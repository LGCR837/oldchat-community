package httpapi

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"html/template"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
	"metrochat/internal/mail"
)

// ---------- 常量 ----------
const botTokenTTL = 30 * 24 * time.Hour // token 有效期 30 天

// isValidContactEmail 校验申请时填写的联系邮箱格式。
func isValidContactEmail(email string) bool {
	email = strings.TrimSpace(email)
	if len(email) < 5 || len(email) > 120 {
		return false
	}
	at := strings.LastIndex(email, "@")
	if at <= 0 || at == len(email)-1 {
		return false
	}
	local := email[:at]
	domain := email[at+1:]
	if strings.ContainsAny(local, " \t\r\n<>\"") || strings.ContainsAny(domain, " \t\r\n<>\"") {
		return false
	}
	if !strings.Contains(domain, ".") {
		return false
	}
	return true
}

// notifyPermitReviewEmail 审核通过/拒绝后，向申请人填写的邮箱发送通知。
// 只发送站内通知文本；Token 仍在官网页面领取，邮件不包含 Token 明文。
func (a *API) notifyPermitReviewEmail(ctx context.Context, table, id, action, reviewReason, claimURL string) {
	if a == nil || (table != "bot_permits" && table != "channel_api_permits") {
		return
	}
	action = strings.TrimSpace(action)
	if action != "approve" && action != "reject" {
		return
	}
	var email string
	err := a.db.GetContext(ctx, &email, `SELECT contact_email FROM `+table+` WHERE id=$1`, strings.TrimSpace(id))
	if err != nil || strings.TrimSpace(email) == "" {
		return
	}
	status := "已通过"
	body := "您的申请已通过审核，请登录官网前往以下页面领取 Token（只显示一次）。\n\n领取地址：" + claimURL
	if action == "reject" {
		status = "未通过"
		body = "很遗憾，您的申请未通过审核。"
		if strings.TrimSpace(reviewReason) != "" {
			body += "\n审核意见：" + strings.TrimSpace(reviewReason)
		}
		body += "\n如有疑问可登录官网重新提交申请。"
	}
	title := "机器人 Token 申请"
	if table == "channel_api_permits" {
		title = "频道 Publisher Token 申请"
	}
	go func() {
		if err := mail.SendMessage(email, title+"审核"+status, body); err != nil {
			log.Printf("permit review email failed table=%s id=%s err=%v", table, id, err)
		}
	}()
}

const maxBotButtons = 16 // 单消息按钮上限
const maxBotButtonText = 32

