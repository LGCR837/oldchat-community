package httpapi

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"metrochat/internal/data"
)

const (
	maxCipPackageBytes   = 2 * 1024 * 1024 // 2MB
	maxCipExtractedBytes = 8 * 1024 * 1024 // 8MB
	maxCipFilesCount     = 128
)

var validCipIDRegexp = regexp.MustCompile(`^[a-z0-9_-]{1,64}$`)

// cipManifest represents manifest.json inside .cip package
type cipManifest struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Description  string   `json:"description"`
	Version      int      `json:"version"`
	IconURL      string   `json:"icon_url"`
	Permissions  []string `json:"permissions"`
	AllowedHosts []string `json:"allowed_hosts"`
}

const cipUploadHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>OldChat CIP 小程序开发者上传平台</title>
  <style>
    body{max-width:720px;margin:0 auto;padding:24px;font-family:-apple-system,"Noto Sans SC",sans-serif;line-height:1.7;color:#111;background:#fff}
    h1{font-size:26px;margin:8px 0}
    h2{font-size:18px;margin:24px 0 8px;border-bottom:1px solid #ddd;padding-bottom:6px}
    a{color:#0366d6;text-decoration:none}
    a:hover{text-decoration:underline}
    .btn{display:inline-block;padding:10px 20px;border:2px solid #111;font-weight:700;margin:8px 4px;color:#111;background:#fff;text-decoration:none;cursor:pointer}
    .btn.primary{background:#111;color:#fff}
    .box{border:1px solid #ccc;padding:16px;margin:16px 0;background:#fafafa}
    .ok{color:#0a7a0a;font-weight:bold;margin:12px 0}
    .err{color:#c00;font-weight:bold;margin:12px 0}
    input[type=text], input[type=password], textarea{width:100%;box-sizing:border-box;padding:8px 10px;margin:6px 0 12px;border:1px solid #999;font-size:14px}
    label{font-weight:bold;font-size:14px}
    .tip{color:#666;font-size:13px;margin-top:-6px;margin-bottom:10px}

    /* 深色自适应：不支持的浏览器整块忽略，保持上面的浅色 */
    @media (prefers-color-scheme: dark) {
      body{color:#e6e8eb;background:#14161a;color-scheme:dark}
      h2{border-bottom-color:#2f353d}
      a{color:#7fb2e0}
      .btn{border-color:#3a78a8;background:#1a1d21;color:#e6e8eb}
      .btn.primary{background:#2e6591;border-color:#3a78a8;color:#fff}
      .box{border-color:#2f353d;background:#20242a}
      .ok{color:#4ac26b}
      .err{color:#ef6b73}
      input[type=text],input[type=password],textarea{background:#20242a;border-color:#2f353d;color:#e6e8eb}
      .tip{color:#8b94a0}
    }
  </style>
</head>
<body>
  <p><a href="/">← 返回首页</a> ｜ <a href="/lua-cip.md">CIP 开发规范手册</a></p>
  <h1>OldChat CIP 小程序上传与上架</h1>
  <p>欢迎提交您开发的 CIP（Chat Integration Package）小程序！提交后将进入官方审核，审核通过后全网用户均可在客户端<b>“小程序” → “小程序商店”</b>一键安装使用。</p>

  {{if .Success}}
  <div class="box ok">
    <h3>🎉 上传成功，已进入审核队列！</h3>
    <p>小程序 ID：<strong>{{.AppID}}</strong> ｜ 名称：<strong>{{.AppName}}</strong>（v{{.AppVersion}}）</p>
    <p>管理员审核通过后，该应用将自动上架并在客户端小程序商店展示。</p>
    <p><a class="btn primary" href="/cip-upload">继续上传新小程序</a></p>
  </div>
  {{else}}

  {{if .Error}}
  <div class="box err">❌ 错误：{{.Error}}</div>
  {{end}}

  <form method="post" action="/cip-upload" enctype="multipart/form-data" class="box">
    <label>1. 账号身份（可选 / 建议填写）</label>
    <input type="text" name="author" placeholder="作者昵称或账号 UID（如：USR-123456）" value="{{.Author}}">
    <div class="tip">填写后将在小程序商店展示您的开发者信息。</div>

    <label>2. 选择 .cip 小程序包（必需，最大 2MB）</label>
    <input type="file" name="cip_file" accept=".cip,.zip" required style="margin:8px 0 14px;display:block;">
    <div class="tip">
      包内根目录必须包含 <code>manifest.json</code> 与 <code>main.lua</code>（可选 <code>assets/</code> 资源目录）。<br>
      打包命令示例：<code>zip my_app.cip manifest.json main.lua assets/*</code>
    </div>

    <label>3. 开发者备注说明（可选）</label>
    <textarea name="notes" rows="3" placeholder="填写给管理员的审核说明、版本变更或功能演示说明…"></textarea>

    <button type="submit" class="btn primary">提交并申请上架</button>
  </form>
  {{end}}

  <h2>CIP 打包核心规范速查</h2>
  <ul>
    <li><b>体积限制</b>：压缩包 ≤ 2MB，解压后 ≤ 8MB，文件数量 ≤ 128 个；</li>
    <li><b>根目录文件</b>：<code>manifest.json</code> 与 <code>main.lua</code> 必须在根目录，不得包裹外层同名文件夹；</li>
    <li><b>资源图片</b>：放在 <code>assets/</code> 目录下，推荐提供 <code>assets/icon.png</code> 作为应用图标；</li>
    <li><b>权限声明</b>：仅声明实际用到的权限（<code>storage</code>, <code>network</code>, <code>network_external</code>, <code>camera</code>）。</li>
  </ul>
</body>
</html>`

var cipUploadTpl = template.Must(template.New("cip_upload").Parse(cipUploadHTML))

type cipUploadData struct {
	Success    bool
	Error      string
	AppID      string
	AppName    string
	AppVersion int
	Author     string
}

func (a *API) handleCipUploadPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = cipUploadTpl.Execute(w, cipUploadData{})
}

func (a *API) handleCipUploadSubmit(w http.ResponseWriter, r *http.Request) {
	if a.cipStore == nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "服务端小程序商店组件未就绪"})
		return
	}

	if err := r.ParseMultipartForm(maxCipPackageBytes + 512*1024); err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "文件上传过大（限 2MB）"})
		return
	}

	file, header, err := r.FormFile("cip_file")
	if err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "请选择有效的 .cip 小程序文件"})
		return
	}
	defer file.Close()

	author := strings.TrimSpace(r.FormValue("author"))
	if author == "" {
		author = "匿名开发者"
	}

	// Read entire package into memory with limit
	rawBytes, err := io.ReadAll(io.LimitReader(file, maxCipPackageBytes+1))
	if err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "读取文件失败"})
		return
	}
	if len(rawBytes) > maxCipPackageBytes {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "CIP 压缩包不能超过 2MB"})
		return
	}

	// Validate zip and parse manifest.json + main.lua
	zipReader, err := zip.NewReader(bytes.NewReader(rawBytes), int64(len(rawBytes)))
	if err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "无效的 CIP 归档（需为标准 ZIP 格式）"})
		return
	}

	if len(zipReader.File) > maxCipFilesCount {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "CIP 包内文件数量不能超过 128 个"})
		return
	}

	var manifestBytes []byte
	var mainLuaBytes []byte
	var iconBytes []byte
	var iconExt string
	var totalExtracted int64

	for _, f := range zipReader.File {
		if f.FileInfo().IsDir() {
			continue
		}
		cleanName := filepath.ToSlash(f.Name)
		if strings.HasPrefix(cleanName, "/") || strings.Contains(cleanName, "../") || strings.Contains(cleanName, `\`) {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_ = cipUploadTpl.Execute(w, cipUploadData{Error: "包内包含不安全路径：" + cleanName})
			return
		}

		rc, err := f.Open()
		if err != nil {
			continue
		}
		content, readErr := io.ReadAll(io.LimitReader(rc, 2*1024*1024))
		rc.Close()
		if readErr != nil {
			continue
		}
		totalExtracted += int64(len(content))
		if totalExtracted > maxCipExtractedBytes {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_ = cipUploadTpl.Execute(w, cipUploadData{Error: "CIP 包解压后总大小超过 8MB 限制"})
			return
		}

		if cleanName == "manifest.json" {
			manifestBytes = content
		} else if cleanName == "main.lua" {
			mainLuaBytes = content
		} else if cleanName == "assets/icon.png" || cleanName == "assets/icon.jpg" || cleanName == "assets/icon.webp" {
			iconBytes = content
			iconExt = filepath.Ext(cleanName)
		}
	}

	if len(manifestBytes) == 0 {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "包根目录缺少必需的 manifest.json"})
		return
	}
	if len(mainLuaBytes) == 0 {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "包根目录缺少必需的 main.lua"})
		return
	}

	var manifest cipManifest
	if err := json.Unmarshal(manifestBytes, &manifest); err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "manifest.json 格式错误：" + err.Error()})
		return
	}

	manifest.ID = strings.TrimSpace(manifest.ID)
	if !validCipIDRegexp.MatchString(manifest.ID) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "manifest.json 中 id 不合法（限 1~64 位小写字母、数字、下划线、中划线）"})
		return
	}
	manifest.Name = strings.TrimSpace(manifest.Name)
	if manifest.Name == "" || len(manifest.Name) > 40 {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "应用名称不能为空且不能超过 40 字"})
		return
	}
	if manifest.Version <= 0 {
		manifest.Version = 1
	}

	// Compute sha256
	sum := sha256.Sum256(rawBytes)
	shaStr := hex.EncodeToString(sum[:])

	// Save CIP file to disk: <UploadDir>/cip/<id>.cip
	cipDir := filepath.Join(a.cfg.UploadDir, "cip")
	_ = os.MkdirAll(cipDir, 0755)
	destFileName := fmt.Sprintf("%s.cip", manifest.ID)
	destPath := filepath.Join(cipDir, destFileName)
	if err := os.WriteFile(destPath, rawBytes, 0644); err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "保存 CIP 文件到磁盘失败"})
		return
	}

	// Save icon if present
	iconURL := manifest.IconURL
	if len(iconBytes) > 0 {
		iconName := fmt.Sprintf("cip_icon_%s%s", manifest.ID, iconExt)
		iconDest := filepath.Join(a.cfg.UploadDir, "media", iconName)
		_ = os.MkdirAll(filepath.Join(a.cfg.UploadDir, "media"), 0755)
		if writeErr := os.WriteFile(iconDest, iconBytes, 0644); writeErr == nil {
			iconURL = "/v1/uploads/media/" + iconName
		}
	}

	permsBytes, _ := json.Marshal(manifest.Permissions)
	if len(permsBytes) == 0 {
		permsBytes = []byte("[]")
	}

	cipFileURL := fmt.Sprintf("/v1/cip/store/download/%s", manifest.ID)

	storeItem := data.CipStoreItem{
		ID:           manifest.ID,
		Name:         manifest.Name,
		Description:  manifest.Description,
		Version:      manifest.Version,
		AuthorUID:    author,
		AuthorName:   author,
		IconURL:      iconURL,
		CipFileURL:   cipFileURL,
		FileSize:     int64(len(rawBytes)),
		SHA256:       shaStr,
		Permissions:  string(permsBytes),
		Status:       "pending",
		RejectReason: "",
		CreatedAt:    time.Now().Unix(),
	}

	ctx := r.Context()
	if err := a.cipStore.SubmitCIP(ctx, storeItem); err != nil {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = cipUploadTpl.Execute(w, cipUploadData{Error: "保存提交记录失败: " + err.Error()})
		return
	}

	_ = header

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = cipUploadTpl.Execute(w, cipUploadData{
		Success:    true,
		AppID:      manifest.ID,
		AppName:    manifest.Name,
		AppVersion: manifest.Version,
		Author:     author,
	})
}

// handleCipStoreList 返回审核通过的小程序商店列表
func (a *API) handleCipStoreList(w http.ResponseWriter, r *http.Request) {
	if a.cipStore == nil {
		writeJSON(w, http.StatusOK, map[string]interface{}{"items": []interface{}{}})
		return
	}
	items, err := a.cipStore.ListApproved(r.Context(), 100, 0)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", "failed to list store apps")
		return
	}
	if items == nil {
		items = []data.CipStoreItem{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"items": items,
	})
}

// handleCipStoreDownload 提供小程序 .cip 包下载
func (a *API) handleCipStoreDownload(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(chi.URLParam(r, "id"))
	if id == "" || !validCipIDRegexp.MatchString(id) {
		http.NotFound(w, r)
		return
	}

	cipPath := filepath.Join(a.cfg.UploadDir, "cip", id+".cip")
	if info, err := os.Stat(cipPath); err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}

	if a.cipStore != nil {
		_ = a.cipStore.IncrementDownloadCount(r.Context(), id)
	}

	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s.cip"`, id))
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeFile(w, r, cipPath)
}

// handleAdminModerationCipReview 管理员审核 CIP 小程序
func (a *API) handleAdminModerationCipReview(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	_ = r.ParseMultipartForm(64 << 10)
	_ = r.ParseForm()
	id := strings.TrimSpace(r.FormValue("id"))
	action := strings.TrimSpace(r.FormValue("action"))
	reason := strings.TrimSpace(r.FormValue("reason"))

	if id == "" || (action != "approve" && action != "reject") {
		http.Error(w, "invalid id or action", http.StatusBadRequest)
		return
	}

	status := "approved"
	if action == "reject" {
		status = "rejected"
	}

	if a.cipStore != nil {
		_ = a.cipStore.ReviewSubmission(r.Context(), id, status, reason)
	}

	if action == "approve" {
		cipPath := filepath.Join(a.cfg.UploadDir, "cip", id+".cip")
		if cipBytes, err := os.ReadFile(cipPath); err == nil {
			_ = a.extractCipToLuaApps(id, cipBytes)
		}
	}

	a.logModeratorAction(r, "cip_review", "cip", id, "审核 CIP 小程序 #"+id+" 为 "+status+"，理由: "+reason)

	if r.Header.Get("X-Requested-With") == "XMLHttpRequest" || strings.Contains(r.Header.Get("Accept"), "application/json") {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"ok":      true,
			"message": "CIP 小程序审核已完成（" + status + "）",
		})
		return
	}
	http.Redirect(w, r, "/admins/moderation?section=cip", http.StatusSeeOther)
}

// extractCipToLuaApps 辅助方法：将通过审核的 CIP 解压到 lua_apps 生产包目录
func (a *API) extractCipToLuaApps(id string, archive []byte) error {
	zipReader, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
	if err != nil {
		return err
	}
	pkgDir := filepath.Join(a.luaAppDir, "packages", id)
	_ = os.MkdirAll(pkgDir, 0755)

	for _, f := range zipReader.File {
		if f.FileInfo().IsDir() {
			continue
		}
		clean := filepath.ToSlash(f.Name)
		if strings.HasPrefix(clean, "/") || strings.Contains(clean, "../") {
			continue
		}
		target := filepath.Join(pkgDir, clean)
		_ = os.MkdirAll(filepath.Dir(target), 0755)
		rc, err := f.Open()
		if err != nil {
			continue
		}
		dest, createErr := os.Create(target)
		if createErr == nil {
			_, _ = io.Copy(dest, rc)
			dest.Close()
		}
		rc.Close()
	}
	return nil
}
