package httpapi

import (
	"context"
	"html/template"
	"net/http"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

type adminOAuthClientPage struct {
	Clients []data.OAuthClient
	Secret  string
	Error   string
}

var adminOAuthClientTemplate = template.Must(template.New("oauth_clients").Parse(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OAuth Clients - Oldchat</title><style>
*{box-sizing:border-box}body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#f4f4f4;color:#111}.wrap{max-width:1050px;margin:40px auto;padding:0 18px}.top{display:flex;justify-content:space-between;align-items:center}.card{background:#fff;border:2px solid #111;padding:22px;margin:18px 0;box-shadow:5px 5px 0 #111}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}label{font-weight:700}input{width:100%;padding:10px;margin-top:5px;border:1px solid #777}button,.btn{display:inline-block;background:#111;color:#fff;border:0;padding:10px 16px;font-weight:700;text-decoration:none;cursor:pointer}.secret{overflow-wrap:anywhere;background:#dcfce7;border:1px solid #16a34a;padding:14px}.err{background:#fee2e2;border:1px solid #dc2626;padding:12px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:10px;border-bottom:1px solid #ddd;vertical-align:top}code{overflow-wrap:anywhere}@media(max-width:700px){.grid{grid-template-columns:1fr}.table{overflow:auto}.top{align-items:start}}</style></head><body><main class="wrap"><div class="top"><h1>OAuth Clients</h1><a class="btn" href="/admins">返回后台</a></div>
{{if .Secret}}<div class="card secret"><b>Client Secret（仅显示一次）</b><p><code>{{.Secret}}</code></p><p>请立即交给应用开发者并安全保存，服务端只保存哈希。</p></div>{{end}}{{if .Error}}<div class="err">{{.Error}}</div>{{end}}
<section class="card"><h2>创建 Client</h2><form method="post" action="/admins/oauth-clients"><div class="grid"><label>应用名称<input name="name" maxlength="80" required></label><label>HTTPS 回调地址<input name="redirect_uri" type="url" required></label></div><label><input style="width:auto" type="checkbox" name="public_client" value="1"> 公开客户端（不签发 secret，仍强制 PKCE）</label><p><button name="action" value="create">创建</button></p></form></section>
<section class="card"><h2>已登记应用</h2><div class="table"><table><thead><tr><th>名称 / Client ID</th><th>回调</th><th>类型</th><th>状态</th><th>操作</th></tr></thead><tbody>{{range .Clients}}<tr><td><b>{{.Name}}</b><br><code>{{.ClientID}}</code></td><td><code>{{.RedirectURI}}</code></td><td>{{if .PublicClient}}PUBLIC{{else}}CONFIDENTIAL{{end}}</td><td>{{if .Active}}启用{{else}}停用{{end}}</td><td><form method="post" action="/admins/oauth-clients"><input type="hidden" name="client_id" value="{{.ClientID}}"><button name="action" value="{{if .Active}}disable{{else}}enable{{end}}">{{if .Active}}停用{{else}}启用{{end}}</button></form></td></tr>{{else}}<tr><td colspan="5">暂无 Client</td></tr>{{end}}</tbody></table></div></section></main></body></html>`))

func (a *API) handleAdminOAuthClients(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		http.Redirect(w, r, "/admins", http.StatusSeeOther)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	clients, err := a.oauth.ListClients(ctx)
	page := adminOAuthClientPage{Clients: clients}
	if err != nil {
		page.Error = "读取 Client 失败"
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = adminOAuthClientTemplate.Execute(w, page)
}

func (a *API) handleAdminOAuthClientsPost(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	action := r.FormValue("action")
	if action == "enable" || action == "disable" {
		_ = a.oauth.SetClientActive(ctx, strings.TrimSpace(r.FormValue("client_id")), action == "enable")
		http.Redirect(w, r, "/admins/oauth-clients", http.StatusSeeOther)
		return
	}
	name := strings.TrimSpace(r.FormValue("name"))
	redirect := strings.TrimSpace(r.FormValue("redirect_uri"))
	if name == "" || !auth.ValidateOAuthHTTPSURL(redirect, true) {
		a.renderAdminOAuthClients(w, ctx, "", "名称或 HTTPS 回调地址无效")
		return
	}
	public := r.FormValue("public_client") == "1"
	secret := ""
	secretHash := ""
	if !public {
		var err error
		secret, secretHash, err = auth.NewRefreshToken()
		if err != nil {
			a.renderAdminOAuthClients(w, ctx, "", "生成 secret 失败")
			return
		}
	}
	client := data.OAuthClient{ClientID: "oauth_" + nanoid.New(), Name: name, SecretHash: secretHash, RedirectURI: redirect, AllowedScopes: "openid profile offline_access", PublicClient: public, Active: true}
	if err := a.oauth.UpsertClient(ctx, client); err != nil {
		a.renderAdminOAuthClients(w, ctx, "", "创建 Client 失败")
		return
	}
	a.renderAdminOAuthClients(w, ctx, secret, "")
}

func (a *API) renderAdminOAuthClients(w http.ResponseWriter, ctx context.Context, secret, errorText string) {
	clients, _ := a.oauth.ListClients(ctx)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = adminOAuthClientTemplate.Execute(w, adminOAuthClientPage{Clients: clients, Secret: secret, Error: errorText})
}
