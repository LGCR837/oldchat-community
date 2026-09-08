package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

const oauthCSRFCookie = "__Host-oldchat_oauth_csrf"

type oauthPageData struct {
	Title       string
	RequestID   string
	CSRF        string
	ClientName  string
	Scope       string
	Error       string
	Consent     bool
	RegisterURL string
	ResetURL    string
}

var oauthPageTemplate = template.Must(template.New("oauth").Parse(`<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{{.Title}} - Oldchat</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f4f6;color:#171717;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}.shell{max-width:430px;margin:8vh auto;padding:18px}.card{background:#fff;border:1px solid #ddd;padding:28px;box-shadow:8px 8px 0 #171717}.brand{font-weight:800;font-size:24px;margin-bottom:8px}.sub{color:#666;margin:0 0 22px}.err{background:#fee2e2;border:1px solid #dc2626;padding:10px;margin-bottom:14px}label{display:block;font-weight:700;margin-top:14px}input{width:100%;padding:12px;margin-top:6px;border:1px solid #999;font-size:16px}button{width:100%;padding:12px;margin-top:18px;border:0;background:#171717;color:#fff;font-weight:700;font-size:16px}.secondary{background:#fff;color:#171717;border:1px solid #171717}.links{display:flex;justify-content:space-between;margin-top:18px}.links a{color:#175cd3;text-decoration:none}.scope{padding:14px;border:1px solid #bbb;background:#fafafa;line-height:1.7}</style></head>
<body><main class="shell"><section class="card"><div class="brand">Oldchat ID</div>
{{if .Consent}}<p class="sub"><b>{{.ClientName}}</b> 请求登录你的 Oldchat 账户</p><div class="scope">授权范围：{{.Scope}}<br>仅会提供稳定用户标识和公开资料。</div>
<form method="post" action="/oauth/authorize"><input type="hidden" name="request_id" value="{{.RequestID}}"><input type="hidden" name="csrf" value="{{.CSRF}}"><button name="action" value="approve">允许并继续</button><button class="secondary" name="action" value="deny">拒绝</button></form>
{{else}}<p class="sub">使用 Oldchat 账户登录 <b>{{.ClientName}}</b></p>{{if .Error}}<div class="err">{{.Error}}</div>{{end}}
<form method="post" action="/oauth/authorize"><input type="hidden" name="request_id" value="{{.RequestID}}"><input type="hidden" name="csrf" value="{{.CSRF}}"><label>用户名或邮箱<input name="identifier" autocomplete="username" required></label><label>密码<input type="password" name="password" autocomplete="current-password" required></label><button name="action" value="login">登录</button></form><div class="links"><a href="{{.RegisterURL}}">注册账户</a><a href="{{.ResetURL}}">找回密码</a></div>{{end}}
</section></main></body></html>`))

func (a *API) oauthAvailable(w http.ResponseWriter) bool {
	if a.oauth == nil || a.oauthSigningKey == nil {
		writeOAuthError(w, http.StatusServiceUnavailable, "temporarily_unavailable", "OAuth service unavailable")
		return false
	}
	return true
}

func (a *API) handleOIDCDiscovery(w http.ResponseWriter, r *http.Request) {
	issuer := a.cfg.OAuthIssuer
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"issuer":                                issuer,
		"authorization_endpoint":                issuer + "/oauth/authorize",
		"token_endpoint":                        issuer + "/oauth/token",
		"userinfo_endpoint":                     issuer + "/oauth/userinfo",
		"jwks_uri":                              issuer + "/oauth/jwks.json",
		"revocation_endpoint":                   issuer + "/oauth/revoke",
		"introspection_endpoint":                issuer + "/oauth/introspect",
		"response_types_supported":              []string{"code"},
		"grant_types_supported":                 []string{"authorization_code", "refresh_token"},
		"subject_types_supported":               []string{"public"},
		"id_token_signing_alg_values_supported": []string{"EdDSA"},
		"code_challenge_methods_supported":      []string{"S256"},
		"scopes_supported":                      []string{"openid", "profile", "offline_access"},
		"token_endpoint_auth_methods_supported": []string{"client_secret_basic", "none"},
	})
}