// ---------- 官网机器人 Token 申请页 ----------
const botApplyHTML = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>机器人 Token - OldChat</title><style>
*{box-sizing:border-box;border-radius:0!important}body{max-width:760px;margin:0 auto;padding:32px 20px 80px;font-family:-apple-system,"Noto Sans SC",sans-serif;line-height:1.7;color:#111}a{color:#111;font-weight:700}h1{font-size:30px}.card{border:2px solid #111;padding:20px;margin:16px 0}.ok{color:#08752d}.err{color:#c5162e}.hint{font-size:13px;color:#666}textarea,select,input{width:100%;padding:10px;border:2px solid #111;margin:5px 0 14px;font:14px inherit}button,.btn{display:inline-block;padding:10px 18px;background:#111;color:#fff;border:2px solid #111;font-weight:800;cursor:pointer;text-decoration:none}code{display:block;background:#f2f2f2;padding:12px;word-break:break-all;user-select:all}
@media (prefers-color-scheme: dark){body{color:#e6e8eb;background:#14161a;color-scheme:dark}a{color:#7fb2e0}.card{border-color:#2f353d}.ok{color:#4ac26b}.err{color:#ef6b73}.hint{color:#8b94a0}textarea,select,input{background:#20242a;border-color:#2f353d;color:#e6e8eb}button,.btn{background:#2e6591;border-color:#3a78a8;color:#fff}code{background:#20242a;color:#c9cdd4}}</style></head><body>
<p><a href="/">OLDCHAT/</a> · <a href="/bot-api.md">开发手册</a> · <a href="/channel-api/apply">频道 Token</a></p>
<h1>机器人 Message Token</h1><p>以你的账号向群聊或私聊发送带按钮的消息。Token 有效期 30 天，只在领取时显示一次。</p>
{{if .Error}}<div class="card err">{{.Error}}</div>{{end}}{{if .Token}}<div class="card ok"><b>Token 领取成功，请立即复制保存：</b><code>{{.Token}}</code><div class="hint">关闭或刷新本页后无法再次查看。请勿提交到公开仓库。</div></div>{{end}}
{{if .LoggedIn}}<div class="card"><b>当前账号：</b>{{.UID}}{{if .HasPermit}}<br><b>申请状态：</b>{{if eq .Status "applied"}}审核中{{else if eq .Status "approved"}}<span class="ok">已通过</span>{{else if eq .Status "rejected"}}<span class="err">已拒绝</span>{{else}}已撤销{{end}}<br><b>用途：</b>{{.Reason}}<br><b>权限：</b>{{.Scopes}}{{if .ReviewReason}}<br><b>审核意见：</b>{{.ReviewReason}}{{end}}{{end}}</div>
{{if and .HasPermit (eq .Status "approved")}}{{if .TokenIssued}}<div class="card ok">Token 已领取{{if .ExpiresAt}}，有效期至 {{.ExpiresAt.Format "2006-01-02 15:04"}}{{end}}。</div>{{else}}<form class="card" method="post" action="/bot-apply/token"><input type="hidden" name="id" value="{{.PermitID}}"><b>审核已通过</b><p class="hint">点击后生成 Token。页面只展示一次。</p><button type="submit">领取 Message Token</button></form>{{end}}{{end}}
{{if .CanApply}}<form class="card" method="post" action="/bot-apply"><label>用途说明（必填，最多 200 字）</label><textarea name="reason" rows="4" maxlength="200" required placeholder="例如：群欢迎机器人、投票或工单菜单"></textarea><label>目标场景</label><select name="scopes"><option value="group,direct">群聊 + 私聊</option><option value="group">仅群聊</option><option value="direct">仅私聊</option></select><label>联系邮箱（必填，审核结果会发送到此邮箱）</label><input type="email" name="contact_email" maxlength="120" required placeholder="you@example.com"><button type="submit">提交审核</button></form>{{end}}
{{else}}<div class="card"><b>请先登录官网账号</b><form method="post" action="/shop/login"><input type="hidden" name="next" value="/bot-apply"><input name="identifier" placeholder="账号 / 邮箱" required><input name="password" type="password" placeholder="密码" required><button type="submit">登录并申请</button></form></div>{{end}}
</body></html>`

var botApplyTpl = template.Must(template.New("bot_apply").Parse(botApplyHTML))

type botApplyPageData struct {
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

func (a *API) renderBotApplyPage(w http.ResponseWriter, r *http.Request, rawToken, errorMsg string) {
	data := botApplyPageData{Token: rawToken, Error: errorMsg}
	user, ok := a.shopUserFromContext(r)
	if ok && user != nil {
		data.LoggedIn, data.UID, data.CanApply = true, user.UID, true
		if permit, err := a.loadLatestBotPermit(r.Context(), user.ID); err == nil && permit != nil {
			data.HasPermit = true
			data.PermitID, data.Status, data.Reason, data.Scopes = permit.ID, permit.Status, permit.Reason, permit.Scopes
			data.ReviewReason, data.TokenIssued, data.ExpiresAt = permit.ReviewReason, permit.TokenHash != "", permit.ExpiresAt
			data.CanApply = permit.Status == "rejected" || permit.Status == "revoked"
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = botApplyTpl.Execute(w, data)
}

func (a *API) handleBotApplyPage(w http.ResponseWriter, r *http.Request) {
	a.renderBotApplyPage(w, r, "", "")
}

func (a *API) handleBotApplySubmit(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
	user, ok := a.shopUserFromContext(r)
	if !ok || user == nil {
		a.renderBotApplyPage(w, r, "", "请先登录后申请")
		return
	}
	if err := r.ParseForm(); err != nil {
		a.renderBotApplyPage(w, r, "", "提交内容无效")
		return
	}
	reason := strings.TrimSpace(r.FormValue("reason"))
	if reason == "" || len([]rune(reason)) > 200 {
		a.renderBotApplyPage(w, r, "", "用途说明必填且最多 200 字")
		return
	}
	scopes := strings.TrimSpace(r.FormValue("scopes"))
	if scopes != "group" && scopes != "direct" && scopes != "group,direct" {
		a.renderBotApplyPage(w, r, "", "权限范围无效")
		return
	}
	contactEmail := strings.TrimSpace(r.FormValue("contact_email"))
	if !isValidContactEmail(contactEmail) {
		a.renderBotApplyPage(w, r, "", "请填写有效的联系邮箱")
		return
	}
	if existing, err := a.loadLatestBotPermit(r.Context(), user.ID); err == nil && existing != nil && (existing.Status == "applied" || existing.Status == "approved") {
		a.renderBotApplyPage(w, r, "", "已有待审核或已通过的申请")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	_, err := a.db.ExecContext(ctx, `INSERT INTO bot_permits (id,user_id,token_hash,status,scopes,reason,contact_email) VALUES ($1,$2,'','applied',$3,$4,$5)`, nanoid.New(), user.ID, scopes, reason, contactEmail)
	if err != nil {
		log.Printf("bot apply insert failed: %v", err)
		a.renderBotApplyPage(w, r, "", "申请提交失败，请稍后再试")
		return
	}
	http.Redirect(w, r, "/bot-apply", http.StatusSeeOther)
}

func (a *API) handleBotTokenClaim(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 4<<10)
	user, ok := a.shopUserFromContext(r)
	if !ok || user == nil {
		a.renderBotApplyPage(w, r, "", "请先登录后领取")
		return
	}
	_ = r.ParseForm()
	permitID := strings.TrimSpace(r.FormValue("id"))
	if permitID == "" {
		a.renderBotApplyPage(w, r, "", "申请记录无效")
		return
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		a.renderBotApplyPage(w, r, "", "Token 生成失败")
		return
	}
	rawToken := hex.EncodeToString(buf)
	sum := sha256.Sum256([]byte(rawToken))
	expiresAt := time.Now().Add(botTokenTTL)
	result, err := a.db.ExecContext(r.Context(), `UPDATE bot_permits SET token_hash=$1,expires_at=$2,token_delivered_at=CURRENT_TIMESTAMP WHERE id=$3 AND user_id=$4 AND status='approved' AND token_hash=''`, hex.EncodeToString(sum[:]), expiresAt, permitID, user.ID)
	affected := int64(0)
	if err == nil {
		affected, _ = result.RowsAffected()
	}
	if err != nil || affected != 1 {
		a.renderBotApplyPage(w, r, "", "Token 已领取、申请未通过或不属于当前账号")
		return
	}
	a.renderBotApplyPage(w, r, rawToken, "")
}

// ---------- 管理后台审核 ----------
func (a *API) handleBotAdminList(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	rows := []struct {
		ID       string    `db:"id"`
		UID      string    `db:"uid"`
		Username string    `db:"username"`
		Status   string    `db:"status"`
		Scopes   string    `db:"scopes"`
		Reason   string    `db:"reason"`
		Created  time.Time `db:"created_at"`
	}{}
	err := a.db.SelectContext(ctx, &rows, `
SELECT p.id, u.uid, u.username, p.status, p.scopes, p.reason, p.created_at
FROM bot_permits p JOIN users u ON u.id = p.user_id
WHERE p.status != 'revoked' ORDER BY p.created_at DESC LIMIT 100`)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{"permits": rows})
}

func (a *API) handleBotAdminReview(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var req struct {
		ID           string `json:"id"`
		Action       string `json:"action"`
		ReviewReason string `json:"review_reason"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	updated, err := a.reviewBotPermit(r.Context(), req.ID, req.Action, req.ReviewReason)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "review_failed", "review failed")
		return
	}
	if !updated {
		writeError(w, http.StatusConflict, "not_pending", "application is not pending")
		return
	}
	a.notifyPermitReviewEmail(r.Context(), "bot_permits", req.ID, req.Action, req.ReviewReason, a.publicURL("/bot-apply"))
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]bool{"ok": true})
}

