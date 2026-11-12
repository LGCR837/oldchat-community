package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"metrochat/internal/config"
)

func newCorsTestAPI(enabled bool, origins []string) *API {
	return &API{cfg: config.Config{CorsEnabled: enabled, CorsAllowedOrigins: origins}}
}

func serveCors(t *testing.T, api *API, method, path, origin string) *httptest.ResponseRecorder {
	t.Helper()
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	req := httptest.NewRequest(method, path, nil)
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	if method == http.MethodOptions {
		req.Header.Set("Access-Control-Request-Method", http.MethodPost)
		req.Header.Set("Access-Control-Request-Headers", "authorization, content-type")
	}
	rec := httptest.NewRecorder()
	api.corsMiddleware(next).ServeHTTP(rec, req)
	return rec
}

func TestCorsPreflightShortCircuits(t *testing.T) {
	api := newCorsTestAPI(true, nil)
	rec := serveCors(t, api, http.MethodOptions, "/v1/auth/login", "https://web.example.com")

	if rec.Code != http.StatusNoContent {
		t.Fatalf("预检状态码 = %d，期望 204", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "https://web.example.com" {
		t.Fatalf("Allow-Origin = %q", got)
	}
	if got := rec.Header().Get("Access-Control-Allow-Methods"); got == "" {
		t.Fatal("缺少 Allow-Methods")
	}
	// 回显请求头，保证 X-Enc / X-Sign 这类自定义头不被拦。
	if got := rec.Header().Get("Access-Control-Allow-Headers"); got != "authorization, content-type" {
		t.Fatalf("Allow-Headers = %q", got)
	}
	if rec.Body.Len() != 0 {
		t.Fatalf("预检不应返回正文，实际 %d 字节", rec.Body.Len())
	}
}

func TestCorsSimpleRequestExposesHeaders(t *testing.T) {
	api := newCorsTestAPI(true, nil)
	rec := serveCors(t, api, http.MethodGet, "/v2/channels/state", "https://web.example.com")

	if rec.Code != http.StatusOK {
		t.Fatalf("状态码 = %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "https://web.example.com" {
		t.Fatalf("Allow-Origin = %q", got)
	}
	if got := rec.Header().Get("Access-Control-Expose-Headers"); got == "" {
		t.Fatal("缺少 Expose-Headers")
	}
	if got := rec.Header().Get("Vary"); got != "Origin" {
		t.Fatalf("Vary = %q", got)
	}
}

func TestCorsSkipsCookieBackedPages(t *testing.T) {
	api := newCorsTestAPI(true, nil)
	for _, path := range []string{"/admin/users", "/channel-manage", "/oauth/token", "/"} {
		rec := serveCors(t, api, http.MethodPost, path, "https://web.example.com")
		if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
			t.Fatalf("%s 不应开放跨域，实际 Allow-Origin=%q", path, got)
		}
	}
}

func TestCorsRespectsAllowList(t *testing.T) {
	api := newCorsTestAPI(true, []string{"https://a.example.com"})

	allowed := serveCors(t, api, http.MethodGet, "/v1/me", "https://a.example.com")
	if got := allowed.Header().Get("Access-Control-Allow-Origin"); got != "https://a.example.com" {
		t.Fatalf("白名单来源应放行，实际 %q", got)
	}

	denied := serveCors(t, api, http.MethodGet, "/v1/me", "https://b.example.com")
	if got := denied.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("非白名单来源不应放行，实际 %q", got)
	}
	// 被拒来源的预检也要给 204，避免和 405 混淆。
	preflight := serveCors(t, api, http.MethodOptions, "/v1/me", "https://b.example.com")
	if preflight.Code != http.StatusNoContent {
		t.Fatalf("被拒来源预检状态码 = %d", preflight.Code)
	}
	if got := preflight.Header().Get("Access-Control-Allow-Methods"); got != "" {
		t.Fatalf("被拒来源不应返回 Allow-Methods，实际 %q", got)
	}
}

func TestCorsDisabled(t *testing.T) {
	api := newCorsTestAPI(false, nil)
	rec := serveCors(t, api, http.MethodGet, "/v1/me", "https://web.example.com")
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("关闭后不应返回 Allow-Origin，实际 %q", got)
	}
}

func TestCorsIgnoresSameOriginAndBareOptions(t *testing.T) {
	api := newCorsTestAPI(true, nil)

	noOrigin := serveCors(t, api, http.MethodGet, "/v1/me", "")
	if got := noOrigin.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("无 Origin 不应返回 Allow-Origin，实际 %q", got)
	}

	// 没有 Access-Control-Request-Method 的 OPTIONS 属于普通请求，交给路由。
	req := httptest.NewRequest(http.MethodOptions, "/v1/me", nil)
	req.Header.Set("Origin", "https://web.example.com")
	rec := httptest.NewRecorder()
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusMethodNotAllowed) })
	api.corsMiddleware(next).ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("裸 OPTIONS 应透传给路由，实际 %d", rec.Code)
	}
}

func TestCorsPathAllowed(t *testing.T) {
	cases := map[string]bool{
		"/v1":             true,
		"/v1/me":          true,
		"/v2/gateway":     true,
		"/ws":             true,
		"/download/oldchat.apk": true,
		"/admin":          false,
		"/channel-manage": false,
		"/":               false,
	}
	for path, want := range cases {
		if got := corsPathAllowed(path); got != want {
			t.Fatalf("corsPathAllowed(%q) = %v，期望 %v", path, got, want)
		}
	}
}
