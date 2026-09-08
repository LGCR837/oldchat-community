package httpapi

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 测试 gateway 分发：真实 handler 应被正确调用并返回包装响应。
func TestV2GatewayDispatchUpdates(t *testing.T) {
	api := newPtsTestAPI(t)

	// 先制造一条事件
	api.pts.Emit(context.Background(), "user-1", "DIRECT_MESSAGE_NEW", "m1", map[string]interface{}{"id": "m1"})

	// 构造 gateway 请求：body 是加密后的明文（此处直接给明文，模拟 secureMiddleware 解密后）
	body := `{"m":"GET","p":"/v2/updates/difference","q":"pts=0&limit=10"}`
	req := httptest.NewRequest(http.MethodPost, "/v2/gateway", bytes.NewBufferString(body))
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleV2Gateway(rec, req)

	if rec.Code != 200 {
		t.Fatalf("gateway code = %d body=%s", rec.Code, rec.Body.String())
	}
	// 响应是包装格式 {"code":200,"body":...}
	resp := rec.Body.String()
	if !strings.Contains(resp, `"code":200`) {
		t.Fatalf("gateway wrapper missing code: %s", resp)
	}
	if !strings.Contains(resp, `"body"`) {
		t.Fatalf("gateway wrapper missing body: %s", resp)
	}
	if !strings.Contains(resp, `"type":"DIRECT_MESSAGE_NEW"`) {
		t.Fatalf("gateway inner body missing event: %s", resp)
	}
}

// 测试 gateway 白名单：非 /v2/ 路径拒绝。
func TestV2GatewayRejectsNonV2Path(t *testing.T) {
	api := newPtsTestAPI(t)
	body := `{"m":"GET","p":"/v1/auth/login","q":""}`
	req := httptest.NewRequest(http.MethodPost, "/v2/gateway", bytes.NewBufferString(body))
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleV2Gateway(rec, req)
	if rec.Code != 400 {
		t.Fatalf("should reject non-v2 path, code=%d", rec.Code)
	}
}

// 测试 gateway：未知 /v2 路径 404。
func TestV2GatewayUnknownPath(t *testing.T) {
	api := newPtsTestAPI(t)
	body := `{"m":"GET","p":"/v2/not/exist","q":""}`
	req := httptest.NewRequest(http.MethodPost, "/v2/gateway", bytes.NewBufferString(body))
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleV2Gateway(rec, req)
	if rec.Code != 200 {
		t.Fatalf("wrapper should return 200 with inner 404, code=%d body=%s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), `"code":404`) {
		t.Fatalf("inner 404 missing: %s", rec.Body.String())
	}
}

// 测试 gateway：坏 body。
func TestV2GatewayBadBody(t *testing.T) {
	api := newPtsTestAPI(t)
	req := httptest.NewRequest(http.MethodPost, "/v2/gateway", bytes.NewBufferString("not json"))
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleV2Gateway(rec, req)
	if rec.Code != 400 {
		t.Fatalf("bad body should 400, code=%d", rec.Code)
	}
}
