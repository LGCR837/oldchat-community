package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"
	"metrochat/internal/auth"
	"metrochat/internal/config"
	"metrochat/internal/data"
)

func newAIProxyTestAPI(t *testing.T, upstream http.Handler) (*API, *sqlx.DB, *httptest.Server) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`CREATE TABLE ai_token_quotas (user_id TEXT PRIMARY KEY, token_limit INTEGER NOT NULL DEFAULT 0, used_tokens INTEGER NOT NULL DEFAULT 0, reserved_tokens INTEGER NOT NULL DEFAULT 0, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`)
	if err != nil {
		t.Fatal(err)
	}
	// 测试用户显式授予额度（生产默认 0，测试需有额度验证结算逻辑）。
	_, err = db.Exec(`INSERT INTO ai_token_quotas (user_id, token_limit) VALUES ('u1', 5000000), ('u2', 5000000)`)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(upstream)
	t.Cleanup(func() { server.Close(); db.Close() })
	return &API{cfg: config.Config{OpenCodeGoAPIKey: "server-secret", OpenCodeGoEndpoint: server.URL, OpenCodeGoModel: "deepseek-v4-flash"}, aiQuotas: data.NewAIQuotaStore(db), aiProxyClient: server.Client()}, db, server
}

func withAIClaims(req *http.Request, userID string) *http.Request {
	return req.WithContext(context.WithValue(req.Context(), claimsKey, &auth.AccessClaims{RegisteredClaims: jwt.RegisteredClaims{Subject: userID}}))
}

func TestAIProxyForcesModelStreamsAndSettlesUsage(t *testing.T) {
	upstream := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer server-secret" {
			t.Errorf("missing server auth")
		}
		var body map[string]interface{}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if body["model"] != "deepseek-v4-flash" {
			t.Errorf("model was not forced: %v", body["model"])
		}
		options, _ := body["stream_options"].(map[string]interface{})
		if options["include_usage"] != true {
			t.Errorf("usage was not requested")
		}
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"))
		_, _ = w.Write([]byte("data: {\"choices\":[],\"usage\":{\"total_tokens\":42}}\n\n"))
		_, _ = w.Write([]byte("data: [DONE]\n\n"))
	})
	api, _, _ := newAIProxyTestAPI(t, upstream)
	req := httptest.NewRequest(http.MethodPost, "/v1/ai/chat/completions", strings.NewReader(`{"model":"evil","stream":true,"messages":[{"role":"user","content":"hello"}]}`))
	rec := httptest.NewRecorder()
	api.handleAIChatCompletions(rec, withAIClaims(req, "u1"))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "[DONE]") {
		t.Fatalf("unexpected response %d: %s", rec.Code, rec.Body.String())
	}
	quota, err := api.aiQuotas.Get(context.Background(), "u1")
	if err != nil {
		t.Fatal(err)
	}
	if quota.UsedTokens != 42 || quota.ReservedTokens != 0 {
		t.Fatalf("usage not settled: %+v", quota)
	}
}

func TestAIProxyRefundsReservationOnUpstreamFailure(t *testing.T) {
	api, _, _ := newAIProxyTestAPI(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "private upstream error", http.StatusTooManyRequests)
	}))
	req := httptest.NewRequest(http.MethodPost, "/v1/ai/chat/completions", strings.NewReader(`{"messages":[{"role":"user","content":"hello"}]}`))
	rec := httptest.NewRecorder()
	api.handleAIChatCompletions(rec, withAIClaims(req, "u2"))
	if rec.Code != http.StatusBadGateway || strings.Contains(rec.Body.String(), "private upstream error") {
		t.Fatalf("upstream error leaked: %d %s", rec.Code, rec.Body.String())
	}
	quota, _ := api.aiQuotas.Get(context.Background(), "u2")
	if quota.UsedTokens != 0 || quota.ReservedTokens != 0 {
		t.Fatalf("reservation not refunded: %+v", quota)
	}
}
