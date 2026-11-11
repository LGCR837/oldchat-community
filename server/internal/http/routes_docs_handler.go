package httpapi

import (
	"net/http"
	"os"
	"strings"
)

// handleNonAdminRoutesDocument 公开当前非管理后台路由清单，便于客户端和外部集成核对。
func handleNonAdminRoutesDocument(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSpace(os.Getenv("NON_ADMIN_ROUTES_DOC"))
	if path == "" {
		path = "NON_ADMIN_ROUTES.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "route document is not installed", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}
