package httpapi

import (
	"html/template"
	"io"
	"net/http"
	"strings"
	"time"

	"metrochat/internal/data"
)

const channelWebCookie = "oldchat_channel_session"

type channelWebDashboard struct {
	CSRF         string
	UserUID      string
	Scopes       string
	CanCreate    bool
	CanPost      bool
	IsOwner      bool
	SelectedRole string
	Channels     []data.Channel
	Selected     *data.Channel
	Posts        []data.ChannelPost
	Publishers   []channelWebPublisher
	Message      string
}

type channelWebPublisher struct {
	UID      string `db:"uid"`
	Username string `db:"username"`
	Role     string `db:"role"`
}

func (a *API) handleChannelWebHome(w http.ResponseWriter, r *http.Request) {
	if session, csrf, ok := a.channelWebSessionForRequest(r); ok {
		a.renderChannelDashboard(w, r, session, csrf, "")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, strings.Replace(channelWebLoginHTML, "{{ERROR}}", "", 1))
}

func (a *API) handleChannelWebLogin(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
	if err := r.ParseForm(); err != nil {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}
	raw := strings.TrimSpace(r.FormValue("token"))
	raw = strings.TrimSpace(strings.TrimPrefix(raw, "Bearer "))
	raw = strings.Trim(raw, "\"'` ")
	permit, err := a.authenticateChannelAPIToken(r.Context(), raw)
	if err != nil || permit == nil || !hasChannelScope(permit.Scopes, "channel:manage") {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, strings.Replace(channelWebLoginHTML, "{{ERROR}}", "Token 无效、已过期或缺少 channel:manage 权限", 1))
		return
	}
	sid := a.channelWebSessions.New(permit.ID, permit.UserID, 12*time.Hour)
	http.SetCookie(w, &http.Cookie{Name: channelWebCookie, Value: sid, Path: "/channel-manage", HttpOnly: true, Secure: r.TLS != nil, SameSite: http.SameSiteLaxMode, MaxAge: 12 * 3600})
	http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
}

func (a *API) handleChannelWebLogout(w http.ResponseWriter, r *http.Request) {
	if cookie, err := r.Cookie(channelWebCookie); err == nil {
		a.channelWebSessions.Delete(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: channelWebCookie, Value: "", Path: "/channel-manage", MaxAge: -1, HttpOnly: true})
	http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
}

func (a *API) handleChannelWebPost(w http.ResponseWriter, r *http.Request) {
	session, csrf, ok := a.channelWebSessionForRequest(r)
	if !ok {
		http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
		return
	}
	if !hasChannelScope(a.channelWebPermitScopes(r, session), "channel:post") {
		http.Error(w, "当前 Token 缺少 channel:post 权限", http.StatusForbidden)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 52<<20)
	if err := r.ParseMultipartForm(52 << 20); err != nil {
		http.Error(w, "上传内容过大或格式错误", http.StatusBadRequest)
		return
	}
	if r.FormValue("csrf") != csrf {
		http.Error(w, "CSRF", http.StatusForbidden)
		return
	}
	channelID := strings.TrimSpace(r.FormValue("channel_id"))
	canPost, err := a.channels.CanPost(r.Context(), channelID, session.UserID)
	if err != nil || !canPost {
		http.Error(w, "无发帖权限", http.StatusForbidden)
		return
	}
	body := strings.TrimSpace(r.FormValue("body"))
	msgType, mediaURL := "text", ""
	if r.MultipartForm != nil && len(r.MultipartForm.File["media"]) > 0 {
		ref, detectedType, _, uploadErr := a.saveChannelMediaFile(r)
		if uploadErr != nil {
			http.Error(w, "只支持不超过50MB的图片、语音和视频", http.StatusBadRequest)
			return
		}
		mediaURL, msgType = ref, detectedType
	}
	if body == "" && mediaURL == "" {
		http.Error(w, "消息不能为空", http.StatusBadRequest)
		return
	}
	postID, _ := newPublicID("CP-", 14)
	post := &data.ChannelPost{ID: postID, ChannelID: channelID, AuthorID: session.UserID, Body: body, MsgType: msgType, MediaURL: mediaURL}
	postSeq, eventSeq, err := a.channels.CreatePost(r.Context(), post)
	if err != nil {
		http.Error(w, "发送失败", http.StatusInternalServerError)
		return
	}
	a.broadcastChannelUpdate(channelID, postSeq, eventSeq, "post_new")
	http.Redirect(w, r, "/channel-manage?channel_id="+channelID+"&message=sent", http.StatusSeeOther)
}

