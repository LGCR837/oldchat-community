package httpapi

import (
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/go-chi/chi/v5"

	"metrochat/internal/data"
)

func (a *API) handleFileAssetDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
		return
	}
	id := strings.TrimSpace(chi.URLParam(r, "fileID"))
	asset, err := a.fileAssets.GetByID(r.Context(), id)
	if err != nil {
		if err == data.ErrNotFound {
			http.NotFound(w, r)
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	path, ok := safeAssetPath(a.cfg.UploadDir, asset.RelativePath)
	if !ok {
		http.NotFound(w, r)
		return
	}
	file, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	setPrivateFileHeaders(w)
	if asset.ContentType != "" {
		w.Header().Set("Content-Type", asset.ContentType)
	}
	if asset.SHA256 != "" {
		w.Header().Set("ETag", `"`+asset.SHA256+`"`)
	}
	name := strings.TrimSpace(asset.OriginalName)
	if name == "" {
		name = filepath.Base(asset.RelativePath)
	}
	if disposition := mime.FormatMediaType("attachment", map[string]string{"filename": name}); disposition != "" {
		w.Header().Set("Content-Disposition", disposition)
	}
	http.ServeContent(w, r, name, info.ModTime(), file)
}

func (a *API) handleResourceItemDownload(w http.ResponseWriter, r *http.Request) {
	if !a.cfg.ResourceSquareEnabled {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
		return
	}
	id := strings.TrimSpace(chi.URLParam(r, "itemID"))
	item, err := a.resources.GetItemByID(r.Context(), id)
	if err != nil {
		if err == data.ErrNotFound {
			http.NotFound(w, r)
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	path := resourceUploadPath(a.cfg.UploadDir, item.URL)
	if path == "" {
		http.NotFound(w, r)
		return
	}
	file, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	setPrivateFileHeaders(w)
	name := strings.TrimSpace(item.Name)
	if name == "" {
		name = filepath.Base(path)
	}
	if disposition := mime.FormatMediaType("attachment", map[string]string{"filename": name}); disposition != "" {
		w.Header().Set("Content-Disposition", disposition)
	}
	http.ServeContent(w, r, name, info.ModTime(), file)
}

func safeAssetPath(uploadDir, relativePath string) (string, bool) {
	rel := filepath.ToSlash(filepath.Clean(strings.TrimSpace(relativePath)))
	if rel == "" || rel == "." || strings.HasPrefix(rel, "../") || strings.Contains(rel, "/../") || filepath.IsAbs(rel) {
		return "", false
	}
	if !strings.HasPrefix(rel, "files/") && !strings.HasPrefix(rel, "resources/") {
		return "", false
	}
	root, err := filepath.Abs(uploadDir)
	if err != nil {
		return "", false
	}
	full, err := filepath.Abs(filepath.Join(root, filepath.FromSlash(rel)))
	if err != nil || (full != root && !strings.HasPrefix(full, root+string(os.PathSeparator))) {
		return "", false
	}
	return full, true
}

func setPrivateFileHeaders(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "private, no-store")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("Vary", "Authorization")
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; sandbox")
}

func withProtectedUploadPaths(auth func(http.Handler) http.Handler, next http.Handler) http.Handler {
	protected := auth(next)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := strings.ToLower(r.URL.Path)
		if strings.Contains(path, "/uploads/resources/") || strings.Contains(path, "/resources/") {
			http.NotFound(w, r)
			return
		}
		if strings.Contains(path, "/uploads/files/") || strings.Contains(path, "/uploads/filewearhouse/") {
			protected.ServeHTTP(w, r)
			return
		}
		next.ServeHTTP(w, r)
	})
}
