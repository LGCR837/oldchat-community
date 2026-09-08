package httpapi

import (
	"net/http"
	"os"
	"strings"
)

// handleAPIFullDocument 公开完整 API 文档（/in/api.md 隐蔽路由）。
// 文档文件路径由环境变量 API_FULL_DOC 指定，默认 API_FULL.md。
func handleAPIFullDocument(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSpace(os.Getenv("API_FULL_DOC"))
	if path == "" {
		path = "API_FULL.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "API document is not installed", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}

// handleAPIStandardDocument 公开 Oldchat 服务端 API 格式、认证、分页、错误和兼容规范。
func handleAPIStandardDocument(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSpace(os.Getenv("API_STANDARD_DOC"))
	if path == "" {
		path = "API_STANDARD.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "API document is not installed", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}