func (a *API) reviewBotPermit(ctx context.Context, id, action, reviewReason string) (bool, error) {
	action = strings.ToLower(strings.TrimSpace(action))
	if action != "approve" && action != "reject" {
		return false, nil
	}
	status := "approved"
	if action == "reject" {
		status = "rejected"
	}
	result, err := a.db.ExecContext(ctx, `UPDATE bot_permits SET status=$1,review_reason=$2,reviewed_at=CURRENT_TIMESTAMP WHERE id=$3 AND status='applied'`, status, strings.TrimSpace(reviewReason), strings.TrimSpace(id))
	if err != nil {
		return false, err
	}
	affected, _ := result.RowsAffected()
	return affected == 1, nil
}

// ---------- 发送带按钮消息 ----------
type botButtonReq struct {
	Text   string `json:"text"`
	Action string `json:"action"` // send_text / open_url / send_image / reply_msg / form
	Data   string `json:"data"`   // 预设文本 / url / 图片url / 表单定义json
}

type botSendRequest struct {
	ToType  string         `json:"to_type"` // group / direct
	ToID    string         `json:"to_id"`   // group_id 或 uid/ncuid
	Text    string         `json:"text"`
	Buttons []botButtonReq `json:"buttons"`
}

type botButtonOut struct {
	Text      string `json:"text"`
	Action    string `json:"action"`
	Data      string `json:"data"`
	Index     int    `json:"index"`
	ExpiresAt int64  `json:"expires_at,omitempty"`
	TID       string `json:"tid"`
	Nonce     string `json:"nonce"`
}

