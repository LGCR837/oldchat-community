package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func (a *API) handleAdminAPILogs(w http.ResponseWriter, r *http.Request) {
	logPaths := []string{
		filepath.Join("logs", "server.log"),
		"server.log",
		filepath.Join("/A-chat", "logs", "server.log"),
	}

	var content string
	for _, p := range logPaths {
		b, err := os.ReadFile(p)
		if err == nil && len(b) > 0 {
			content = string(b)
			break
		}
	}

	var lines []string
	if content != "" {
		allLines := strings.Split(content, "\n")
		start := len(allLines) - 100
		if start < 0 {
			start = 0
		}
		lines = allLines[start:]
	} else {
		lines = []string{"[System] 暂无独立 server.log 文件输出，服务正通过 systemd journal 正常运行中。"}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": map[string]any{
			"logs": lines,
		},
	})
}
