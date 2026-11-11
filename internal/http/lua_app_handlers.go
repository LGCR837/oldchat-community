package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/go-chi/chi/v5"
)

const maxLuaScriptBytes = 512 * 1024

type luaAppMetadata struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Description  string   `json:"description,omitempty"`
	Version      int      `json:"version"`
	IconURL      string   `json:"icon_url,omitempty"`
	Enabled      bool     `json:"enabled"`
	Order        int      `json:"order,omitempty"`
	Permissions  []string `json:"permissions"`
	AllowedHosts []string `json:"allowed_hosts,omitempty"`
	SHA256       string   `json:"sha256,omitempty"`
	ScriptURL    string   `json:"script_url,omitempty"`
}

type luaAppManifestResponse struct {
	Apps []luaAppMetadata `json:"apps"`
}

type luaAppScriptResponse struct {
	ID      string `json:"id"`
	Version int    `json:"version"`
	SHA256  string `json:"sha256"`
	Script  string `json:"script"`
}

func resolveLuaAppDir() string {
	if value := strings.TrimSpace(os.Getenv("LUA_APP_DIR")); value != "" {
		return value
	}
	return "lua_apps"
}

func validLuaAppID(id string) bool {
	if id == "" || len(id) > 64 {
		return false
	}
	for _, r := range id {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			continue
		}
		return false
	}
	return true
}

func (a *API) loadLuaApp(id string) (luaAppMetadata, []byte, error) {
	var meta luaAppMetadata
	if !validLuaAppID(id) {
		return meta, nil, os.ErrNotExist
	}
	metaPath := filepath.Join(a.luaAppDir, id+".json")
	scriptPath := filepath.Join(a.luaAppDir, id+".lua")
	packageManifest := filepath.Join(a.luaAppDir, "packages", id, "manifest.json")
	if _, statErr := os.Stat(packageManifest); statErr == nil {
		metaPath = packageManifest
		scriptPath = filepath.Join(a.luaAppDir, "packages", id, "main.lua")
	}
	metaBytes, err := os.ReadFile(metaPath)
	if err != nil {
		return meta, nil, err
	}
	if err = json.Unmarshal(metaBytes, &meta); err != nil {
		return meta, nil, err
	}
	if meta.ID == "" {
		meta.ID = id
	}
	if meta.ID != id || !validLuaAppID(meta.ID) {
		return meta, nil, os.ErrInvalid
	}
	if meta.Version <= 0 {
		meta.Version = 1
	}
	script, err := os.ReadFile(scriptPath)
	if err != nil {
		return meta, nil, err
	}
	if len(script) == 0 || len(script) > maxLuaScriptBytes {
		return meta, nil, os.ErrInvalid
	}
	sum := sha256.Sum256(script)
	meta.SHA256 = hex.EncodeToString(sum[:])
	meta.ScriptURL = "/discover/lua/apps/" + id
	return meta, script, nil
}

func (a *API) listLuaApps() ([]luaAppMetadata, error) {
	ids := make(map[string]bool)
	entries, err := os.ReadDir(a.luaAppDir)
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".json") {
			ids[strings.TrimSuffix(entry.Name(), ".json")] = true
		}
	}
	packages, packageErr := os.ReadDir(filepath.Join(a.luaAppDir, "packages"))
	if packageErr == nil {
		for _, entry := range packages {
			if entry.IsDir() && validLuaAppID(entry.Name()) {
				ids[entry.Name()] = true
			}
		}
	}
	apps := make([]luaAppMetadata, 0, len(ids))
	for id := range ids {
		meta, _, loadErr := a.loadLuaApp(id)
		if loadErr == nil {
			apps = append(apps, meta)
		}
	}
	sort.SliceStable(apps, func(i, j int) bool {
		if apps[i].Order != apps[j].Order {
			return apps[i].Order < apps[j].Order
		}
		return apps[i].ID < apps[j].ID
	})
	return apps, nil
}

func (a *API) handleLuaAppManifest(w http.ResponseWriter, r *http.Request) {
	all, err := a.listLuaApps()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "lua_manifest_error", "cannot read lua apps")
		return
	}
	apps := make([]luaAppMetadata, 0, len(all))
	for _, app := range all {
		if app.Enabled {
			apps = append(apps, app)
		}
	}
	writeJSON(w, http.StatusOK, luaAppManifestResponse{Apps: apps})
}

func (a *API) handleLuaAppScript(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(chi.URLParam(r, "id"))
	meta, script, err := a.loadLuaApp(id)
	if err != nil || !meta.Enabled {
		writeError(w, http.StatusNotFound, "lua_app_not_found", "lua app not found")
		return
	}
	writeJSON(w, http.StatusOK, luaAppScriptResponse{
		ID: meta.ID, Version: meta.Version, SHA256: meta.SHA256, Script: string(script),
	})
}

func (a *API) handleLuaAppAsset(w http.ResponseWriter, r *http.Request) {
	rel := filepath.Clean(strings.TrimPrefix(chi.URLParam(r, "*"), "/"))
	if rel == "." || strings.HasPrefix(rel, "..") || filepath.IsAbs(rel) {
		http.NotFound(w, r)
		return
	}
	parts := strings.SplitN(filepath.ToSlash(rel), "/", 2)
	if len(parts) != 2 || !validLuaAppID(parts[0]) {
		http.NotFound(w, r)
		return
	}
	path := filepath.Join(a.luaAppDir, "packages", parts[0], "assets", filepath.FromSlash(parts[1]))
	base, _ := filepath.Abs(filepath.Join(a.luaAppDir, "packages", parts[0], "assets"))
	resolved, _ := filepath.Abs(path)
	if resolved != base && !strings.HasPrefix(resolved, base+string(os.PathSeparator)) {
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, resolved)
}