func (a *API) handleBotSend(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	// 认证：Authorization: Bearer <message-token>
	authHeader := strings.TrimSpace(r.Header.Get("Authorization"))
	token := ""
	if strings.HasPrefix(authHeader, "Bearer ") {
		token = strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
	}
	if token == "" {
		writeError(w, http.StatusUnauthorized, "missing_token", "missing message token")
		return
	}
	permit, err := a.authenticateBotToken(r.Context(), token)
	if err != nil || permit == nil {
		writeError(w, http.StatusUnauthorized, "invalid_token", "invalid or expired message token")
		return
	}

	var req botSendRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	req.ToType = strings.ToLower(strings.TrimSpace(req.ToType))
	req.ToID = strings.ToUpper(strings.TrimSpace(req.ToID))
	req.Text = strings.TrimSpace(req.Text)
	if req.ToType != "group" && req.ToType != "direct" {
		writeError(w, http.StatusBadRequest, "invalid_to_type", "to_type must be group or direct")
		return
	}
	if len(req.Buttons) == 0 || len(req.Buttons) > maxBotButtons {
		writeError(w, http.StatusBadRequest, "invalid_buttons", "buttons 1-16")
		return
	}
	// scope 校验
	scopes := strings.Split(permit.Scopes, ",")
	hasScope := false
	for _, s := range scopes {
		if strings.TrimSpace(s) == req.ToType {
			hasScope = true
			break
		}
	}
	if !hasScope {
		writeError(w, http.StatusForbidden, "scope_denied", "token has no scope: "+req.ToType)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	// 校验目标与权限
	if req.ToType == "group" {
		if !isValidGroupID(req.ToID) {
			writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
			return
		}
		if _, err := a.groups.GetRole(ctx, req.ToID, permit.UserID); err != nil {
			writeError(w, http.StatusForbidden, "not_member", "sender is not a member of the group")
			return
		}
	} else {
		if !isValidUID(req.ToID) && !strings.HasPrefix(req.ToID, "nc_") {
			writeError(w, http.StatusBadRequest, "invalid_to_id", "invalid direct target")
			return
		}
	}

	// 构造 buttons（生成 tid，payload = msgID|index|action|nonce）
	msgID := nanoid.New()
	nonces := make([]string, len(req.Buttons))
	outButtons := make([]botButtonOut, 0, len(req.Buttons))
	for i, btn := range req.Buttons {
		action := strings.ToLower(strings.TrimSpace(btn.Action))
		if action == "" {
			action = "send_text"
		}
		text := strings.TrimSpace(btn.Text)
		if text == "" || len([]rune(text)) > maxBotButtonText {
			writeError(w, http.StatusBadRequest, "invalid_button", "button text required or too long")
			return
		}
		switch action {
		case "send_text", "reply_msg":
			if strings.TrimSpace(btn.Data) == "" {
				writeError(w, http.StatusBadRequest, "invalid_button", "send_text/reply_msg requires data")
				return
			}
		case "open_url":
			if !strings.HasPrefix(strings.ToLower(btn.Data), "http") {
				writeError(w, http.StatusBadRequest, "invalid_button", "open_url requires http(s) url")
				return
			}
		case "send_image":
			if !strings.HasPrefix(strings.ToLower(btn.Data), "http") {
				writeError(w, http.StatusBadRequest, "invalid_button", "send_image requires image url")
				return
			}
		case "form":
			if !isValidFormJSON(btn.Data) {
				writeError(w, http.StatusBadRequest, "invalid_button", "form requires valid form json")
				return
			}
		default:
			writeError(w, http.StatusBadRequest, "invalid_button", "unsupported action: "+action)
			return
		}
		nonce := nanoid.New()
		nonces[i] = nonce
		tid := ""
		if a.buttonSigner != nil {
			tid = a.buttonSigner.SignTID(msgID, i, action, nonce)
		}
		expiresAt := int64(0)
		if action == "form" {
			expiresAt = time.Now().Add(10 * time.Minute).Unix()
		}
		outButtons = append(outButtons, botButtonOut{
			Text: text, Action: action, Data: btn.Data, Index: i,
			ExpiresAt: expiresAt, TID: tid, Nonce: nonce,
		})
	}

	// 构造 payload v3
	payload := map[string]interface{}{
		"v":       3,
		"text":    req.Text,
		"buttons": outButtons,
	}
	bodyJSON, _ := json.Marshal(payload)

	// 复用正常发送逻辑
	if req.ToType == "group" {
		err = a.storeBotGroupMessage(ctx, permit.UserID, req.ToID, string(bodyJSON), msgID)
	} else {
		err = a.storeBotDirectMessage(ctx, permit.UserID, req.ToID, string(bodyJSON), msgID)
	}
	if err != nil {
		log.Printf("bot send store failed: %v", err)
		writeError(w, http.StatusInternalServerError, "send_failed", "send failed")
		return
	}
	// 更新 last_used
	_, _ = a.db.ExecContext(ctx, `UPDATE bot_permits SET last_used_at=CURRENT_TIMESTAMP WHERE id=$1`, permit.ID)

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"ok":      true,
		"msg_id":  msgID,
		"buttons": outButtons,
	})
}

