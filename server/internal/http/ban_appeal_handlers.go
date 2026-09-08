package httpapi

import (
	"context"
	"database/sql"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

type iGotBannedPageData struct {
	Error        string
	Success      string
	Username     string
	AppealText   string
	Contact      string
	IsLoggedIn   bool
	IsBanned     bool
	UID          string
	BanReason    string
	BannedAt     string
	BanExpiry    string
	HasPending   bool
	LatestAppeal *data.BanAppeal
}

var iGotBannedTpl = template.Must(template.New("igotbanned").Parse(iGotBannedHTML))

func (a *API) handleIGotBannedPage(w http.ResponseWriter, r *http.Request) {
	a.renderIGotBannedPage(w, iGotBannedPageData{})
}

func (a *API) handleIGotBannedSubmit(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if !a.ipLimiter.Allow("igotbanned:" + clientIP(r)) {
		a.renderIGotBannedPage(w, iGotBannedPageData{Error: "提交过于频繁，请稍后再试"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := r.ParseForm(); err != nil {
		a.renderIGotBannedPage(w, iGotBannedPageData{Error: "提交失败，请稍后再试"})
		return
	}

	action := strings.ToLower(strings.TrimSpace(r.FormValue("action")))
	username := strings.ToLower(strings.TrimSpace(r.FormValue("username")))
	password := strings.TrimSpace(r.FormValue("password"))
	appealText := sanitizeBanAppealText(r.FormValue("appeal_text"))
	contact := sanitizeBanAppealContact(r.FormValue("contact"))
	pageData := iGotBannedPageData{
		Username:   username,
		AppealText: appealText,
		Contact:    contact,
	}

	if username == "" || password == "" {
		pageData.Error = "请输入用户名和密码"
		a.renderIGotBannedPage(w, pageData)
		return
	}
	if !isValidUsername(username) {
		pageData.Error = "用户名格式不正确"
		a.renderIGotBannedPage(w, pageData)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	user, err := a.users.GetByEmailOrUsername(ctx, username)
	if err != nil {
		_, _ = auth.VerifyPassword(password, dummyHash)
		pageData.Error = "账号或密码错误"
		a.renderIGotBannedPage(w, pageData)
		return
	}
	ok, err := auth.VerifyPassword(password, user.PasswordHash)
	if err != nil || !ok {
		pageData.Error = "账号或密码错误"
		a.renderIGotBannedPage(w, pageData)
		return
	}

	pageData.IsLoggedIn = true
	pageData.UID = user.UID

	banned, err := a.devices.IsUserBanned(ctx, user.ID)
	if err != nil {
		pageData.Error = "系统繁忙，请稍后再试"
		a.renderIGotBannedPage(w, pageData)
		return
	}

	banReason := ""
	banExpiry := ""
	bannedAt := ""
	banUntil := time.Time{}
	if banRow, banErr := a.devices.GetBannedUserByUserID(ctx, user.ID); banErr == nil && banRow != nil {
		banReason = strings.TrimSpace(banRow.Reason)
		bannedAt = banRow.CreatedAt.Format("2006-01-02 15:04:05")
		if banRow.BannedUntil.Valid {
			banUntil = banRow.BannedUntil.Time
		}
		banExpiry = formatBanExpiry(banRow.BannedUntil)
	}

	pageData.IsBanned = banned
	pageData.BanReason = banReason
	pageData.BannedAt = bannedAt
	pageData.BanExpiry = banExpiry

	// 查询最新一条申诉记录
	latestAppeal, _ := a.banAppeals.GetLatestByUser(ctx, user.ID)
	pageData.LatestAppeal = latestAppeal
	hasPending := latestAppeal != nil && latestAppeal.Status == "pending"
	pageData.HasPending = hasPending

	// 如果仅是查询封禁状态
	if action == "check" || appealText == "" {
		if !banned {
			pageData.Success = "✅ 该账号当前状态正常，未被封禁，无需提交申诉。"
		} else {
			pageData.Success = "🔍 已查询到您的账号封禁状态与详细原因，请在下方查看。"
		}
		a.renderIGotBannedPage(w, pageData)
		return
	}

	// 提交申诉流程
	if !banned {
		pageData.Error = "该账号当前未封禁，无需提交申诉"
		a.renderIGotBannedPage(w, pageData)
		return
	}

	if hasPending {
		pageData.Error = "您已有待处理的申诉正在审核中，请勿重复提交"
		a.renderIGotBannedPage(w, pageData)
		return
	}

	appeal := &data.BanAppeal{
		UserID:     user.ID,
		UID:        user.UID,
		Username:   user.Username,
		BanReason:  banReason,
		AppealText: appealText,
		Contact:    contact,
		Status:     "pending",
	}
	if !banUntil.IsZero() {
		appeal.BannedUntil.Valid = true
		appeal.BannedUntil.Time = banUntil
	}
	if err := a.banAppeals.Create(ctx, appeal); err != nil {
		pageData.Error = "提交失败，请稍后重试"
		a.renderIGotBannedPage(w, pageData)
		return
	}

	// 刷新最新申诉
	latestAppeal, _ = a.banAppeals.GetLatestByUser(ctx, user.ID)
	pageData.LatestAppeal = latestAppeal
	pageData.HasPending = true
	pageData.Success = "🎉 申诉提交成功！管理员会尽快在后台审核处理。"
	pageData.AppealText = ""
	a.renderIGotBannedPage(w, pageData)
}

func (a *API) handleAdminBanAppealStatus(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	appealID := strings.TrimSpace(r.FormValue("id"))
	status := normalizeBanAppealStatusInput(r.FormValue("status"))
	adminNote := strings.TrimSpace(r.FormValue("admin_note"))
	unbanOnApprove := strings.TrimSpace(r.FormValue("unban_on_approve"))
	if appealID == "" {
		http.Redirect(w, r, "/admins?err="+url.QueryEscape("缺少申诉ID")+"#banappeals", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()

	appeal, err := a.banAppeals.GetByID(ctx, appealID)
	if err != nil {
		http.Redirect(w, r, "/admins?err="+url.QueryEscape("申诉不存在")+"#banappeals", http.StatusSeeOther)
		return
	}

	if err = a.banAppeals.SetStatus(ctx, appealID, status, adminNote); err != nil {
		http.Redirect(w, r, "/admins?err="+url.QueryEscape("申诉处理失败")+"#banappeals", http.StatusSeeOther)
		return
	}

	shouldUnban := status == "approved"
	if shouldUnban {
		if unbanOnApprove == "0" || strings.EqualFold(unbanOnApprove, "false") {
			shouldUnban = false
		}
	}
	if shouldUnban && appeal != nil && strings.TrimSpace(appeal.UserID) != "" {
		_ = a.devices.UnbanUser(ctx, appeal.UserID)
	}

	okMsg := "申诉状态已更新"
	if shouldUnban {
		okMsg = "申诉已通过并解封账号"
	}
	http.Redirect(w, r, "/admins?ok="+url.QueryEscape(okMsg)+"#banappeals", http.StatusSeeOther)
}

func (a *API) renderIGotBannedPage(w http.ResponseWriter, pageData iGotBannedPageData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = iGotBannedTpl.Execute(w, pageData)
}

func sanitizeBanAppealText(value string) string {
	text := strings.TrimSpace(value)
	if text == "" {
		return ""
	}
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	if utf8.RuneCountInString(text) > 1000 {
		runes := []rune(text)
		text = string(runes[:1000])
	}
	return text
}

func sanitizeBanAppealContact(value string) string {
	contact := strings.TrimSpace(value)
	if contact == "" {
		return ""
	}
	contact = strings.ReplaceAll(contact, "\r", " ")
	contact = strings.ReplaceAll(contact, "\n", " ")
	if utf8.RuneCountInString(contact) > 120 {
		runes := []rune(contact)
		contact = string(runes[:120])
	}
	return contact
}

func formatBanExpiry(v sql.NullTime) string {
	if v.Valid {
		return v.Time.Format("2006-01-02 15:04")
	}
	return "永久"
}

func normalizeBanAppealStatusInput(raw string) string {
	v := strings.ToLower(strings.TrimSpace(raw))
	switch v {
	case "pending", "open", "todo":
		return "pending"
	case "approved", "approve", "pass", "accepted":
		return "approved"
	case "rejected", "reject", "deny", "declined":
		return "rejected"
	default:
		return "pending"
	}
}

const iGotBannedHTML = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>旧聊 · 封禁状态查询与申诉通道</title>
  <style>
    :root {
      --bg: #ffffff;
      --card: #ffffff;
      --border: #111111;
      --text: #111111;
      --heading: #000000;
      --muted: #555555;
      --primary: #111111;
      --danger: #c00000;
      --danger-bg: #fff0f0;
      --ok-bg: #f0fff0;
      --font-mono: "SFMono-Regular", Consolas, Menlo, monospace;
    }
    * { box-sizing: border-box; border-radius:0!important; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      line-height: 1.65;
      padding: 30px 16px 80px;
    }
    .container {
      max-width: 680px;
      margin: 0 auto;
    }
    .nav {
      font-size: 13px;
      font-family: var(--font-mono);
      margin-bottom: 20px;
    }
    .nav a { color: #0366d6; text-decoration: none; font-weight: 700; }
    .nav a:hover { text-decoration: underline; }
    .card {
      background: var(--card);
      border: 2px solid var(--border);
      padding: 24px;
      margin-bottom: 20px;
    }
    h1 {
      font-size: 24px;
      color: var(--heading);
      margin-bottom: 6px;
      font-weight: 900;
    }
    .subtitle {
      font-size: 13px;
      color: var(--muted);
      margin-bottom: 20px;
    }
    .alert {
      padding: 12px 16px;
      font-size: 14px;
      margin-bottom: 20px;
      border: 2px solid var(--border);
    }
    .alert-ok { background: var(--ok-bg); border-color: #0a7a0a; color: #0a7a0a; font-weight: 700; }
    .alert-err { background: var(--danger-bg); border-color: var(--danger); color: var(--danger); font-weight: 700; }
    
    .status-panel {
      border: 2px solid var(--border);
      background: #f8f8f8;
      padding: 16px;
      margin-bottom: 20px;
    }
    .status-badge {
      display: inline-block;
      padding: 4px 10px;
      font-size: 12px;
      font-weight: 800;
      font-family: var(--font-mono);
      margin-bottom: 12px;
      border: 2px solid var(--border);
    }
    .badge-banned { background: var(--danger); color: #ffffff; border-color: var(--danger); }
    .badge-normal { background: #0a7a0a; color: #ffffff; border-color: #0a7a0a; }
    
    .info-row {
      display: flex;
      margin-bottom: 8px;
      font-size: 13px;
      line-height: 1.5;
    }
    .info-row .label {
      width: 100px;
      color: var(--muted);
      flex-shrink: 0;
      font-weight: 700;
    }
    .info-row .val {
      color: var(--heading);
      word-break: break-all;
    }
    .reason-box {
      background: #fff;
      border: 2px solid var(--danger);
      padding: 10px 12px;
      margin-top: 10px;
      font-size: 13px;
      color: var(--danger);
      line-height: 1.5;
    }

    .appeal-history {
      border: 2px solid var(--border);
      padding: 14px;
      margin-top: 14px;
      background: #ffffff;
      font-size: 13px;
    }
    .appeal-history-title {
      font-weight: 800;
      color: var(--heading);
      margin-bottom: 8px;
      font-family: var(--font-mono);
    }

    label {
      display: block;
      font-size: 13px;
      font-weight: 700;
      color: var(--heading);
      margin-top: 14px;
      margin-bottom: 4px;
    }
    input, textarea {
      width: 100%;
      background: #ffffff;
      border: 2px solid var(--border);
      color: var(--heading);
      padding: 10px 12px;
      font-size: 14px;
      font-family: inherit;
    }
    input:focus, textarea:focus {
      outline: none;
      background: #fafafa;
    }
    textarea {
      min-height: 110px;
      resize: vertical;
    }
    .btn-group {
      display: flex;
      gap: 12px;
      margin-top: 18px;
      flex-wrap: wrap;
    }
    button {
      padding: 10px 20px;
      font-size: 14px;
      font-weight: 800;
      cursor: pointer;
      border: 2px solid var(--border);
      transition: all 0.15s ease;
      font-family: var(--font-mono);
    }
    .btn-primary { background: var(--primary); color: #ffffff; }
    .btn-primary:hover { background: #ffffff; color: var(--primary); }
    .btn-ghost { background: #ffffff; color: var(--border); }
    .btn-ghost:hover { background: #f0f0f0; }
    .note {
      font-size: 12px;
      color: var(--muted);
      margin-top: 20px;
      line-height: 1.6;
      border-top: 1px solid #ccc;
      padding-top: 14px;
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="nav">
      <a href="/">HOME</a> / <a href="/download/oldchat.apk">DOWNLOAD</a> / <a href="/shop">SHOP</a> / <a href="/terms">TERMS</a>
    </div>

    <div class="card">
      <h1>封禁状态查询与申诉通道</h1>
      <p class="subtitle">登录您的 OldChat 账号，即可随时查询封禁详情与提交解封申诉。</p>

      {{if .Success}}<div class="alert alert-ok">{{.Success}}</div>{{end}}
      {{if .Error}}<div class="alert alert-err">{{.Error}}</div>{{end}}

      {{if .IsLoggedIn}}
        <!-- 登录后：封禁状态与详细原因展示面板 -->
        <div class="status-panel">
          {{if .IsBanned}}
            <span class="status-badge badge-banned">[封禁中] 账号处于封禁状态</span>
            <div class="info-row"><span class="label">账号 UID:</span><span class="val">{{.UID}} ({{.Username}})</span></div>
            <div class="info-row"><span class="label">封禁时间:</span><span class="val">{{if .BannedAt}}{{.BannedAt}}{{else}}-{{end}}</span></div>
            <div class="info-row"><span class="label">解封到期:</span><span class="val" style="color:var(--danger);font-weight:700;">{{if .BanExpiry}}{{.BanExpiry}}{{else}}永久封禁{{end}}</span></div>
            <div class="info-row" style="margin-top:10px;"><span class="label">封禁原因:</span></div>
            <div class="reason-box"><strong>[官方记录原因]</strong><br>{{if .BanReason}}{{.BanReason}}{{else}}违反社区规范与用户协议{{end}}</div>
          {{else}}
            <span class="status-badge badge-normal">[正常] 账号状态正常</span>
            <div class="info-row"><span class="label">账号 UID:</span><span class="val">{{.UID}} ({{.Username}})</span></div>
            <p style="font-size:13px;color:#0a7a0a;margin-top:6px;font-weight:700;">该账号当前未受到任何封禁限制，无需提交申诉。</p>
          {{end}}

          {{if .LatestAppeal}}
            <div class="appeal-history">
              <div class="appeal-history-title">[最近申诉记录]</div>
              <div class="info-row">
                <span class="label">申诉状态:</span>
                <span class="val">
                  {{if eq .LatestAppeal.Status "pending"}}<span style="color:#b8860b;font-weight:700;">[审核中] 正在审核中</span>
                  {{else if eq .LatestAppeal.Status "approved"}}<span style="color:#0a7a0a;font-weight:700;">[已通过] 申诉已通过 (已解封)</span>
                  {{else if eq .LatestAppeal.Status "rejected"}}<span style="color:var(--danger);font-weight:700;">[已驳回] 申诉已驳回</span>
                  {{else}}{{.LatestAppeal.Status}}{{end}}
                </span>
              </div>
              <div class="info-row"><span class="label">提交时间:</span><span class="val">{{.LatestAppeal.CreatedAt.Format "2006-01-02 15:04:05"}}</span></div>
              <div class="info-row"><span class="label">我的申诉:</span><span class="val">{{.LatestAppeal.AppealText}}</span></div>
              {{if .LatestAppeal.AdminNote}}
              <div class="info-row" style="margin-top:6px;border-top:1px dashed #ccc;padding-top:6px;">
                <span class="label">处理意见:</span><span class="val" style="color:#0366d6;font-weight:700;">{{.LatestAppeal.AdminNote}}</span>
              </div>
              {{end}}
            </div>
          {{end}}
        </div>
      {{end}}

      <!-- 申诉表单 -->
      <form method="post" action="/igotbanned">
        <label>用户名</label>
        <input name="username" value="{{.Username}}" placeholder="请输入账号用户名" required>

        <label>密码</label>
        <input name="password" type="password" placeholder="请输入账号密码" required>

        {{if and .IsLoggedIn .IsBanned (not .HasPending)}}
        <div style="margin-top:16px;border-top:2px solid var(--border);padding-top:14px;">
          <label>申诉理由与整改说明</label>
          <textarea name="appeal_text" placeholder="请详细说明封禁经过、申诉理由，以及你愿意如何遵守社区规范。" required>{{.AppealText}}</textarea>

          <label>联系方式 (可选)</label>
          <input name="contact" value="{{.Contact}}" placeholder="例如 QQ 邮箱 / 备用联系方式">

          <div class="btn-group">
            <button type="submit" name="action" value="appeal" class="btn-primary">[提交解封申诉]</button>
            <button type="submit" name="action" value="check" class="btn-ghost">[仅刷新查询]</button>
          </div>
        </div>
        {{else if and .IsLoggedIn .HasPending}}
        <div style="margin-top:16px;padding:12px;background:#fffde7;border:2px solid #b8860b;font-size:13px;color:#856404;font-weight:700;">
          [审核中] 您的申诉正在由管理员/审核团队审核中，请耐心等待，无需重复提交。
        </div>
        <div class="btn-group">
          <button type="submit" name="action" value="check" class="btn-ghost">[刷新查询状态]</button>
        </div>
        {{else}}
        <div style="margin-top:14px;">
          <label>申诉理由 (如需直接提交)</label>
          <textarea name="appeal_text" placeholder="可直接填写申诉理由，或先点击下方“仅查询封禁原因”">{{.AppealText}}</textarea>

          <label>联系方式 (可选)</label>
          <input name="contact" value="{{.Contact}}" placeholder="例如 QQ 邮箱">

          <div class="btn-group">
            <button type="submit" name="action" value="check" class="btn-ghost">[仅查询封禁原因与进度]</button>
            <button type="submit" name="action" value="appeal" class="btn-primary">[提交解封申诉]</button>
          </div>
        </div>
        {{end}}
      </form>

      <div class="note">
        <strong>[申诉须知]</strong><br>
        1. 系统会自动验证账号密码并显示您被封禁的详细原因与期限；<br>
        2. 若申诉被驳回，您可补充新的说明材料再次提交；<br>
        3. 如有任何违规操作，请如实说明经过，态度诚恳者优先予以复核。
      </div>
    </div>
  </div>
</body>
</html>
`
