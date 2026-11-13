package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

// 安装包发版与客户端清单：让接手部署的人只靠管理后台就能完成发版，
// 不必手工编辑 update 目录里的 JSON 或用 SCP 传包。

const (
	releaseChannelFormal = "formal"
	releaseChannelDev    = "dev"

	// releaseMaxArtifactBytes 单个安装包上限，APK 通常个位数 MB，留足余量给整包资源。
	releaseMaxArtifactBytes = 512 << 20
	// releaseMaxConfigBytes 通道配置 JSON 的读取上限。
	releaseMaxConfigBytes = 64 << 10

	clientRegistryName = "clients.json"
)

var releaseArtifactExts = map[string]bool{
	".apk": true,
	".aab": true,
	".zip": true,
	".exe": true,
	".msi": true,
	".dmg": true,
	".ipa": true,
}

type adminReleaseChannel struct {
	Channel      string `json:"channel"`
	ConfigName   string `json:"config_name"`
	Exists       bool   `json:"exists"`
	VersionName  string `json:"version_name"`
	VersionCode  int    `json:"version_code"`
	APK          string `json:"apk"`
	APKURL       string `json:"apk_url"`
	Notes        string `json:"notes"`
	ReleaseNotes string `json:"release_notes"`
}

type adminReleaseArtifact struct {
	Name       string `json:"name"`
	Size       int64  `json:"size"`
	ModifiedAt int64  `json:"modified_at"`
	Referenced string `json:"referenced"`
}

// 客户端板块：目前只维护条目元数据，下载地址留空由后续补充。
type adminClientEntry struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Platform    string `json:"platform"`
	Version     string `json:"version"`
	Description string `json:"description"`
	DownloadURL string `json:"download_url"`
	Enabled     bool   `json:"enabled"`
}

func releaseConfigName(channel string) (string, bool) {
	switch channel {
	case releaseChannelFormal:
		return "update.json", true
	case releaseChannelDev:
		return "dev_update.json", true
	default:
		return "", false
	}
}

func (a *API) updateDirPath() string {
	dir := strings.TrimSpace(a.cfg.UpdateDir)
	if dir == "" {
		return "update"
	}
	return dir
}

// handleAdminAPIReleaseGet GET /v1/admin/release
func (a *API) handleAdminAPIReleaseGet(w http.ResponseWriter, r *http.Request) {
	dir := a.updateDirPath()
	channels := make([]adminReleaseChannel, 0, 2)
	referenced := make(map[string]string)
	for _, ch := range []string{releaseChannelFormal, releaseChannelDev} {
		item := a.readReleaseChannel(dir, ch)
		channels = append(channels, item)
		for _, raw := range []string{item.APK, item.APKURL} {
			raw = strings.TrimSpace(raw)
			if raw == "" || strings.Contains(raw, "://") {
				continue
			}
			referenced[filepath.Base(raw)] = ch
		}
	}

	artifacts := make([]adminReleaseArtifact, 0, 16)
	if entries, err := os.ReadDir(dir); err == nil {
		for _, e := range entries {
			if e.IsDir() || strings.HasPrefix(e.Name(), ".") {
				continue
			}
			info, err := e.Info()
			if err != nil {
				continue
			}
			artifacts = append(artifacts, adminReleaseArtifact{
				Name:       e.Name(),
				Size:       info.Size(),
				ModifiedAt: info.ModTime().Unix(),
				Referenced: referenced[e.Name()],
			})
		}
	}
	sort.Slice(artifacts, func(i, j int) bool { return artifacts[i].ModifiedAt > artifacts[j].ModifiedAt })

	writeJSON(w, http.StatusOK, map[string]any{
		"code":     200,
		"dir":      dir,
		"channels": channels,
		"files":    artifacts,
		"max_mb":   releaseMaxArtifactBytes >> 20,
	})
}