// ---------- 机器人轮询按钮回调 ----------
type botResponseCursor struct {
	ID int64 `json:"id"`
}

type botResponseDBRow struct {
	ResponseID int64     `db:"response_id"`
	TID        string    `db:"tid"`
	MsgID      string    `db:"msg_id"`
	BtnIndex   int       `db:"btn_index"`
	Action     string    `db:"action"`
	FormData   string    `db:"form_data"`
	UserUID    string    `db:"user_uid"`
	UserNcuid  string    `db:"user_ncuid"`
	ToType     string    `db:"to_type"`
	ToID       string    `db:"to_id"`
	CreatedAt  time.Time `db:"created_at"`
}

type botResponseOut struct {
	ResponseID int64       `json:"response_id"`
	TID        string      `json:"tid"`
	MsgID      string      `json:"msg_id"`
	BtnIndex   int         `json:"btn_index"`
	Action     string      `json:"action"`
	FormData   interface{} `json:"form_data,omitempty"`
	UserUID    string      `json:"user_uid"`
	UserNcuid  string      `json:"user_ncuid,omitempty"`
	ToType     string      `json:"to_type"`
	ToID       string      `json:"to_id"`
	CreatedAt  time.Time   `json:"created_at"`
}

func (a *API) handleBotResponses(w http.ResponseWriter, r *http.Request) {
	authHeader := strings.TrimSpace(r.Header.Get("Authorization"))
	if !strings.HasPrefix(authHeader, "Bearer ") {
		writeError(w, http.StatusUnauthorized, "missing_token", "missing message token")
		return
	}
	permit, err := a.authenticateBotToken(r.Context(), strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer ")))
	if err != nil || permit == nil {
		writeError(w, http.StatusUnauthorized, "invalid_token", "invalid or expired message token")
		return
	}
	limit := 50
	if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
		if parsed, parseErr := strconv.Atoi(raw); parseErr != nil || parsed < 1 || parsed > 100 {
			writeError(w, http.StatusBadRequest, "invalid_limit", "limit must be 1-100")
			return
		} else {
			limit = parsed
		}
	}
	cursorRaw := strings.TrimSpace(r.URL.Query().Get("cursor"))
	cursor, err := decodeBotResponseCursor(cursorRaw)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_cursor", "invalid cursor")
		return
	}
	rows := make([]botResponseDBRow, 0, limit+1)
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	_, _ = a.db.ExecContext(ctx, `DELETE FROM button_responses WHERE created_at < datetime('now','-10 minutes')`)
	err = a.db.SelectContext(ctx, &rows, `
SELECT c.id AS response_id, c.tid, c.msg_id, c.btn_index, c.action, c.form_data,
       u.uid AS user_uid, u.ncuid AS user_ncuid,
       CASE WHEN gm.id IS NOT NULL THEN 'group' ELSE 'direct' END AS to_type,
       CASE WHEN gm.id IS NOT NULL THEN gm.group_id ELSE u.uid END AS to_id,
       c.created_at
FROM button_responses c
JOIN users u ON u.id = c.user_id
LEFT JOIN group_messages gm ON gm.id = c.msg_id AND gm.sender_id = $1
LEFT JOIN direct_messages dm ON dm.id = c.msg_id AND dm.sender_id = $1
WHERE (gm.id IS NOT NULL OR dm.id IS NOT NULL)
  AND c.created_at >= datetime('now','-10 minutes')
  AND c.id > $2
ORDER BY c.id ASC
LIMIT $3`, permit.UserID, cursor.ID, limit+1)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "query_failed", "failed to query responses")
		return
	}
	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}
	responses := make([]botResponseOut, 0, len(rows))
	for _, row := range rows {
		var formData interface{}
		if strings.TrimSpace(row.FormData) != "" {
			if json.Unmarshal([]byte(row.FormData), &formData) != nil {
				formData = row.FormData
			}
		}
		responses = append(responses, botResponseOut{
			ResponseID: row.ResponseID, TID: row.TID, MsgID: row.MsgID,
			BtnIndex: row.BtnIndex, Action: row.Action,
			FormData: formData, UserUID: row.UserUID, UserNcuid: row.UserNcuid,
			ToType: row.ToType, ToID: row.ToID, CreatedAt: row.CreatedAt,
		})
	}
	nextCursor := cursorRaw
	if len(rows) > 0 {
		last := rows[len(rows)-1]
		nextCursor = encodeBotResponseCursor(botResponseCursor{ID: last.ResponseID})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"responses": responses, "next_cursor": nextCursor, "has_more": hasMore,
	})
}

