package httpapi

import (
	"net/http"
	"os"
	"strings"
)

func handleLuaCIPDevelopmentGuide(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSpace(os.Getenv("LUA_CIP_DOC"))
	if path == "" {
		path = "LUA_CIP_DEVELOPMENT_GUIDE.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "Lua CIP development guide is not installed", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}