func (a *API) handleChannelWebPublisher(w http.ResponseWriter, r *http.Request) {
	session, csrf, ok := a.channelWebSessionForRequest(r)
	if !ok {
		http.Redirect(w, r, "/channel-manage", http.StatusSeeOther)
		return
	}
	if err := r.ParseForm(); err != nil || r.FormValue("csrf") != csrf {
		http.Error(w, "Bad request", http.StatusBadRequest)
		return
	}
	channelID, uid, action := strings.TrimSpace(r.FormValue("channel_id")), strings.TrimSpace(r.FormValue("uid")), r.FormValue("action")
	var ownerID string
	if err := a.db.GetContext(r.Context(), &ownerID, `SELECT owner_id FROM channels WHERE id=$1`, channelID); err != nil || ownerID != session.UserID {
		http.Error(w, "只有频道所有者可授权", http.StatusForbidden)
		return
	}
	var userID string
	if err := a.db.GetContext(r.Context(), &userID, `SELECT id FROM users WHERE uid=$1 OR ncuid=$1`, uid); err != nil {
		http.Error(w, "用户不存在", http.StatusNotFound)
		return
	}
	if action == "grant" {
		result, err := a.db.ExecContext(r.Context(), `UPDATE channel_subscriptions SET role='publisher' WHERE channel_id=$1 AND user_id=$2 AND role<>'owner'`, channelID, userID)
		if err != nil {
			http.Error(w, "授权失败", http.StatusInternalServerError)
			return
		}
		n, _ := result.RowsAffected()
		if n == 0 {
			http.Error(w, "该用户必须先关注频道", http.StatusBadRequest)
			return
		}
		_, _ = a.db.ExecContext(r.Context(), `UPDATE channel_sequences SET subscriber_version=subscriber_version+1 WHERE channel_id=$1`, channelID)
	} else if action == "revoke" {
		_, _ = a.db.ExecContext(r.Context(), `UPDATE channel_subscriptions SET role='subscriber' WHERE channel_id=$1 AND user_id=$2 AND role IN ('admin','publisher')`, channelID, userID)
		_, _ = a.db.ExecContext(r.Context(), `UPDATE channel_sequences SET subscriber_version=subscriber_version+1 WHERE channel_id=$1`, channelID)
	} else {
		http.Error(w, "无效操作", http.StatusBadRequest)
		return
	}
	http.Redirect(w, r, "/channel-manage?channel_id="+channelID, http.StatusSeeOther)
}

func (a *API) channelWebSessionForRequest(r *http.Request) (channelWebSession, string, bool) {
	cookie, err := r.Cookie(channelWebCookie)
	if err != nil {
		return channelWebSession{}, "", false
	}
	session, ok := a.channelWebSessions.Get(cookie.Value)
	if !ok {
		return channelWebSession{}, "", false
	}
	var active int
	err = a.db.GetContext(r.Context(), &active, `SELECT COUNT(1) FROM channel_api_permits WHERE id=$1 AND user_id=$2 AND status='approved' AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)`, session.PermitID, session.UserID)
	if err != nil || active != 1 {
		a.channelWebSessions.Delete(cookie.Value)
		return channelWebSession{}, "", false
	}
	return session, cookie.Value, true
}

func (a *API) channelWebPermitScopes(r *http.Request, session channelWebSession) string {
	var scopes string
	_ = a.db.GetContext(r.Context(), &scopes, `SELECT scopes FROM channel_api_permits WHERE id=$1 AND user_id=$2 AND status='approved'`, session.PermitID, session.UserID)
	return scopes
}

