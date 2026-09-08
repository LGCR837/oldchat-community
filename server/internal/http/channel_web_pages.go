package httpapi

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"html/template"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

// 简化后的频道管理/注册/机器人申请页面。
// 复用 channelWebSession（publisher token 登录）鉴权。

const channelRegisterPath = "/channel-manage/register"
const channelUpdatePath = "/channel-manage/update"
const channelApplyPath = "/channel-api/apply"

type channelRegisterPageData struct {
	CSRF    string
	Message string
	Error   string
}

// handleChannelWebRegister GET /channel-manage/register — 频道注册页。
func (a *API) handleChannelWebRegister(w http.ResponseWriter, r *http.Request) {
	if session, csrf, ok := a.channelWebSessionForRequest(r); ok {
		if !hasChannelScope(a.channelWebPermitScopes(r, session), "channel:create") {
			http.Error(w, "当前 Token 缺少 channel:create 权限", http.StatusForbidden)
			return
		}
		renderChannelRegisterPage(w, csrf, "", "")
		return
	}
	http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
}

// handleChannelWebRegisterPost POST /channel-manage/register — 创建频道。
func (a *API) handleChannelWebRegisterPost(w http.ResponseWriter, r *http.Request) {
	session, csrf, ok := a.channelWebSessionForRequest(r)
	if !ok {
		http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
		return
	}
	if !hasChannelScope(a.channelWebPermitScopes(r, session), "channel:create") {
		http.Error(w, "当前 Token 缺少 channel:create 权限", http.StatusForbidden)
		return
	}
	// 支持头像文件上传（multipart），普通表单同样兼容。
	r.Body = http.MaxBytesReader(w, r.Body, 8<<20)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		renderChannelRegisterPage(w, csrf, "", "提交内容过大或格式错误")
		return
	}
	if r.FormValue("csrf") != csrf {
		renderChannelRegisterPage(w, csrf, "", "CSRF 校验失败，请重试")
		return
	}
	name := strings.TrimSpace(r.FormValue("name"))
	handle := data.NormalizeChannelHandle(r.FormValue("handle"))
	description := strings.TrimSpace(r.FormValue("description"))
	avatarURL := strings.TrimSpace(r.FormValue("avatar_url"))
	visibility := strings.ToLower(strings.TrimSpace(r.FormValue("visibility")))
	if visibility != "public" && visibility != "private" {
		visibility = "private"
	}
	if name == "" || len([]rune(name)) > 64 {
		renderChannelRegisterPage(w, csrf, "", "频道名称必填，最多 64 字符")
		return
	}
	if visibility == "public" && !channelHandlePattern.MatchString(handle) {
		renderChannelRegisterPage(w, csrf, "", "公开频道 handle 需 5-32 位小写字母/数字/下划线")
		return
	}
	if handle != "" && !channelHandlePattern.MatchString(handle) {
		renderChannelRegisterPage(w, csrf, "", "handle 格式不正确（5-32 位小写字母/数字/下划线）")
		return
	}
	// 优先使用上传的头像文件，其次接受外部 URL。
	if r.MultipartForm != nil && len(r.MultipartForm.File["avatar_file"]) > 0 {
		ref, err := a.saveChannelAvatarFile(r)
		if err != nil {
			renderChannelRegisterPage(w, csrf, "", "头像只支持 2MB 以内的图片")
			return
		}
		avatarURL = ref
	} else if len(avatarURL) > 1024 {
		renderChannelRegisterPage(w, csrf, "", "头像 URL 过长（最多 1024 字符）")
		return
	}
	channelID, err := newPublicID("CH-", 10)
	if err != nil {
		renderChannelRegisterPage(w, csrf, "", "创建失败，请稍后再试")
		return
	}
	channel := &data.Channel{
		ID: channelID, OwnerID: session.UserID, Name: name, Handle: handle,
		AvatarURL: avatarURL, Description: description, Visibility: visibility,
	}
	if err = a.channels.Create(r.Context(), channel); err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			renderChannelRegisterPage(w, csrf, "", "handle 已被占用，请换一个")
			return
		}
		renderChannelRegisterPage(w, csrf, "", "创建失败："+err.Error())
		return
	}
	http.Redirect(w, r, "/channel-manage?channel_id="+channelID+"&message=created", http.StatusSeeOther)
}

