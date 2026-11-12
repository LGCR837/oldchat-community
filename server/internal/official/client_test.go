package official

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

// 旧站偶发响应中途断开（表现为 HTTP/2 stream error），客户端需要重连重试。
func TestDoRetriesAfterInterruptedResponse(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&calls, 1) == 1 {
			hj, ok := w.(http.Hijacker)
			if !ok {
				t.Fatal("测试服务器不支持 hijack")
			}
			conn, _, err := hj.Hijack()
			if err != nil {
				t.Fatalf("hijack 失败: %v", err)
			}
			_ = conn.Close()
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"uid":"1001","display_name":"我"}`))
	}))
	defer srv.Close()

	client := New(srv.URL)
	profile, err := client.Me(context.Background())
	if err != nil {
		t.Fatalf("重试后仍失败: %v", err)
	}
	if profile.UID != "1001" || profile.DisplayName != "我" {
		t.Fatalf("响应解析异常: %+v", profile)
	}
	if n := atomic.LoadInt32(&calls); n != 2 {
		t.Fatalf("应当重试一次，实际请求 %d 次", n)
	}
}

// 业务错误（401）不该重试。
func TestDoDoesNotRetryUnauthorized(t *testing.T) {
	var calls int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
	}))
	defer srv.Close()

	client := New(srv.URL)
	if _, err := client.Me(context.Background()); err != ErrUnauthorized {
		t.Fatalf("应返回 ErrUnauthorized，实际 %v", err)
	}
	if n := atomic.LoadInt32(&calls); n != 1 {
		t.Fatalf("401 不应重试，实际请求 %d 次", n)
	}
}
