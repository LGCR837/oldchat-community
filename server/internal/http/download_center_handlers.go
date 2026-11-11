package httpapi

import (
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
)

// handleUpdateCenter 同时承载 /update/ 下载中心页面与 /update/<file> 安装包分发。
// 原实现直接对目录做 FileServer，目录为空时整页 404，故改为内置页面渲染。
func (a *API) handleUpdateCenter(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(strings.TrimPrefix(r.URL.Path, "/update"), "/")
	if name == "" {
		a.renderDownloadCenter(w)
		return
	}
	clean := path.Clean("/" + name)
	clean = strings.TrimPrefix(clean, "/")
	if clean == "" || clean == "." || strings.Contains(clean, `\`) {
		http.NotFound(w, r)
		return
	}
	target := filepath.Join(a.cfg.UpdateDir, filepath.FromSlash(clean))
	info, err := os.Stat(target)
	if err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	if strings.HasSuffix(strings.ToLower(clean), ".json") {
		w.Header().Set("Cache-Control", "no-store")
	}
	http.ServeFile(w, r, target)
}

func (a *API) renderDownloadCenter(w http.ResponseWriter) {
	formalRel := a.loadLandingRelease()
	devRel := a.loadDevLandingRelease()

	data := downloadPageData{
		Formal: a.downloadChannel("旧聊 正式版", "正式发布", "update.json", "", formalRel),
		Dev:    a.downloadChannel("旧聊 测试版 (Dev)", "测试通道", "dev_update.json", " ver-badge-test", devRel),
	}
	if !data.Formal.Available && !data.Dev.Available {
		data.Notice = "安装包尚未上传，把 APK 放进服务器 update 目录后刷新本页即可。"
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = downloadPageTpl.Execute(w, data)
}

func (a *API) downloadChannel(title, channel, configName, badgeClass string, rel landingRelease) downloadChannel {
	item := downloadChannel{
		Title:       title,
		Channel:     channel,
		BadgeClass:  badgeClass,
		VersionCode: "未发布",
		Notes:       "尚未发布该通道版本。",
		ConfigName:  configName,
	}
	if rel.VersionName != "" {
		item.Version = "v" + rel.VersionName
	}
	if rel.VersionCode > 0 {
		item.VersionCode = strconv.Itoa(rel.VersionCode)
	}
	if notes := strings.TrimSpace(rel.Notes); notes != "" {
		item.Notes = notes
	} else if notes := strings.TrimSpace(rel.ReleaseNotes); notes != "" {
		item.Notes = notes
	}
	if item.Version == "" {
		item.Version = "未发布"
		item.BadgeClass += " ver-badge-empty"
	}
	if url, ok := a.releaseArtifactURL(rel); ok {
		item.DownloadURL = url
		item.Available = true
	}
	if info, err := os.Stat(filepath.Join(a.cfg.UpdateDir, configName)); err == nil && !info.IsDir() {
		item.ConfigURL = "/update/" + configName
	}
	return item
}

// releaseArtifactURL 解析安装包地址：优先绝对地址，其次 update 目录内的本地文件。
func (a *API) releaseArtifactURL(rel landingRelease) (string, bool) {
	raw := strings.TrimSpace(rel.APKURL)
	if raw == "" {
		raw = strings.TrimSpace(rel.APK)
	}
	if raw == "" {
		return "", false
	}
	if strings.HasPrefix(raw, "http://") || strings.HasPrefix(raw, "https://") {
		return raw, true
	}
	name := filepath.Base(raw)
	if name == "" || name == "." || name == ".." {
		return "", false
	}
	if info, err := os.Stat(filepath.Join(a.cfg.UpdateDir, name)); err != nil || info.IsDir() {
		return "", false
	}
	return "/update/" + name, true
}