// handleChannelWebUpdate POST /channel-manage/update — 更新名称/头像/描述。
func (a *API) handleChannelWebUpdate(w http.ResponseWriter, r *http.Request) {
	session, csrf, ok := a.channelWebSessionForRequest(r)
	if !ok {
		http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
		return
	}
	// 头像支持文件上传，使用 multipart 解析（普通表单同样兼容）。
	r.Body = http.MaxBytesReader(w, r.Body, 8<<20)
	if err := r.ParseMultipartForm(8 << 20); err != nil {
		http.Error(w, "提交内容过大或格式错误", http.StatusBadRequest)
		return
	}
	if r.FormValue("csrf") != csrf {
		http.Error(w, "CSRF", http.StatusForbidden)
		return
	}
	channelID := strings.TrimSpace(r.FormValue("channel_id"))
	var ownerID string
	if err := a.db.GetContext(r.Context(), &ownerID, `SELECT owner_id FROM channels WHERE id=$1`, channelID); err != nil || ownerID != session.UserID {
		http.Error(w, "只有频道所有者可修改", http.StatusForbidden)
		return
	}
	switch r.FormValue("action") {
	case "name":
		name := strings.TrimSpace(r.FormValue("name"))
		if name == "" || len([]rune(name)) > 64 {
			http.Error(w, "名称必填，最多 64 字符", http.StatusBadRequest)
			return
		}
		_ = a.channels.UpdateName(r.Context(), channelID, name)
	case "avatar":
		// 优先处理上传的头像文件（avatar_file），其次接受外部 URL。
		if r.MultipartForm != nil && len(r.MultipartForm.File["avatar_file"]) > 0 {
			ref, err := a.saveChannelAvatarFile(r)
			if err != nil {
				http.Error(w, "头像只支持 2MB 以内的图片", http.StatusBadRequest)
				return
			}
			_ = a.channels.UpdateAvatar(r.Context(), channelID, ref)
		} else {
			avatarURL := strings.TrimSpace(r.FormValue("avatar_url"))
			if avatarURL == "" || len(avatarURL) > 1024 {
				http.Error(w, "头像 URL 必填", http.StatusBadRequest)
				return
			}
			_ = a.channels.UpdateAvatar(r.Context(), channelID, avatarURL)
		}
	case "description":
		description := strings.TrimSpace(r.FormValue("description"))
		if len([]rune(description)) > 500 {
			http.Error(w, "描述最多 500 字符", http.StatusBadRequest)
			return
		}
		_ = a.channels.UpdateDescription(r.Context(), channelID, description)
	default:
		http.Error(w, "invalid action", http.StatusBadRequest)
		return
	}
	_, _ = a.db.ExecContext(r.Context(), `UPDATE channel_sequences SET settings_version=settings_version+1 WHERE channel_id=$1`, channelID)
	http.Redirect(w, r, "/channel-manage?channel_id="+channelID+"&message=updated", http.StatusSeeOther)
}