func encodeBotResponseCursor(cursor botResponseCursor) string {
	raw, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(raw)
}

func decodeBotResponseCursor(raw string) (botResponseCursor, error) {
	if raw == "" {
		return botResponseCursor{}, nil
	}
	decoded, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return botResponseCursor{}, err
	}
	var cursor botResponseCursor
	if err := json.Unmarshal(decoded, &cursor); err != nil || cursor.ID <= 0 {
		return botResponseCursor{}, io.ErrUnexpectedEOF
	}
	return cursor, nil
}

// ---------- 回调 ----------
type botCallbackRequest struct {
	MsgID    string `json:"msg_id"`
	ToType   string `json:"to_type"`
	ToID     string `json:"to_id"`
	BtnIndex int    `json:"btn_index"`
	TID      string `json:"tid"`
	Nonce    string `json:"nonce"`
	Action   string `json:"action"`
	FormData string `json:"form_data"` // 表单提交内容（可选）
}

func (a *API) handleBotCallback(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	var req botCallbackRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	// 认证：登录用户
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	// 验签 tid
	if a.buttonSigner == nil {
		writeError(w, http.StatusInternalServerError, "signer_unavailable", "signer unavailable")
		return
	}
	verified := a.buttonSigner.VerifyTID(req.TID, req.MsgID, req.BtnIndex, req.Action, req.Nonce)
	if !verified {
		writeError(w, http.StatusForbidden, "invalid_tid", "invalid tid")
		return
	}
	// 表单从消息发出起仅允许填写 10 分钟。客户端会提前禁用，服务端仍强制校验。
	if req.Action == "form" {
		createdAt, createdErr := a.buttonMessageCreatedAt(ctx, req.MsgID)
		if createdErr != nil {
			writeError(w, http.StatusNotFound, "message_not_found", "button message not found")
			return
		}
		if time.Now().After(createdAt.Add(10 * time.Minute)) {
			writeError(w, http.StatusGone, "form_expired", "form expired")
			return
		}
	}
	// 防重放：tid 已用则拒绝
	var count int
	_ = a.db.GetContext(ctx, &count,
		`SELECT COUNT(1) FROM button_responses WHERE tid=$1 AND user_id=$2`, req.TID, claims.Subject)
	if count > 0 {
		writeError(w, http.StatusForbidden, "tid_used", "tid already used")
		return
	}
	_, _ = a.db.ExecContext(ctx, `DELETE FROM button_responses WHERE created_at < datetime('now','-10 minutes')`)
	_, _ = a.db.ExecContext(ctx, `
INSERT INTO button_responses (tid, msg_id, user_id, action, btn_index, form_data) VALUES ($1,$2,$3,$4,$5,$6)`,
		req.TID, req.MsgID, claims.Subject, req.Action, req.BtnIndex, req.FormData)

	// 按 action 分发
	switch req.Action {
	case "open_url":
		// 返回 url 给客户端跳转（data 来自消息，由服务端从消息里取，防止客户端伪造）
		url := a.extractButtonData(ctx, req.MsgID, req.BtnIndex)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"action": "open_url", "url": url})
		return
	case "reply_msg":
		data := a.extractButtonData(ctx, req.MsgID, req.BtnIndex)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"action": "reply_msg", "text": data})
		return
	case "form":
		data := a.extractButtonData(ctx, req.MsgID, req.BtnIndex)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"action": "form", "form": data})
		return
	case "send_text", "send_image":
		// 服务端代发预设内容到原会话（带 tid 标记，方便机器人区分）
		data := a.extractButtonData(ctx, req.MsgID, req.BtnIndex)
		if data == "" {
			writeError(w, http.StatusBadRequest, "empty_action_data", "empty data")
			return
		}
		msgType := "text"
		if req.Action == "send_image" {
			msgType = "image"
		}
		newMsgID := nanoid.New()
		var sendErr error
		if req.ToType == "group" {
			body := `{"v":3,"text":"` + escapeJSONText(data) + `","reply_tid":"` + req.TID + `"}`
			if req.Action == "send_image" {
				body = `{"v":3,"text":"","media_kind":"image","media_url":"` + escapeJSONText(data) + `","reply_tid":"` + req.TID + `"}`
			}
			sendErr = a.storeBotGroupMessage(ctx, claims.Subject, req.ToID, body, newMsgID)
		} else {
			body := `{"v":3,"text":"` + escapeJSONText(data) + `","reply_tid":"` + req.TID + `"}`
			if req.Action == "send_image" {
				body = `{"v":3,"text":"","media_kind":"image","media_url":"` + escapeJSONText(data) + `","reply_tid":"` + req.TID + `"}`
			}
			sendErr = a.storeBotDirectMessage(ctx, claims.Subject, req.ToID, body, newMsgID)
		}
		if sendErr != nil {
			log.Printf("bot callback send failed: %v", sendErr)
			writeError(w, http.StatusInternalServerError, "send_failed", "send failed")
			return
		}
		_ = msgType
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"ok": true, "msg_id": newMsgID})
		return
	}
	writeError(w, http.StatusBadRequest, "invalid_action", "invalid action")
}