func (a *API) readReleaseChannel(dir, channel string) adminReleaseChannel {
	name, _ := releaseConfigName(channel)
	item := adminReleaseChannel{Channel: channel, ConfigName: name}
	raw, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		return item
	}
	var release landingRelease
	if err := json.Unmarshal(raw, &release); err != nil {
		return item
	}
	item.Exists = true
	item.VersionName = release.VersionName
	item.VersionCode = release.VersionCode
	item.APK = release.APK
	item.APKURL = release.APKURL
	item.Notes = release.Notes
	item.ReleaseNotes = release.ReleaseNotes
	return item
}

// handleAdminAPIReleaseSave POST /v1/admin/release/save
func (a *API) handleAdminAPIReleaseSave(w http.ResponseWriter, r *http.Request) {
	var req adminReleaseChannel
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, releaseMaxConfigBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_req", "json parse error")
		return
	}
	channel := strings.TrimSpace(req.Channel)
	name, ok := releaseConfigName(channel)
	if !ok {
		writeError(w, http.StatusBadRequest, "invalid_channel", "unknown channel")
		return
	}
	if req.VersionCode < 0 {
		writeError(w, http.StatusBadRequest, "invalid_version", "version code must not be negative")
		return
	}
	apk := strings.TrimSpace(req.APK)
	if apk != "" && !strings.Contains(apk, "://") {
		apk = filepath.Base(strings.ReplaceAll(apk, `\`, "/"))
		if _, err := os.Stat(filepath.Join(a.updateDirPath(), apk)); err != nil {
			writeError(w, http.StatusBadRequest, "artifact_missing", "安装包不存在于 update 目录")
			return
		}
	}

	release := landingRelease{
		VersionName:  strings.TrimSpace(req.VersionName),
		VersionCode:  req.VersionCode,
		APK:          apk,
		APKURL:       strings.TrimSpace(req.APKURL),
		Notes:        strings.TrimSpace(req.Notes),
		ReleaseNotes: strings.TrimSpace(req.ReleaseNotes),
	}
	if release.VersionCode > 0 && release.VersionName == "" {
		release.VersionName = "1.0." + strconv.Itoa(release.VersionCode)
	}

	dir := a.updateDirPath()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, "write_failed", "无法创建 update 目录")
		return
	}
	body, _ := json.MarshalIndent(release, "", "  ")
	body = append(body, '\n')
	if err := os.WriteFile(filepath.Join(dir, name), body, 0o644); err != nil {
		writeError(w, http.StatusInternalServerError, "write_failed", "写入配置失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "版本信息已保存", "config_name": name})
}

// handleAdminAPIReleaseUpload POST /v1/admin/release/upload
// 流式接收安装包，落到 update 目录；同名文件不覆盖，自动追加时间戳。
func (a *API) handleAdminAPIReleaseUpload(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, releaseMaxArtifactBytes+(8<<20))
	limitUploadBody(r)
	reader, err := r.MultipartReader()
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_upload", "需要 multipart 上传")
		return
	}

	dir := a.updateDirPath()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, "write_failed", "无法创建 update 目录")
		return
	}

	for {
		part, partErr := reader.NextPart()
		if partErr == io.EOF {
			break
		}
		if partErr != nil {
			writeError(w, http.StatusBadRequest, "invalid_upload", "上传中断")
			return
		}
		if part.FormName() != "file" || part.FileName() == "" {
			_, _ = io.Copy(io.Discard, io.LimitReader(part, 64<<10))
			_ = part.Close()
			continue
		}

		name := sanitizeReleaseFileName(part.FileName())
		if name == "" {
			_ = part.Close()
			writeError(w, http.StatusBadRequest, "invalid_name", "文件名不合法")
			return
		}
		if !releaseArtifactExts[strings.ToLower(filepath.Ext(name))] {
			_ = part.Close()
			writeError(w, http.StatusBadRequest, "invalid_ext", "只接受 apk、aab、zip、exe、msi、dmg、ipa")
			return
		}
		name = uniqueArtifactName(dir, name)

		tmp, err := os.CreateTemp(dir, ".upload-*.part")
		if err != nil {
			_ = part.Close()
			writeError(w, http.StatusInternalServerError, "upload_failed", "无法写入临时文件")
			return
		}
		tmpPath := tmp.Name()
		written, copyErr := io.Copy(tmp, io.LimitReader(part, releaseMaxArtifactBytes+1))
		closeErr := tmp.Close()
		_ = part.Close()
		if copyErr != nil || closeErr != nil {
			_ = os.Remove(tmpPath)
			writeError(w, http.StatusInternalServerError, "upload_failed", "上传失败")
			return
		}
		if written <= 0 {
			_ = os.Remove(tmpPath)
			writeError(w, http.StatusBadRequest, "empty_file", "文件为空")
			return
		}
		if written > releaseMaxArtifactBytes {
			_ = os.Remove(tmpPath)
			writeError(w, http.StatusRequestEntityTooLarge, "file_too_large", "文件超过上限")
			return
		}
		if err := os.Rename(tmpPath, filepath.Join(dir, name)); err != nil {
			_ = os.Remove(tmpPath)
			writeError(w, http.StatusInternalServerError, "upload_failed", "保存失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"code": 200,
			"name": name,
			"size": written,
		})
		return
	}
	writeError(w, http.StatusBadRequest, "no_file", "没有接收到文件")
}

// handleAdminAPIReleaseDelete POST /v1/admin/release/delete
func (a *API) handleAdminAPIReleaseDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_req", "json parse error")
		return
	}
	name := sanitizeReleaseFileName(req.Name)
	if name == "" {
		writeError(w, http.StatusBadRequest, "invalid_name", "文件名不合法")
		return
	}
	if name == "update.json" || name == "dev_update.json" {
		writeError(w, http.StatusBadRequest, "protected_file", "通道配置文件请通过保存操作覆盖")
		return
	}
	dir := a.updateDirPath()
	for _, ch := range []string{releaseChannelFormal, releaseChannelDev} {
		item := a.readReleaseChannel(dir, ch)
		if strings.EqualFold(filepath.Base(item.APK), name) || strings.EqualFold(filepath.Base(item.APKURL), name) {
			writeError(w, http.StatusBadRequest, "artifact_referenced", "该安装包仍被版本配置引用")
			return
		}
	}
	if err := os.Remove(filepath.Join(dir, name)); err != nil {
		if os.IsNotExist(err) {
			writeError(w, http.StatusNotFound, "not_found", "文件不存在")
			return
		}
		writeError(w, http.StatusInternalServerError, "delete_failed", "删除失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "已删除"})
}

// handleAdminAPIClientsGet GET /v1/admin/clients
func (a *API) handleAdminAPIClientsGet(w http.ResponseWriter, r *http.Request) {
	entries, err := loadClientRegistry(clientRegistryName)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "read_failed", "读取客户端清单失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "clients": entries})
}

// handleAdminAPIClientsSave POST /v1/admin/clients
func (a *API) handleAdminAPIClientsSave(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Clients []adminClientEntry `json:"clients"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, releaseMaxConfigBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_req", "json parse error")
		return
	}
	if len(req.Clients) > 64 {
		writeError(w, http.StatusBadRequest, "too_many", "客户端条目过多")
		return
	}
	cleaned := make([]adminClientEntry, 0, len(req.Clients))
	seen := make(map[string]bool, len(req.Clients))
	for _, entry := range req.Clients {
		entry.ID = strings.TrimSpace(entry.ID)
		entry.Name = strings.TrimSpace(entry.Name)
		if entry.Name == "" {
			continue
		}
		if entry.ID == "" || seen[entry.ID] {
			entry.ID = clientEntryID(entry.Name, len(seen)+1)
		}
		seen[entry.ID] = true
		entry.Platform = strings.TrimSpace(entry.Platform)
		entry.Version = strings.TrimSpace(entry.Version)
		entry.Description = strings.TrimSpace(entry.Description)
		entry.DownloadURL = strings.TrimSpace(entry.DownloadURL)
		cleaned = append(cleaned, entry)
	}

	body, _ := json.MarshalIndent(map[string]any{"clients": cleaned}, "", "  ")
	body = append(body, '\n')
	if err := os.WriteFile(clientRegistryName, body, 0o644); err != nil {
		writeError(w, http.StatusInternalServerError, "write_failed", "保存客户端清单失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "客户端清单已保存", "clients": cleaned})
}

// loadClientRegistry 读取客户端清单，文件不存在时返回内置默认条目，
// 方便接手部署的人第一次打开页面就能看到结构。
func loadClientRegistry(path string) ([]adminClientEntry, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return defaultClientEntries(), nil
		}
		return nil, err
	}
	var payload struct {
		Clients []adminClientEntry `json:"clients"`
	}
	if err := json.Unmarshal(raw, &payload); err != nil {
		return defaultClientEntries(), nil
	}
	if payload.Clients == nil {
		return []adminClientEntry{}, nil
	}
	return payload.Clients, nil
}

func defaultClientEntries() []adminClientEntry {
	return []adminClientEntry{
		{ID: "android", Name: "Android 客户端", Platform: "Android", Version: "", Description: "适配 Android 2.3 及以上版本", Enabled: true},
		{ID: "web", Name: "网页版", Platform: "Web", Version: "", Description: "浏览器直接使用，无需安装", Enabled: true},
		{ID: "windows", Name: "Windows 客户端", Platform: "Windows", Version: "", Description: "桌面端，适配 Windows 7 及以上", Enabled: true},
	}
}

func clientEntryID(name string, fallback int) string {
	var b strings.Builder
	for _, r := range strings.ToLower(name) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		} else if r == ' ' || r == '-' || r == '_' {
			b.WriteByte('-')
		}
	}
	id := strings.Trim(b.String(), "-")
	if id == "" {
		id = "client-" + strconv.Itoa(fallback)
	}
	return id
}