// saveChannelAvatarFile 保存频道头像上传文件（仅图片，2MB 上限），返回私有媒体引用。
func (a *API) saveChannelAvatarFile(r *http.Request) (string, error) {
	const maxAvatarBytes = 2 << 20
	file, header, err := r.FormFile("avatar_file")
	if err != nil {
		return "", err
	}
	defer file.Close()
	contentType := strings.ToLower(strings.TrimSpace(header.Header.Get("Content-Type")))
	if contentType == "" {
		contentType = mime.TypeByExtension(filepath.Ext(header.Filename))
	}
	if !strings.HasPrefix(contentType, "image/") {
		return "", errUnsupportedMediaType
	}
	ext, err := mediaExtFromType(contentType)
	if err != nil {
		return "", err
	}
	if err = os.MkdirAll(a.channelMediaDir(), 0o750); err != nil {
		return "", err
	}
	filename := "ca-" + nanoid.New() + ext
	path := filepath.Join(a.channelMediaDir(), filename)
	dst, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		return "", err
	}
	written, copyErr := io.Copy(dst, io.LimitReader(file, maxAvatarBytes+1))
	closeErr := dst.Close()
	if copyErr != nil || closeErr != nil || written <= 0 || written > maxAvatarBytes {
		_ = os.Remove(path)
		if copyErr != nil {
			return "", copyErr
		}
		return "", errMediaTooLarge
	}
	return channelMediaRefPrefix + filename, nil
}

// ---------- 频道 Publisher Token 申请页 ----------

type channelApplyPageData struct {
	LoggedIn     bool
	UID          string
	HasPermit    bool
	CanApply     bool
	PermitID     string
	Status       string
	Reason       string
	Scopes       string
	ReviewReason string
	TokenIssued  bool
	ExpiresAt    *time.Time
	Token        string
	Error        string
}

