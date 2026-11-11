package httpapi

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const (
	maxCIPUploadBytes = 2 << 20
	maxCIPFiles       = 128
	maxCIPExtracted   = 8 << 20
)

var cipAllowedHostPattern = regexp.MustCompile(`^(?:\*|(?:\*\.)?[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?)$`)

var allowedCIPAssetExt = map[string]bool{
	".png": true, ".jpg": true, ".jpeg": true, ".webp": true,
	".gif": true, ".json": true, ".txt": true, ".md": true,
}

func (a *API) handleAdminLuaApps(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "admin login required")
		return
	}
	apps, err := a.listLuaApps()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "lua_list_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"apps": apps})
}

func (a *API) handleAdminLuaAppUpload(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "admin login required")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxCIPUploadBytes+64*1024)
	if err := r.ParseMultipartForm(maxCIPUploadBytes); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_cip", "CIP package is too large or malformed")
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, "missing_cip", "multipart field file is required")
		return
	}
	defer file.Close()
	if !strings.HasSuffix(strings.ToLower(header.Filename), ".cip") && !strings.HasSuffix(strings.ToLower(header.Filename), ".zip") {
		writeError(w, http.StatusBadRequest, "invalid_cip", "file extension must be .cip")
		return
	}
	payload, err := io.ReadAll(io.LimitReader(file, maxCIPUploadBytes+1))
	if err != nil || len(payload) == 0 || len(payload) > maxCIPUploadBytes {
		writeError(w, http.StatusBadRequest, "invalid_cip", "CIP package exceeds 2 MiB")
		return
	}
	meta, err := a.installCIP(payload)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_cip", err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, map[string]interface{}{"app": meta, "message": "CIP installed"})
}

func (a *API) handleAdminLuaAppToggle(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "admin login required")
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	enabled := strings.EqualFold(strings.TrimSpace(r.FormValue("enabled")), "true") || r.FormValue("enabled") == "1"
	meta, _, err := a.loadLuaApp(id)
	if err != nil {
		writeError(w, http.StatusNotFound, "lua_app_not_found", "lua app not found")
		return
	}
	meta.Enabled = enabled
	if err := a.writeLuaAppMetadata(id, meta); err != nil {
		writeError(w, http.StatusInternalServerError, "lua_update_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"app": meta})
}

func (a *API) handleAdminLuaAppDelete(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "admin login required")
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	if !validLuaAppID(id) {
		writeError(w, http.StatusBadRequest, "invalid_id", "invalid app id")
		return
	}
	_ = os.RemoveAll(filepath.Join(a.luaAppDir, "packages", id))
	_ = os.Remove(filepath.Join(a.luaAppDir, id+".json"))
	_ = os.Remove(filepath.Join(a.luaAppDir, id+".lua"))
	_ = os.RemoveAll(filepath.Join(a.luaAppDir, "assets", id))
	writeJSON(w, http.StatusOK, map[string]interface{}{"deleted": true, "id": id})
}

