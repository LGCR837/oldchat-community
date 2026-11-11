package httpapi

import (
	"net/http"
	"os"
	"strings"
)

// handleClientGuideDocument 公开 Oldchat Android 客户端开发规格文档。
func handleClientGuideDocument(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSpace(os.Getenv("CLIENT_GUIDE_DOC"))
	if path == "" {
		path = "CLIENT_DEVELOPMENT_GUIDE.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "client guide document is not installed", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}
