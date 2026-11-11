package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
)

func handleOAuthLoginDocument(w http.ResponseWriter, r *http.Request) {
	path := os.Getenv("OAUTH_LOGIN_DOC")
	if path == "" {
		path = "AUTH_LOGIN_EXTERNAL_API.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		if exe, exeErr := os.Executable(); exeErr == nil {
			content, err = os.ReadFile(filepath.Join(filepath.Dir(exe), "AUTH_LOGIN_EXTERNAL_API.md"))
		}
	}
	if err != nil {
		http.Error(w, "OAuth document not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}