func (a *API) handleOAuthJWKS(w http.ResponseWriter, r *http.Request) {
	if !a.oauthAvailable(w) {
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=3600")
	writeJSON(w, http.StatusOK, map[string]interface{}{"keys": []interface{}{a.oauthSigningKey.JWK()}})
}

func (a *API) handleOAuthAuthorizeGet(w http.ResponseWriter, r *http.Request) {
	if !a.oauthAvailable(w) {
		return
	}
	q := r.URL.Query()
	decodedChallenge, challengeErr := base64.RawURLEncoding.DecodeString(q.Get("code_challenge"))
	if q.Get("response_type") != "code" || q.Get("client_id") == "" || q.Get("redirect_uri") == "" || q.Get("state") == "" || q.Get("nonce") == "" || q.Get("code_challenge_method") != "S256" || challengeErr != nil || len(decodedChallenge) != 32 {
		writeOAuthError(w, http.StatusBadRequest, "invalid_request", "invalid authorization request")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	_ = a.oauth.PruneExpired(ctx)
	client, err := a.oauth.GetClient(ctx, q.Get("client_id"))
	if err != nil || !client.Active || client.RedirectURI != q.Get("redirect_uri") {
		writeOAuthError(w, http.StatusBadRequest, "invalid_client", "unknown client or redirect_uri")
		return
	}
	scope, ok := auth.NormalizeScopes(q.Get("scope"), client.AllowedScopes)
	if !ok {
		writeOAuthError(w, http.StatusBadRequest, "invalid_scope", "invalid scope")
		return
	}
	csrf, _, err := auth.NewRefreshToken()
	if err != nil {
		writeOAuthError(w, 500, "server_error", "random generation failed")
		return
	}
	req := data.OAuthAuthorizationRequest{ID: nanoid.New(), ClientID: client.ClientID, RedirectURI: client.RedirectURI, Scope: scope, State: q.Get("state"), Nonce: q.Get("nonce"), CodeChallenge: q.Get("code_challenge"), CSRFHash: auth.HashToken(csrf), ExpiresAt: time.Now().Add(10 * time.Minute)}
	if err = a.oauth.CreateAuthorizationRequest(ctx, req); err != nil {
		writeOAuthError(w, 500, "server_error", "internal error")
		return
	}
	http.SetCookie(w, &http.Cookie{Name: oauthCSRFCookie, Value: csrf, Path: "/", MaxAge: 600, HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode})
	setOAuthPageHeaders(w)
	_ = oauthPageTemplate.Execute(w, oauthPageData{Title: "登录", RequestID: req.ID, CSRF: csrf, ClientName: client.Name, RegisterURL: "/oauth/register?request_id=" + url.QueryEscape(req.ID), ResetURL: "/oauth/reset?request_id=" + url.QueryEscape(req.ID)})
}

func (a *API) handleOAuthAuthorizePost(w http.ResponseWriter, r *http.Request) {
	if !a.oauthAvailable(w) {
		return
	}
	if !parseOAuthForm(w, r) {
		writeOAuthError(w, 400, "invalid_request", "invalid form")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	req, err := a.oauth.GetAuthorizationRequest(ctx, r.FormValue("request_id"))
	if err != nil || time.Now().After(req.ExpiresAt) {
		writeOAuthError(w, 400, "invalid_request", "request expired")
		return
	}
	cookie, err := r.Cookie(oauthCSRFCookie)
	csrf := r.FormValue("csrf")
	if err != nil || !auth.ConstantTimeEqual(cookie.Value, csrf) || !auth.ConstantTimeEqual(req.CSRFHash, auth.HashToken(csrf)) {
		writeOAuthError(w, 403, "access_denied", "CSRF validation failed")
		return
	}
	client, err := a.oauth.GetClient(ctx, req.ClientID)
	if err != nil || !client.Active {
		writeOAuthError(w, 400, "invalid_client", "client disabled")
		return
	}
	switch r.FormValue("action") {
	case "login":
		identifier := strings.ToLower(strings.TrimSpace(r.FormValue("identifier")))
		if !a.ipLimiter.Allow("oauth-login-ip:"+clientIP(r)) || !a.idLimiter.Allow("oauth-login:"+identifier) {
			writeOAuthError(w, 429, "slow_down", "too many attempts")
			return
		}
		user, err := a.users.GetByEmailOrUsername(ctx, identifier)
		passwordHash := dummyHash
		if err == nil {
			passwordHash = user.PasswordHash
		}
		validPassword, _ := auth.VerifyPassword(r.FormValue("password"), passwordHash)
		if err != nil || !validPassword {
			setOAuthPageHeaders(w)
			w.WriteHeader(http.StatusUnauthorized)
			_ = oauthPageTemplate.Execute(w, oauthPageData{Title: "登录", RequestID: req.ID, CSRF: csrf, ClientName: client.Name, Error: "账号或密码错误", RegisterURL: "/oauth/register?request_id=" + url.QueryEscape(req.ID), ResetURL: "/oauth/reset?request_id=" + url.QueryEscape(req.ID)})
			return
		}
		banned, _ := a.devices.IsUserBanned(ctx, user.ID)
		if banned {
			writeOAuthError(w, 403, "access_denied", "account unavailable")
			return
		}
		if err = a.oauth.SetAuthorizationUser(ctx, req.ID, user.ID); err != nil {
			writeOAuthError(w, 500, "server_error", "internal error")
			return
		}
		setOAuthPageHeaders(w)
		_ = oauthPageTemplate.Execute(w, oauthPageData{Title: "授权确认", RequestID: req.ID, CSRF: csrf, ClientName: client.Name, Scope: req.Scope, Consent: true})
	case "approve":
		if req.UserID == "" {
			writeOAuthError(w, 400, "login_required", "login required")
			return
		}
		code, _, err := auth.NewRefreshToken()
		if err != nil {
			writeOAuthError(w, 500, "server_error", "random generation failed")
			return
		}
		if _, err = a.oauth.CreateCodeAndDeleteRequest(ctx, req.ID, auth.HashToken(code), time.Now().Add(a.cfg.OAuthAuthorizationCodeTTL)); err != nil {
			writeOAuthError(w, 500, "server_error", "internal error")
			return
		}
		redirectOAuth(w, r, req.RedirectURI, map[string]string{"code": code, "state": req.State})
	case "deny":
		_ = a.oauth.DeleteAuthorizationRequest(ctx, req.ID)
		http.SetCookie(w, &http.Cookie{Name: oauthCSRFCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode})
		redirectOAuth(w, r, req.RedirectURI, map[string]string{"error": "access_denied", "state": req.State})
	default:
		writeOAuthError(w, 400, "invalid_request", "invalid action")
	}
}

func (a *API) handleOAuthToken(w http.ResponseWriter, r *http.Request) {
	if !a.oauthAvailable(w) {
		return
	}
	if !parseOAuthForm(w, r) {
		writeOAuthError(w, 400, "invalid_request", "invalid form")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	client, ok := a.authenticateOAuthClient(ctx, r)
	if !ok {
		w.Header().Set("WWW-Authenticate", `Basic realm="oauth"`)
		writeOAuthError(w, 401, "invalid_client", "client authentication failed")
		return
	}
	switch r.FormValue("grant_type") {
	case "authorization_code":
		challenge, valid := auth.PKCEChallengeS256(r.FormValue("code_verifier"))
		if !valid {
			writeOAuthError(w, 400, "invalid_grant", "invalid code_verifier")
			return
		}
		code, err := a.oauth.ConsumeCode(ctx, auth.HashToken(r.FormValue("code")), client.ClientID, r.FormValue("redirect_uri"), challenge)
		if err != nil {
			writeOAuthError(w, 400, "invalid_grant", "invalid or expired code")
			return
		}
		a.issueOAuthTokens(w, ctx, client, code.UserID, code.Scope, code.Nonce, code.AuthTime, "", "")
	case "refresh_token":
		raw := r.FormValue("refresh_token")
		hash := auth.HashToken(raw)
		record, err := a.oauth.GetRefreshToken(ctx, hash)
		if err != nil || record.ClientID != client.ClientID || record.RevokedAt.Valid || time.Now().After(record.ExpiresAt) {
			if old, lookupErr := a.oauth.GetRefreshTokenAny(ctx, hash); lookupErr == nil && old.ClientID == client.ClientID && old.RevokedAt.Valid {
				_ = a.oauth.RevokeGrant(ctx, old.GrantID)
			}
			writeOAuthError(w, 400, "invalid_grant", "invalid refresh token")
			return
		}
		a.issueOAuthTokens(w, ctx, client, record.UserID, record.Scope, "", record.AuthTime, record.GrantID, record.TokenHash)
	default:
		writeOAuthError(w, 400, "unsupported_grant_type", "unsupported grant type")
	}
}

func (a *API) issueOAuthTokens(w http.ResponseWriter, ctx context.Context, client *data.OAuthClient, userID, scope, nonce string, authTime time.Time, existingGrantID, oldRefreshHash string) {
	user, err := a.users.GetByID(ctx, userID)
	if err != nil {
		writeOAuthError(w, 500, "server_error", "internal error")
		return
	}
	banned, banErr := a.devices.IsUserBanned(ctx, user.ID)
	if banErr != nil || banned {
		writeOAuthError(w, 400, "invalid_grant", "account unavailable")
		return
	}
	if authTime.IsZero() {
		authTime = time.Now()
	}
	grantID := existingGrantID
	if grantID == "" {
		grantID = nanoid.New()
	}
	grantID, err = a.oauth.UpsertGrant(ctx, grantID, client.ClientID, userID, scope)
	if err != nil {
		writeOAuthError(w, 500, "server_error", "internal error")
		return
	}
	name := ""
	picture := ""
	uid := ""
	if auth.ScopeContains(scope, "profile") {
		name = user.DisplayName
		picture = user.AvatarURL
		uid = user.UID
	}
	idToken, err := a.oauthSigningKey.SignIDToken(a.cfg.OAuthIssuer, client.ClientID, user.Ncuid, nonce, uid, name, picture, authTime, a.cfg.OAuthAccessTokenTTL)
	if err != nil {
		writeOAuthError(w, 500, "server_error", "token signing failed")
		return
	}
	accessRaw, accessHash, err := auth.NewRefreshToken()
	if err != nil {
		writeOAuthError(w, 500, "server_error", "token generation failed")
		return
	}
	refreshRaw := ""
	refreshHash := ""
	if auth.ScopeContains(scope, "offline_access") {
		refreshRaw, refreshHash, err = auth.NewRefreshToken()
		if err != nil {
			writeOAuthError(w, 500, "server_error", "token generation failed")
			return
		}
	}
	familyID := nanoid.New()
	if oldRefreshHash != "" {
		if old, e := a.oauth.GetRefreshToken(ctx, oldRefreshHash); e == nil {
			familyID = old.FamilyID
		}
	}
	access := data.OAuthTokenRecord{TokenHash: accessHash, GrantID: grantID, ClientID: client.ClientID, UserID: userID, Scope: scope, ExpiresAt: time.Now().Add(a.cfg.OAuthAccessTokenTTL)}
	refresh := data.OAuthTokenRecord{TokenHash: refreshHash, GrantID: grantID, ClientID: client.ClientID, UserID: userID, Scope: scope, FamilyID: familyID, AuthTime: authTime, ExpiresAt: time.Now().Add(a.cfg.OAuthRefreshTokenTTL)}
	if oldRefreshHash != "" {
		if old, e := a.oauth.GetRefreshToken(ctx, oldRefreshHash); e == nil {
			refresh.ExpiresAt = old.ExpiresAt
		}
		err = a.oauth.RotateRefresh(ctx, oldRefreshHash, access, refresh)
	} else if refreshRaw != "" {
		err = a.oauth.CreateTokenPair(ctx, access, refresh)
	} else {
		err = a.oauth.CreateAccessToken(ctx, access)
	}
	if err != nil {
		writeOAuthError(w, 400, "invalid_grant", "token rotation failed")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	response := map[string]interface{}{"token_type": "Bearer", "access_token": accessRaw, "expires_in": int64(a.cfg.OAuthAccessTokenTTL.Seconds()), "scope": scope, "id_token": idToken}
	if refreshRaw != "" {
		response["refresh_token"] = refreshRaw
	}
	writeJSON(w, 200, response)
}

func (a *API) authenticateOAuthClient(ctx context.Context, r *http.Request) (*data.OAuthClient, bool) {
	clientID, secret, basic := r.BasicAuth()
	bodyClientID := r.FormValue("client_id")
	bodySecret := r.FormValue("client_secret")
	if basic && (bodyClientID != "" || bodySecret != "") {
		return nil, false
	}
	if !basic {
		clientID = bodyClientID
		secret = bodySecret
	}
	client, err := a.oauth.GetClient(ctx, clientID)
	if err != nil || !client.Active {
		return nil, false
	}
	if client.PublicClient {
		return client, !basic && secret == ""
	}
	return client, basic && secret != "" && auth.ConstantTimeEqual(client.SecretHash, auth.HashToken(secret))
}

func (a *API) handleOAuthUserInfo(w http.ResponseWriter, r *http.Request) {
	record, user, ok := a.oauthBearerUser(w, r)
	if !ok {
		return
	}
	out := map[string]interface{}{"sub": user.Ncuid}
	if auth.ScopeContains(record.Scope, "profile") {
		out["uid"] = user.UID
		out["name"] = user.DisplayName
		out["picture"] = user.AvatarURL
	}
	writeJSON(w, 200, out)
}

func (a *API) handleOAuthIntrospect(w http.ResponseWriter, r *http.Request) {
	if !parseOAuthForm(w, r) {
		writeJSON(w, 200, map[string]bool{"active": false})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	client, ok := a.authenticateOAuthClient(ctx, r)
	if !ok {
		writeOAuthError(w, 401, "invalid_client", "client authentication failed")
		return
	}
	record, err := a.oauth.GetAccessToken(ctx, auth.HashToken(r.FormValue("token")))
	if err != nil || record.ClientID != client.ClientID || record.RevokedAt.Valid || time.Now().After(record.ExpiresAt) {
		writeJSON(w, 200, map[string]bool{"active": false})
		return
	}
	user, err := a.users.GetByID(ctx, record.UserID)
	if err != nil {
		writeJSON(w, 200, map[string]bool{"active": false})
		return
	}
	banned, banErr := a.devices.IsUserBanned(ctx, user.ID)
	if banErr != nil || banned {
		writeJSON(w, 200, map[string]bool{"active": false})
		return
	}
	writeJSON(w, 200, map[string]interface{}{"active": true, "client_id": record.ClientID, "sub": user.Ncuid, "scope": record.Scope, "token_type": "Bearer", "exp": record.ExpiresAt.Unix()})
}

func (a *API) handleOAuthRevoke(w http.ResponseWriter, r *http.Request) {
	if !parseOAuthForm(w, r) {
		w.WriteHeader(200)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	client, ok := a.authenticateOAuthClient(ctx, r)
	if !ok {
		writeOAuthError(w, 401, "invalid_client", "client authentication failed")
		return
	}
	hash := auth.HashToken(r.FormValue("token"))
	if rec, err := a.oauth.GetAccessToken(ctx, hash); err == nil && rec.ClientID == client.ClientID {
		_ = a.oauth.RevokeToken(ctx, hash)
	} else if rec, err := a.oauth.GetRefreshToken(ctx, hash); err == nil && rec.ClientID == client.ClientID {
		_ = a.oauth.RevokeToken(ctx, hash)
	}
	w.WriteHeader(http.StatusOK)
}

func (a *API) oauthBearerUser(w http.ResponseWriter, r *http.Request) (*data.OAuthTokenRecord, *data.User, bool) {
	parts := strings.Fields(r.Header.Get("Authorization"))
	if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeOAuthError(w, 401, "invalid_token", "missing bearer token")
		return nil, nil, false
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	record, err := a.oauth.GetAccessToken(ctx, auth.HashToken(parts[1]))
	if err != nil || record.RevokedAt.Valid || time.Now().After(record.ExpiresAt) {
		writeOAuthError(w, 401, "invalid_token", "invalid token")
		return nil, nil, false
	}
	user, err := a.users.GetByID(ctx, record.UserID)
	if err != nil {
		writeOAuthError(w, 401, "invalid_token", "invalid user")
		return nil, nil, false
	}
	banned, banErr := a.devices.IsUserBanned(ctx, user.ID)
	if banErr != nil || banned {
		writeOAuthError(w, 401, "invalid_token", "account unavailable")
		return nil, nil, false
	}
	return record, user, true
}

func parseOAuthForm(w http.ResponseWriter, r *http.Request) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 32*1024)
	contentType := strings.ToLower(r.Header.Get("Content-Type"))
	if contentType != "" && !strings.HasPrefix(contentType, "application/x-www-form-urlencoded") {
		return false
	}
	return r.ParseForm() == nil
}

func redirectOAuth(w http.ResponseWriter, r *http.Request, target string, params map[string]string) {
	u, err := url.Parse(target)
	if err != nil {
		writeOAuthError(w, 400, "invalid_request", "invalid redirect")
		return
	}
	q := u.Query()
	for k, v := range params {
		q.Set(k, v)
	}
	u.RawQuery = q.Encode()
	http.Redirect(w, r, u.String(), http.StatusFound)
}
func setOAuthPageHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
}
func writeOAuthError(w http.ResponseWriter, status int, code, description string) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code, "error_description": description})
}