// ---------- 内部辅助 ----------
func (a *API) buttonMessageCreatedAt(ctx context.Context, msgID string) (time.Time, error) {
	var createdAt time.Time
	if err := a.db.GetContext(ctx, &createdAt, `SELECT created_at FROM group_messages WHERE id=$1`, msgID); err == nil {
		return createdAt, nil
	}
	if err := a.db.GetContext(ctx, &createdAt, `SELECT created_at FROM direct_messages WHERE id=$1`, msgID); err != nil {
		return time.Time{}, err
	}
	return createdAt, nil
}

func (a *API) authenticateBotToken(ctx context.Context, rawToken string) (*botPermitRow, error) {
	if rawToken == "" {
		return nil, io.ErrUnexpectedEOF
	}
	sum := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(sum[:])
	row := &botPermitRow{}
	err := a.db.GetContext(ctx, row, `
SELECT id, user_id, status, scopes, expires_at FROM bot_permits
WHERE token_hash=$1 LIMIT 1`, tokenHash)
	if err != nil {
		return nil, err
	}
	if row.Status != "approved" {
		return nil, io.ErrUnexpectedEOF
	}
	if row.ExpiresAt != nil && time.Now().After(*row.ExpiresAt) {
		return nil, io.ErrUnexpectedEOF
	}
	return row, nil
}

type botPermitRow struct {
	ID           string     `db:"id"`
	UserID       string     `db:"user_id"`
	Status       string     `db:"status"`
	Scopes       string     `db:"scopes"`
	Reason       string     `db:"reason"`
	ReviewReason string     `db:"review_reason"`
	TokenHash    string     `db:"token_hash"`
	ExpiresAt    *time.Time `db:"expires_at"`
}

func (a *API) loadLatestBotPermit(ctx context.Context, userID string) (*botPermitRow, error) {
	row := &botPermitRow{}
	err := a.db.GetContext(ctx, row, `
SELECT id,user_id,status,scopes,reason,review_reason,token_hash,expires_at FROM bot_permits
WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1`, userID)
	if err != nil {
		return nil, err
	}
	return row, nil
}