func (a *API) renderChannelDashboard(w http.ResponseWriter, r *http.Request, session channelWebSession, csrf, message string) {
	channels := make([]data.Channel, 0)
	_ = a.db.SelectContext(r.Context(), &channels, `SELECT id,owner_id,name,handle,avatar_url,description,visibility,status,subscriber_count,created_at FROM channels WHERE id IN (SELECT channel_id FROM channel_subscriptions WHERE user_id=$1 AND role IN ('owner','admin','publisher')) ORDER BY updated_at DESC`, session.UserID)
	selectedID := strings.TrimSpace(r.URL.Query().Get("channel_id"))
	if selectedID == "" && len(channels) > 0 {
		selectedID = channels[0].ID
	}
	var selected *data.Channel
	for i := range channels {
		if channels[i].ID == selectedID {
			selected = &channels[i]
			break
		}
	}
	posts, publishers := make([]data.ChannelPost, 0), make([]channelWebPublisher, 0)
	selectedRole := ""
	isOwner := false
	if selected != nil {
		_ = a.db.GetContext(r.Context(), &selectedRole, `SELECT role FROM channel_subscriptions WHERE channel_id=$1 AND user_id=$2`, selected.ID, session.UserID)
		isOwner = selected.OwnerID == session.UserID && selectedRole == "owner"
		// 私有媒体头像（channel-private:ca-xxx）转签名 URL 供页面展示。
		if strings.HasPrefix(selected.AvatarURL, channelMediaRefPrefix) {
			selected.AvatarURL = a.signedChannelMediaURL(selected.AvatarURL)
		}
		_ = a.db.SelectContext(r.Context(), &posts, `SELECT p.id,p.channel_id,p.post_seq,p.author_id,u.uid AS author_uid,u.username AS author_name,u.avatar_url AS author_avatar,p.body,p.msg_type,p.media_url,p.thumb_url,p.view_count,p.comment_count,p.edited_at,p.created_at FROM channel_posts p JOIN users u ON u.id=p.author_id WHERE p.channel_id=$1 AND p.deleted_at IS NULL ORDER BY p.post_seq DESC LIMIT 20`, selected.ID)
		for i := range posts {
			posts[i].MediaURL = a.signedChannelMediaURL(posts[i].MediaURL)
		}
		if isOwner {
			_ = a.db.SelectContext(r.Context(), &publishers, `SELECT u.uid,u.username,s.role FROM channel_subscriptions s JOIN users u ON u.id=s.user_id WHERE s.channel_id=$1 AND s.role IN ('owner','admin','publisher') ORDER BY CASE s.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END,u.username`, selected.ID)
		}
	}
	if r.URL.Query().Get("message") == "sent" {
		message = "消息已发送"
	}
	if r.URL.Query().Get("message") == "created" {
		message = "频道已创建"
	}
	if r.URL.Query().Get("message") == "updated" {
		message = "频道信息已更新"
	}
	var userUID string
	_ = a.db.GetContext(r.Context(), &userUID, `SELECT uid FROM users WHERE id=$1`, session.UserID)
	scopes := a.channelWebPermitScopes(r, session)
	data := channelWebDashboard{CSRF: csrf, UserUID: userUID, Scopes: scopes, CanCreate: hasChannelScope(scopes, "channel:create"), CanPost: hasChannelScope(scopes, "channel:post"), IsOwner: isOwner, SelectedRole: selectedRole, Channels: channels, Selected: selected, Posts: posts, Publishers: publishers, Message: message}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = channelDashboardTemplate.Execute(w, data)
}

var channelDashboardTemplate = template.Must(template.New("channel-dashboard").Funcs(template.FuncMap{
	"sliceName": func(name string) string {
		runes := []rune(strings.TrimSpace(name))
		if len(runes) == 0 {
			return "?"
		}
		return string(runes[0])
	},
}).Parse(channelDashboardHTMLV2))

