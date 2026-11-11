package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"metrochat/internal/ratelimit"
)

func TestStatusPageAndAPIFiltersAdminRoutes(t *testing.T) {
	api := &API{
		systemStatusLimiter: ratelimit.NewLimiter(1.0/3.0, 1),
	}

	// 记录几条指标（包含普通 API 与管理 API）
	globalMetrics.Record(http.MethodGet, "/v1/test/status", http.StatusOK, 2000000, "127.0.0.1", "test-ua")
	globalMetrics.Record(http.MethodPost, "/v1/test/error", http.StatusBadRequest, 5000000, "127.0.0.1", "test-ua")
	globalMetrics.Record(http.MethodGet, "/admins/moderation", http.StatusOK, 10000000, "127.0.0.1", "test-ua")
	globalMetrics.Record(http.MethodGet, "/v1/admin/metrics/overview", http.StatusOK, 3000000, "127.0.0.1", "test-ua")

	// 测试 HTML 页面（确保不暴露 /admins 导航链接）
	recPage := httptest.NewRecorder()
	reqPage := httptest.NewRequest(http.MethodGet, "/status", nil)
	api.handleStatusPage(recPage, reqPage)

	if recPage.Code != http.StatusOK {
		t.Fatalf("handleStatusPage status code = %d, want 200", recPage.Code)
	}
	bodyPage := recPage.Body.String()
	if strings.Contains(bodyPage, `href="/admins"`) {
		t.Fatalf("status page should NOT contain href=/admins link")
	}

	// 测试 JSON API 第一次请求（成功 200）
	recAPI := httptest.NewRecorder()
	reqAPI := httptest.NewRequest(http.MethodGet, "/v1/system/status", nil)
	reqAPI.RemoteAddr = "192.168.1.100:12345"
	api.handleStatusDataAPI(recAPI, reqAPI)

	if recAPI.Code != http.StatusOK {
		t.Fatalf("handleStatusDataAPI status code = %d, want 200", recAPI.Code)
	}
	var data systemStatusJSON
	if err := json.Unmarshal(recAPI.Body.Bytes(), &data); err != nil {
		t.Fatalf("failed to decode JSON: %v", err)
	}
	for _, r := range data.Routes {
		if strings.HasPrefix(r.Path, "/admin") || strings.HasPrefix(r.Path, "/admins") || strings.HasPrefix(r.Path, "/v1/admin") {
			t.Fatalf("routes should NOT contain admin path, got %q", r.Path)
		}
	}

	// 测试同 IP 立即第二次请求（触发 429 限流）
	recAPI2 := httptest.NewRecorder()
	reqAPI2 := httptest.NewRequest(http.MethodGet, "/v1/system/status", nil)
	reqAPI2.RemoteAddr = "192.168.1.100:12345"
	api.handleStatusDataAPI(recAPI2, reqAPI2)

	if recAPI2.Code != http.StatusTooManyRequests {
		t.Fatalf("handleStatusDataAPI rate limit status code = %d, want 429", recAPI2.Code)
	}

	// 测试不同 IP 请求（成功 200）
	recAPI3 := httptest.NewRecorder()
	reqAPI3 := httptest.NewRequest(http.MethodGet, "/v1/system/status", nil)
	reqAPI3.RemoteAddr = "192.168.1.101:12345"
	api.handleStatusDataAPI(recAPI3, reqAPI3)

	if recAPI3.Code != http.StatusOK {
		t.Fatalf("handleStatusDataAPI another IP status code = %d, want 200", recAPI3.Code)
	}
}