// storeBotGroupMessage 复用正常群消息存储 + WS 广播 + 事件流
func (a *API) storeBotGroupMessage(ctx context.Context, senderID, groupID, body, msgID string) error {
	sender, err := a.users.GetByID(ctx, senderID)
	if err != nil {
		return err
	}
	msg := &data.GroupMessage{
		ID:       msgID,
		GroupID:  groupID,
		SenderID: senderID,
		Body:     body,
		MsgType:  "text",
	}
	if err := a.groupMsgs.Create(ctx, msg); err != nil {
		return err
	}
	resp := groupMessageResponse{
		SortSeq:   msg.SortSeq,
		GroupSeq:  msg.GroupSeq,
		ID:        msgID,
		GroupID:   groupID,
		FromUID:   sender.UID,
		FromNcuid: sender.Ncuid,
		FromName:  resolveSenderDisplayName(sender),
		Body:      body,
		MsgType:   "text",
		CreatedAt: time.Now().Unix(),
	}
	env := wsEnvelope{Type: "group_message", Data: resp}
	payload, err := json.Marshal(env)
	if err != nil {
		return nil
	}
	memberIDs, err := a.groups.ListMemberIDs(ctx, groupID)
	if err != nil {
		return nil
	}
	recipients := make([]string, 0, len(memberIDs))
	for _, memberID := range memberIDs {
		if memberID != senderID {
			recipients = append(recipients, memberID)
		}
	}
	a.wsHub.BroadcastToUsers(recipients, payload)
	// 群消息只推进每群唯一的 group_seq；离线成员重连后按水位增量拉取。
	return nil
}

// storeBotDirectMessage 复用正常私聊存储 + WS 广播 + 事件流
func (a *API) storeBotDirectMessage(ctx context.Context, senderID, toID, body, msgID string) error {
	var target *data.User
	var err error
	if strings.HasPrefix(toID, "nc_") {
		target, err = a.users.GetByNcuid(ctx, toID)
	} else {
		target, err = a.users.GetByUID(ctx, toID)
	}
	if err != nil {
		return err
	}
	sender, err := a.users.GetByID(ctx, senderID)
	if err != nil {
		return err
	}
	threadID, err := a.direct.GetThreadID(ctx, senderID, target.ID)
	if err != nil {
		newID := nanoid.New()
		threadID, err = a.direct.GetOrCreateThread(ctx, senderID, target.ID, newID)
		if err != nil {
			return err
		}
	}
	msg := &data.DirectMessage{
		ID:       msgID,
		ThreadID: threadID,
		SenderID: senderID,
		Body:     body,
		MsgType:  "text",
	}
	if err := a.direct.CreateMessage(ctx, msg); err != nil {
		return err
	}
	resp := directMessageResponse{
		SortSeq:   msg.SortSeq,
		ID:        msgID,
		ThreadID:  threadID,
		FromUID:   sender.UID,
		FromNcuid: sender.Ncuid,
		Body:      body,
		MsgType:   "text",
		CreatedAt: time.Now().Unix(),
	}
	env := wsEnvelope{Type: "direct_message", Data: resp}
	payload, err := json.Marshal(env)
	if err == nil {
		a.wsHub.BroadcastToUser(target.ID, payload)
	}
	if a.events != nil {
		if _, err := a.events.Emit(context.Background(), target.ID, "DIRECT_MESSAGE_NEW", msgID, map[string]interface{}{"message": resp}); err != nil {
			return err
		}
	}
	return nil
}

func (a *API) extractButtonData(ctx context.Context, msgID string, index int) string {
	// 从消息 body 里取 buttons[index].data（服务端权威来源，防客户端伪造）
	var body string
	_ = a.db.GetContext(ctx, &body, `SELECT body FROM group_messages WHERE id=$1`, msgID)
	if body == "" {
		_ = a.db.GetContext(ctx, &body, `SELECT body FROM direct_messages WHERE id=$1`, msgID)
	}
	if body == "" {
		return ""
	}
	var payload struct {
		Buttons []struct {
			Data string `json:"data"`
		} `json:"buttons"`
	}
	if err := json.Unmarshal([]byte(body), &payload); err != nil {
		return ""
	}
	if index >= 0 && index < len(payload.Buttons) {
		return payload.Buttons[index].Data
	}
	return ""
}

func isValidFormJSON(s string) bool {
	var v interface{}
	return json.Unmarshal([]byte(s), &v) == nil
}

func escapeJSONText(s string) string {
	b, _ := json.Marshal(s)
	return strings.Trim(string(b), `"`)
}

func (a *API) shopUserFromContext(r *http.Request) (*data.User, bool) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	token := extractShopToken(r)
	if token == "" {
		return nil, false
	}
	user, authErr := a.shopUserFromToken(ctx, token)
	if authErr != "" || user == nil {
		return nil, false
	}
	return user, true
}