const channelWebLoginHTML = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>频道管理 - OldChat</title><style>` + channelManageCSS + `</style></head><body><main class="page narrow"><nav><a href="/">HOME</a> / <a href="/channel-api/apply">TOKEN APPLY</a> / <a href="/channel-api.md">DEVELOPER DOC</a></nav><h1>频道管理</h1><p>使用包含 <code>channel:manage</code> 权限的 Publisher Token 登录。</p><div class="error">{{ERROR}}</div><form class="panel" method="post" action="/channel-manage/login"><label>Publisher Token</label><input name="token" type="password" placeholder="输入 Token" required autofocus><button type="submit">登录控制台</button></form><p class="muted">还没有 Token？请先提交频道 API Token 申请并等待审核。</p></main></body></html>`

const channelWebCSS = `*{box-sizing:border-box}body{margin:0;background:#eef2f5;color:#17212b;font:14px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}a{color:#168acd;text-decoration:none}.login{max-width:420px;margin:12vh auto;background:#fff;padding:34px;border-radius:12px;box-shadow:0 10px 35px #b8c3ca}.error{color:#d33;margin:10px 0}input,textarea,select{width:100%;padding:11px;margin:6px 0;border:1px solid #ccd5db;border-radius:7px;background:#fff}button{border:0;background:#168acd;color:#fff;padding:11px 18px;border-radius:7px;font-weight:700;cursor:pointer}.shell{display:grid;grid-template-columns:240px 1fr;min-height:100vh}.side{background:#17212b;color:#fff;padding:22px}.side a{display:block;padding:11px;color:#dce7ed;border-bottom:1px solid #293743}.main{padding:24px;max-width:1100px}.card{background:#fff;padding:20px;margin-bottom:16px;border-radius:10px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.post{padding:12px 0;border-bottom:1px solid #e3e8ec}.meta{color:#7a8a99;font-size:12px}.media{max-width:320px;max-height:220px}.publisher{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #eee}.drop{display:block;padding:18px;margin:8px 0;border:2px dashed #b9c8d2;border-radius:9px;text-align:center;color:#657580;background:#f5f8fa}.drop input{border:0}.progress{height:8px;background:#e4e9ed;border-radius:4px;overflow:hidden;margin:8px 0;display:none}.progress i{display:block;width:0;height:100%;background:#168acd}.progressText{color:#657580;font-size:12px}@media(max-width:760px){.shell{display:block}.side{min-height:auto}.grid{display:block}.main{padding:12px}}`

const channelDashboardHTML = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>频道管理 - OldChat</title><style>` + channelPageBaseCSS + `</style></head><body>
<div class="wrap" style="max-width:1100px">
  <div class="topbar">
    <h1>频道管理</h1>
    <div class="row" style="margin:0">
      <a class="btn secondary" href="/channel-manage/register">注册频道</a>
      <a class="btn secondary" href="/channel-api/apply">机器人接口申请</a>
      <a class="btn danger" href="/channel-manage/logout">退出</a>
    </div>
  </div>
  <div class="grid2">
    <aside>
      <div class="card" style="padding:12px">
        <div class="sub" style="margin-bottom:8px">我的频道</div>
        {{range .Channels}}
        <a href="/channel-manage?channel_id={{.ID}}" style="display:block;padding:10px 12px;border-radius:9px;margin:4px 0;{{if eq .ID $.Selected.ID}}background:#e0f2fe{{else}}background:#f8fafc{{end}}">
          <b>{{.Name}}</b><br><small style="color:#64748b">@{{.Handle}}</small>
        </a>
        {{end}}
      </div>
    </aside>
    <div>
      {{if .Selected}}
      {{if .Message}}<div class="msg">{{.Message}}</div>{{end}}
      <div class="card">
        <div style="display:flex;gap:16px;align-items:center">
          {{if .Selected.AvatarURL}}<img src="{{.Selected.AvatarURL}}" style="width:64px;height:64px;border-radius:50%;object-fit:cover">{{else}}<div style="width:64px;height:64px;border-radius:50%;background:#e2e8f0;display:flex;align-items:center;justify-content:center;color:#94a3b8;font-size:24px">{{sliceName .Selected.Name}}</div>{{end}}
          <div>
            <h1 style="margin:0">{{.Selected.Name}}</h1>
            <div class="sub">@{{.Selected.Handle}} · {{.Selected.SubscriberCount}} 订阅者 · {{.Selected.Visibility}}</div>
            <div class="sub">{{.Selected.Description}}</div>
          </div>
        </div>
      </div>

      <div class="card">
        <h3 style="margin:0 0 10px">频道设置</h3>
        <form method="post" action="/channel-manage/update" style="display:flex;gap:8px;flex-wrap:wrap;align-items:end">
          <input type="hidden" name="csrf" value="{{.CSRF}}"><input type="hidden" name="channel_id" value="{{.Selected.ID}}"><input type="hidden" name="action" value="name">
          <div style="flex:1;min-width:200px"><label style="margin:0 0 4px">频道名称</label><input type="text" name="name" value="{{.Selected.Name}}" maxlength="64"></div>
          <button class="btn" type="submit">改名</button>
        </form>
        <form method="post" action="/channel-manage/update" style="display:flex;gap:8px;flex-wrap:wrap;align-items:end;margin-top:10px">
          <input type="hidden" name="csrf" value="{{.CSRF}}"><input type="hidden" name="channel_id" value="{{.Selected.ID}}"><input type="hidden" name="action" value="avatar">
          <div style="flex:1;min-width:200px"><label style="margin:0 0 4px">头像 URL</label><input type="url" name="avatar_url" value="{{.Selected.AvatarURL}}" placeholder="https://..."></div>
          <button class="btn" type="submit">更新头像</button>
        </form>
        <form method="post" action="/channel-manage/update" style="margin-top:10px">
          <input type="hidden" name="csrf" value="{{.CSRF}}"><input type="hidden" name="channel_id" value="{{.Selected.ID}}"><input type="hidden" name="action" value="description">
          <label style="margin:0 0 4px">频道描述</label>
          <textarea name="description" rows="2" maxlength="500">{{.Selected.Description}}</textarea>
          <div class="row"><button class="btn" type="submit">更新描述</button></div>
        </form>
      </div>

      <div class="card">
        <h3 style="margin:0 0 10px">发布消息</h3>
        <form id="postForm" method="post" action="/channel-manage/post" enctype="multipart/form-data">
          <input type="hidden" name="csrf" value="{{.CSRF}}"><input type="hidden" name="channel_id" value="{{.Selected.ID}}">
          <textarea name="body" rows="4" placeholder="写点什么..."></textarea>
          <label class="drop">选择图片 / 语音 / 视频（最大 50MB）<input id="mediaFile" type="file" name="media" accept="image/*,audio/*,video/*"></label>
          <div id="progressText" class="hint"></div>
          <div class="row"><button id="sendButton" class="btn" type="submit">发送</button></div>
        </form>
      </div>

      <div class="card">
        <h3 style="margin:0 0 10px">最近消息</h3>
        {{range .Posts}}
        <div style="padding:10px 0;border-bottom:1px solid #e2e8f0">
          <b>{{.AuthorUID}}</b><div>{{.Body}}</div>{{if .MediaURL}}<a href="{{.MediaURL}}" target="_blank">媒体：{{.MsgType}}</a>{{end}}
          <div class="hint">#{{.PostSeq}} · {{.CreatedAt}}</div>
        </div>
        {{else}}<div class="sub">暂无消息</div>{{end}}
      </div>

      <div class="card">
        <h3 style="margin:0 0 10px">发言权管理</h3>
        <form method="post" action="/channel-manage/publisher" style="display:flex;gap:8px;align-items:center">
          <input type="hidden" name="csrf" value="{{.CSRF}}"><input type="hidden" name="channel_id" value="{{.Selected.ID}}">
          <input name="uid" placeholder="用户 UID / NCUID" style="flex:1">
          <button class="btn" name="action" value="grant" type="submit">授权</button>
        </form>
        {{range .Publishers}}
        <div class="publisher" style="display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid #f1f5f9">
          <span>{{.Username}} ({{.UID}})</span>
          <form method="post" action="/channel-manage/publisher"><input type="hidden" name="csrf" value="{{$.CSRF}}"><input type="hidden" name="channel_id" value="{{$.Selected.ID}}"><input type="hidden" name="uid" value="{{.UID}}"><button class="btn secondary" name="action" value="revoke" type="submit">撤销</button></form>
        </div>
        {{end}}
      </div>
      {{else}}<div class="card"><div class="sub">没有可管理的频道。点击右上角「注册频道」创建第一个频道。</div></div>{{end}}
    </div>
  </div>
</div>
<style>.grid2{display:grid;grid-template-columns:240px 1fr;gap:16px}@media(max-width:760px){.grid2{display:block}}</style>
<script>(function(){var f=document.getElementById('postForm');if(!f)return;var file=document.getElementById('mediaFile'),text=document.getElementById('progressText'),btn=document.getElementById('sendButton');file.onchange=function(){if(file.files[0]&&file.files[0].size>50*1024*1024){alert('文件不能超过50MB');file.value='';}else if(file.files[0])text.textContent=file.files[0].name+' · '+(file.files[0].size/1048576).toFixed(1)+' MB';};f.onsubmit=function(e){e.preventDefault();if(file.files[0]&&file.files[0].size>50*1024*1024){alert('文件不能超过50MB');return;}btn.disabled=true;text.textContent='上传中...';var x=new XMLHttpRequest();x.open('POST',f.action,true);x.upload.onprogress=function(p){if(p.lengthComputable){var n=Math.round(p.loaded*100/p.total);text.textContent='上传中 '+n+'%';}};x.onload=function(){if(x.status>=200&&x.status<400){text.textContent='发送成功';location.href='/channel-manage?channel_id={{.Selected.ID}}&message=sent';}else{btn.disabled=false;text.textContent='发送失败：'+x.responseText;}};x.onerror=function(){btn.disabled=false;text.textContent='网络错误';};x.send(new FormData(f));};})();</script>
</body></html>`