// sanitizeReleaseFileName 只保留安全字符，拒绝路径穿越与隐藏文件。
func sanitizeReleaseFileName(name string) string {
	base := filepath.Base(strings.ReplaceAll(strings.TrimSpace(name), `\`, "/"))
	if base == "." || base == ".." || base == "/" {
		return ""
	}
	var b strings.Builder
	for _, r := range base {
		if r < 32 || r == 127 {
			continue
		}
		switch r {
		case '/', '\\', ':', '*', '?', '"', '<', '>', '|':
			continue
		}
		b.WriteRune(r)
	}
	out := strings.TrimSpace(b.String())
	out = strings.TrimLeft(out, ".")
	if out == "" {
		return ""
	}
	if len(out) > 120 {
		ext := filepath.Ext(out)
		stem := strings.TrimSuffix(out, ext)
		if len(stem) > 100 {
			stem = stem[:100]
		}
		out = stem + ext
	}
	return out
}

// uniqueArtifactName 避免覆盖正在分发的旧包，同名时追加时间戳。
func uniqueArtifactName(dir, name string) string {
	if _, err := os.Stat(filepath.Join(dir, name)); os.IsNotExist(err) {
		return name
	}
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	stamp := time.Now().Format("20060102-150405")
	candidate := stem + "-" + stamp + ext
	for i := 1; i < 100; i++ {
		if _, err := os.Stat(filepath.Join(dir, candidate)); os.IsNotExist(err) {
			return candidate
		}
		candidate = stem + "-" + stamp + "-" + strconv.Itoa(i) + ext
	}
	return stem + "-" + stamp + "-" + strconv.FormatInt(time.Now().UnixNano(), 10) + ext
}
