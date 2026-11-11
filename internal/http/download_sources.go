package httpapi

import (
	"net/http"
	"strings"
)

// downloadSourceResponse 返回动态下载源列表。客户端下载时按顺序尝试：
// 第一个视为“对象存储源”（拼接对象相对路径直链），其余为 API 源（走业务下载接口）。
type downloadSourceResponse struct {
	Sources []string `json:"sources"`
}

// handleDownloadSources 返回当前下载源（可动态调整，客户端无需更新）。
// 需要 Bearer 鉴权；未鉴权时也返回默认源（不含敏感信息），方便旧客户端。
func (a *API) handleDownloadSources(w http.ResponseWriter, r *http.Request) {
	sources := a.downloadSources()
	if len(sources) == 0 {
		// 社区版：唯一来源就是源服务器自身。基址为空时返回空列表，
		// 客户端按自己请求的业务地址同源下载。
		if base := a.filesBase(); base != "" {
			sources = []string{base}
		} else {
			sources = []string{}
		}
	}
	writeJSON(w, http.StatusOK, downloadSourceResponse{Sources: sources})
}

func (a *API) downloadSources() []string {
	if a == nil || a.cfg.DownloadSources == nil {
		return nil
	}
	var out []string
	for _, raw := range a.cfg.DownloadSources {
		v := strings.TrimSpace(raw)
		if v != "" {
			out = append(out, strings.TrimRight(v, "/"))
		}
	}
	return out
}