func (a *API) renderChannelAPIApplyPage(w http.ResponseWriter, r *http.Request, rawToken, errorMsg string) {
	data := channelApplyPageData{Token: rawToken, Error: errorMsg}
	user, ok := a.shopUserFromContext(r)
	if ok && user != nil {
		data.LoggedIn, data.UID, data.CanApply = true, user.UID, true
		row := struct {
			ID           string     `db:"id"`
			Status       string     `db:"status"`
			Reason       string     `db:"reason"`
			Scopes       string     `db:"scopes"`
			ReviewReason string     `db:"review_reason"`
			TokenHash    string     `db:"token_hash"`
			ExpiresAt    *time.Time `db:"expires_at"`
		}{}
		if err := a.db.GetContext(r.Context(), &row, `SELECT id,status,reason,scopes,review_reason,token_hash,expires_at FROM channel_api_permits WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1`, user.ID); err == nil {
			data.HasPermit = true
			data.PermitID, data.Status, data.Reason, data.Scopes = row.ID, row.Status, row.Reason, row.Scopes
			data.ReviewReason, data.TokenIssued, data.ExpiresAt = row.ReviewReason, row.TokenHash != "", row.ExpiresAt
			data.CanApply = row.Status == "rejected" || row.Status == "revoked"
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = channelApplyPageTemplate.Execute(w, data)
}

func (a *API) handleChannelAPIApplyPage(w http.ResponseWriter, r *http.Request) {
	a.renderChannelAPIApplyPage(w, r, "", "")
}

func (a *API) handleChannelAPIApplyPost(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	user, ok := a.shopUserFromContext(r)
	if !ok || user == nil {
		a.renderChannelAPIApplyPage(w, r, "", "请先登录后申请")
		return
	}
	if err := r.ParseForm(); err != nil {
		a.renderChannelAPIApplyPage(w, r, "", "提交内容无效")
		return
	}
	reason := strings.TrimSpace(r.FormValue("reason"))
	if reason == "" || len([]rune(reason)) > 200 {
		a.renderChannelAPIApplyPage(w, r, "", "用途说明必填且最多 200 字")
		return
	}
	allowed := map[string]bool{"channel:create": true, "channel:post": true, "channel:edit": true, "channel:delete": true, "channel:manage": true}
	scopes := make([]string, 0, 5)
	seen := make(map[string]bool)
	for _, scope := range r.Form["scopes"] {
		scope = strings.TrimSpace(scope)
		if allowed[scope] && !seen[scope] {
			seen[scope] = true
			scopes = append(scopes, scope)
		}
	}
	if len(scopes) == 0 {
		a.renderChannelAPIApplyPage(w, r, "", "请至少选择一项权限")
		return
	}
	contactEmail := strings.TrimSpace(r.FormValue("contact_email"))
	if !isValidContactEmail(contactEmail) {
		a.renderChannelAPIApplyPage(w, r, "", "请填写有效的联系邮箱")
		return
	}
	var active int
	_ = a.db.GetContext(r.Context(), &active, `SELECT COUNT(1) FROM channel_api_permits WHERE user_id=$1 AND status IN ('applied','approved')`, user.ID)
	if active > 0 {
		a.renderChannelAPIApplyPage(w, r, "", "已有待审核或已通过的申请")
		return
	}
	_, err := a.db.ExecContext(r.Context(), `INSERT INTO channel_api_permits(id,user_id,status,scopes,reason,contact_email) VALUES($1,$2,'applied',$3,$4,$5)`, nanoid.New(), user.ID, strings.Join(scopes, ","), reason, contactEmail)
	if err != nil {
		a.renderChannelAPIApplyPage(w, r, "", "申请提交失败，请稍后再试")
		return
	}
	http.Redirect(w, r, channelApplyPath, http.StatusSeeOther)
}

func (a *API) handleChannelAPITokenClaim(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	user, ok := a.shopUserFromContext(r)
	if !ok || user == nil {
		a.renderChannelAPIApplyPage(w, r, "", "请先登录后领取")
		return
	}
	_ = r.ParseForm()
	permitID := strings.TrimSpace(r.FormValue("id"))
	buf := make([]byte, 32)
	if permitID == "" {
		a.renderChannelAPIApplyPage(w, r, "", "申请记录无效")
		return
	}
	if _, err := rand.Read(buf); err != nil {
		a.renderChannelAPIApplyPage(w, r, "", "Token 生成失败")
		return
	}
	rawToken := hex.EncodeToString(buf)
	sum := sha256.Sum256([]byte(rawToken))
	expiresAt := time.Now().Add(channelTokenTTL)
	result, err := a.db.ExecContext(r.Context(), `UPDATE channel_api_permits SET token_hash=$1,expires_at=$2,token_delivered_at=CURRENT_TIMESTAMP WHERE id=$3 AND user_id=$4 AND status='approved' AND token_hash=''`, hex.EncodeToString(sum[:]), expiresAt, permitID, user.ID)
	affected := int64(0)
	if err == nil {
		affected, _ = result.RowsAffected()
	}
	if err != nil || affected != 1 {
		a.renderChannelAPIApplyPage(w, r, "", "Token 已领取、申请未通过或不属于当前账号")
		return
	}
	a.renderChannelAPIApplyPage(w, r, rawToken, "")
}

// ---------- 模板 ----------

func renderChannelRegisterPage(w http.ResponseWriter, csrf, message, errorMsg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = channelRegisterPageTemplate.Execute(w, channelRegisterPageData{CSRF: csrf, Message: message, Error: errorMsg})
}

const channelPageBaseCSS = `
*{box-sizing:border-box}body{margin:0;background:#f1f5f9;color:#0f172a;font:14px -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
a{color:#168acd;text-decoration:none}.wrap{max-width:680px;margin:40px auto;padding:0 16px}
.card{background:#fff;padding:24px;border-radius:14px;box-shadow:0 6px 24px rgba(15,23,42,.08)}
h1{font-size:20px;margin:0 0 4px}.sub{color:#64748b;margin-bottom:20px;font-size:13px}
label{display:block;font-size:12px;color:#475569;font-weight:600;margin:14px 0 6px}
input[type=text],input[type=url],input[type=password],select,textarea{width:100%;padding:10px 12px;border:1px solid #cbd5e1;border-radius:9px;font-size:14px;background:#fff}
textarea{resize:vertical}input:focus,select:focus,textarea:focus{outline:none;border-color:#168acd;box-shadow:0 0 0 3px rgba(22,138,205,.15)}
.btn{display:inline-block;border:0;background:#168acd;color:#fff;padding:11px 22px;border-radius:9px;font-weight:700;cursor:pointer;font-size:14px}
.btn.secondary{background:#e2e8f0;color:#334155}.btn.danger{background:#ef4444}
.msg{background:#ecfdf5;color:#065f46;padding:10px 14px;border-radius:9px;margin:14px 0}.err{background:#fef2f2;color:#b91c1c;padding:10px 14px;border-radius:9px;margin:14px 0}
.row{display:flex;gap:10px;margin-top:20px;align-items:center}
.check{display:flex;align-items:center;gap:8px;padding:9px 12px;border:1px solid #e2e8f0;border-radius:9px;margin:6px 0;cursor:pointer}
.check input{width:auto}
.hint{color:#94a3b8;font-size:12px;margin-top:4px}
table{width:100%;border-collapse:collapse;font-size:13px;margin:10px 0}
th,td{border-bottom:1px solid #e2e8f0;padding:8px 10px;text-align:left;vertical-align:top}
th{background:#f8fafc;font-size:12px;color:#475569}
code{background:#f1f5f9;padding:2px 6px;border-radius:5px;font-size:12px;color:#0f172a}
.topbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
.topbar .btn{padding:8px 16px;font-size:13px}
`

var channelRegisterPageTemplate = template.Must(template.New("channel-register").Parse(
	`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>创建频道 - OldChat</title><style>` + channelManageCSS + `</style></head><body><main class="page narrow">
<nav><a href="/">HOME</a> / <a href="/channel-manage">CHANNEL CONSOLE</a> / <a href="/channel-api.md">DEVELOPER DOC</a></nav>
<h1>创建频道</h1><p>公开频道可通过唯一 handle 被用户搜索；私有频道仅对受邀用户可见。</p>
{{if .Message}}<div class="success">{{.Message}}</div>{{end}}{{if .Error}}<div class="panel error">{{.Error}}</div>{{end}}
<form class="panel strong" method="post" action="/channel-manage/register" enctype="multipart/form-data"><input type="hidden" name="csrf" value="{{.CSRF}}"><label>频道名称 *</label><input type="text" name="name" maxlength="64" placeholder="例如：OldChat 更新频道" required><label>可见性</label><select name="visibility"><option value="private">私有</option><option value="public">公开</option></select><label>Handle（公开频道必填）</label><input type="text" name="handle" pattern="[a-z0-9_]{5,32}" placeholder="5-32 位小写字母、数字或下划线"><label>频道头像（可选）</label><input type="file" name="avatar_file" accept="image/*"><div class="muted">或填写头像 URL（二选一，文件优先）</div><input type="url" name="avatar_url" placeholder="https://...（可选）"><label>频道描述（可选）</label><textarea name="description" rows="4" maxlength="500"></textarea><div class="actions"><button type="submit">创建频道</button><a class="button secondary" href="/channel-manage">取消</a></div></form>
</main></body></html>`))

var channelApplyPageTemplate = template.Must(template.New("channel-apply").Parse(
	`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>频道 Publisher Token - OldChat</title><style>
*{box-sizing:border-box;border-radius:0!important}
body{max-width:720px;margin:0 auto;padding:24px;font-family:-apple-system,"Noto Sans SC",sans-serif;line-height:1.7;color:#111;background:#fff}
h1{font-size:28px;margin:4px 0}
a{color:#0366d6;text-decoration:none}a:hover{text-decoration:underline}
label{display:block;font-size:13px;font-weight:700;margin:12px 0 4px}
input,textarea,select{width:100%;padding:10px;border:1px solid #999;border-radius:0;font-size:14px;box-sizing:border-box;margin:2px 0 10px;background:#fff}
button{background:#111;color:#fff;border:2px solid #111;padding:10px 20px;font-weight:700;cursor:pointer;font-size:14px}
.btn{display:inline-block;padding:10px 20px;border:2px solid #111;font-weight:700;margin:4px 4px 4px 0;color:#111;text-decoration:none;background:#fff}
.btn.primary{background:#111;color:#fff}
.card{border:1px solid #ccc;padding:16px;margin:12px 0}
.msg,.ok{color:#0a7a0a}.err{color:#c00}
.hint{color:#666;font-size:13px;margin-top:2px}
.check{display:flex;align-items:center;gap:8px;font-weight:400;font-size:14px}
.check input{width:auto;margin:0}
code{display:block;background:#f3f3f3;padding:12px;word-break:break-all;user-select:all;margin-top:8px}
.row{display:flex;gap:10px;margin-top:16px;align-items:center}
</style></head><body>
<p><a href="/">HOME</a> / <a href="/channel-api.md">DEVELOPER DOC</a> / <a href="/bot-apply">BOT TOKEN</a> / <a href="/channel-manage">CHANNEL MANAGE</a></p>
<h1>频道 Publisher Token</h1>
<p>通过 Channel API 创建频道、自动发布及管理内容。Token 有效期 365 天，只在领取时展示一次。</p>
{{if .Error}}<div class="card err">{{.Error}}</div>{{end}}
{{if .Token}}<div class="card ok"><b>Token 领取成功，请立即复制保存：</b><code>{{.Token}}</code><div class="hint">关闭或刷新本页后无法再次查看。请勿提交到公开仓库。</div></div>{{end}}
{{if .LoggedIn}}<div class="card"><b>当前账号：</b>{{.UID}}{{if .HasPermit}}<br><b>申请状态：</b>{{if eq .Status "applied"}}审核中{{else if eq .Status "approved"}}<span class="ok">已通过</span>{{else if eq .Status "rejected"}}<span class="err">已拒绝</span>{{else}}已撤销{{end}}<br><b>用途：</b>{{.Reason}}<br><b>权限：</b>{{.Scopes}}{{if .ReviewReason}}<br><b>审核意见：</b>{{.ReviewReason}}{{end}}{{end}}</div>
{{if and .HasPermit (eq .Status "approved")}}{{if .TokenIssued}}<div class="card ok">Token 已领取{{if .ExpiresAt}}，有效期至 {{.ExpiresAt.Format "2006-01-02 15:04"}}{{end}}。</div>{{else}}<form class="card" method="post" action="/channel-api/apply/token"><input type="hidden" name="id" value="{{.PermitID}}"><b>审核已通过</b><p class="hint">点击后生成 Token。页面只展示一次。</p><button type="submit">领取 Publisher Token</button></form>{{end}}{{end}}
{{if .CanApply}}<form class="card" method="post" action="/channel-api/apply"><label>申请理由 *（最多 200 字）</label><textarea name="reason" rows="4" maxlength="200" required placeholder="例如：为频道提供自动发布服务"></textarea><label>联系邮箱 *（审核结果会发送到此邮箱）</label><input type="email" name="contact_email" maxlength="120" required placeholder="you@example.com"><label>权限范围</label>
<label class="check"><input type="checkbox" name="scopes" value="channel:create"> channel:create <span class="hint">创建频道</span></label><label class="check"><input type="checkbox" name="scopes" value="channel:post" checked> channel:post <span class="hint">发布帖子</span></label><label class="check"><input type="checkbox" name="scopes" value="channel:edit"> channel:edit <span class="hint">编辑帖子</span></label><label class="check"><input type="checkbox" name="scopes" value="channel:delete"> channel:delete <span class="hint">删除帖子</span></label><label class="check"><input type="checkbox" name="scopes" value="channel:manage" checked> channel:manage <span class="hint">登录网页频道管理后台</span></label><div class="row"><button type="submit">提交审核</button></div></form>{{end}}
{{else}}<div class="card"><b>请先登录官网账号</b><form method="post" action="/shop/login"><input type="hidden" name="next" value="/channel-api/apply"><label>账号 / 邮箱</label><input type="text" name="identifier" required><label>密码</label><input type="password" name="password" required><button type="submit">登录并申请</button></form></div>{{end}}
</body></html>`))