func (a *API) installCIP(payload []byte) (luaAppMetadata, error) {
	var meta luaAppMetadata
	reader, err := zip.NewReader(bytes.NewReader(payload), int64(len(payload)))
	if err != nil {
		return meta, errors.New("CIP is not a valid ZIP archive")
	}
	if len(reader.File) == 0 || len(reader.File) > maxCIPFiles {
		return meta, errors.New("CIP must contain 1 to 128 files")
	}
	stagingRoot := filepath.Join(a.luaAppDir, ".staging")
	if err := os.MkdirAll(stagingRoot, 0o750); err != nil {
		return meta, err
	}
	stage, err := os.MkdirTemp(stagingRoot, "cip-")
	if err != nil {
		return meta, err
	}
	defer os.RemoveAll(stage)

	var total int64
	for _, entry := range reader.File {
		name := filepath.ToSlash(entry.Name)
		clean := filepath.ToSlash(filepath.Clean(name))
		if clean == "." || strings.HasPrefix(clean, "../") || strings.HasPrefix(clean, "/") || strings.Contains(clean, "\\") {
			return meta, errors.New("CIP contains an unsafe path")
		}
		if entry.FileInfo().IsDir() {
			continue
		}
		if clean != "manifest.json" && clean != "main.lua" {
			if !strings.HasPrefix(clean, "assets/") || !allowedCIPAssetExt[strings.ToLower(filepath.Ext(clean))] {
				return meta, errors.New("CIP contains a forbidden file: " + clean)
			}
		}
		total += int64(entry.UncompressedSize64)
		if total > maxCIPExtracted {
			return meta, errors.New("CIP extracted content exceeds 8 MiB")
		}
		target := filepath.Join(stage, filepath.FromSlash(clean))
		if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
			return meta, err
		}
		src, err := entry.Open()
		if err != nil {
			return meta, err
		}
		data, readErr := io.ReadAll(io.LimitReader(src, maxCIPExtracted+1))
		src.Close()
		if readErr != nil {
			return meta, readErr
		}
		if err := os.WriteFile(target, data, 0o640); err != nil {
			return meta, err
		}
	}
	manifestBytes, err := os.ReadFile(filepath.Join(stage, "manifest.json"))
	if err != nil {
		return meta, errors.New("manifest.json is required")
	}
	if err := json.Unmarshal(manifestBytes, &meta); err != nil {
		return meta, errors.New("manifest.json is invalid")
	}
	if err := validateCIPMetadata(meta); err != nil {
		return meta, err
	}
	script, err := os.ReadFile(filepath.Join(stage, "main.lua"))
	if err != nil || len(script) == 0 || len(script) > maxLuaScriptBytes {
		return meta, errors.New("main.lua is required and must not exceed 512 KiB")
	}
	meta.SHA256 = ""
	meta.ScriptURL = ""
	if strings.HasPrefix(meta.IconURL, "assets/") {
		meta.IconURL = "/lua-assets/" + meta.ID + "/" + strings.TrimPrefix(meta.IconURL, "assets/")
	}
	normalized, _ := json.MarshalIndent(meta, "", "  ")
	if err := os.WriteFile(filepath.Join(stage, "manifest.json"), normalized, 0o640); err != nil {
		return meta, err
	}
	packages := filepath.Join(a.luaAppDir, "packages")
	if err := os.MkdirAll(packages, 0o750); err != nil {
		return meta, err
	}
	target := filepath.Join(packages, meta.ID)
	backup := target + ".backup-" + time.Now().Format("20060102150405")
	if _, err := os.Stat(target); err == nil {
		if err := os.Rename(target, backup); err != nil {
			return meta, err
		}
	}
	if err := os.Rename(stage, target); err != nil {
		_ = os.Rename(backup, target)
		return meta, err
	}
	_ = os.RemoveAll(backup)
	return meta, nil
}

func validateCIPMetadata(meta luaAppMetadata) error {
	if !validLuaAppID(meta.ID) {
		return errors.New("manifest id must match [a-z0-9_-]{1,64}")
	}
	if strings.TrimSpace(meta.Name) == "" || len([]rune(meta.Name)) > 40 {
		return errors.New("manifest name is required and limited to 40 characters")
	}
	if meta.Version <= 0 {
		return errors.New("manifest version must be positive")
	}
	if len([]rune(meta.Description)) > 200 {
		return errors.New("manifest description is limited to 200 characters")
	}
	allowed := map[string]bool{"camera": true, "network": true, "network_external": true, "storage": true}
	for _, permission := range meta.Permissions {
		if !allowed[permission] {
			return errors.New("unsupported permission: " + permission)
		}
	}
	if len(meta.AllowedHosts) > 32 {
		return errors.New("allowed_hosts is limited to 32 domains")
	}
	for _, host := range meta.AllowedHosts {
		if host != strings.ToLower(host) || !cipAllowedHostPattern.MatchString(host) {
			return errors.New("invalid allowed_hosts domain: " + host)
		}
	}
	if meta.IconURL != "" && !strings.HasPrefix(meta.IconURL, "assets/") && !strings.HasPrefix(meta.IconURL, "https://") && !strings.HasPrefix(meta.IconURL, "http://") {
		return errors.New("icon_url must be assets/... or an HTTP(S) URL")
	}
	return nil
}

func (a *API) writeLuaAppMetadata(id string, meta luaAppMetadata) error {
	path := filepath.Join(a.luaAppDir, id+".json")
	if _, err := os.Stat(filepath.Join(a.luaAppDir, "packages", id, "manifest.json")); err == nil {
		path = filepath.Join(a.luaAppDir, "packages", id, "manifest.json")
	}
	meta.SHA256 = ""
	meta.ScriptURL = ""
	data, err := json.MarshalIndent(meta, "", "  ")
	if err != nil {
		return err
	}
	temp := path + ".tmp"
	if err := os.WriteFile(temp, data, 0o640); err != nil {
		return err
	}
	return os.Rename(temp, path)
}
