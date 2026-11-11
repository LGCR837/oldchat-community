package httpapi

import (
	"net/http"
	"os"
	"strings"
)

// handleInternalMessageSyncDoc 内部文档：消息接收/缓存/群聊同步系统详解。
// 该路由不列入官网任何公开列表（/routes.md、首页 docs 卡片均不含），
// 仅可通过直接 URL /internal/message-sync.md 访问，属于“隐藏路由”。
func handleInternalMessageSyncDoc(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSpace(os.Getenv("MESSAGE_SYNC_DOC"))
	if path == "" {
		path = "docs/message-receive-cache-group-sync.md"
	}
	content, err := os.ReadFile(path)
	if err != nil {
		http.Error(w, "internal document is not installed", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(content)
}
