package migrate

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// 旧站好友列表中途断开时，迁移不应整体失败：跳过私聊，群聊与朋友圈继续。
func TestRunContinuesWhenFriendListBreaks(t *testing.T) {
	db := newTestDB(t)
	userID := seedLocalUser(t, db)

	oldDelay := friendsRetryDelay
	friendsRetryDelay = 0
	t.Cleanup(func() { friendsRetryDelay = oldDelay })

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/auth/login", func(w http.ResponseWriter, r *http.Request) {
		writeTestJSON(w, map[string]interface{}{
			"access_token": "token",
			"user":         map[string]interface{}{"uid": "1001", "display_name": "我"},
		})
	})
	mux.HandleFunc("/v1/me", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{"uid": "1001", "display_name": "我"})
	})
	mux.HandleFunc("/v1/friends", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		hj, ok := w.(http.Hijacker)
		if !ok {
			t.Error("测试服务器不支持 hijack")
			return
		}
		conn, _, err := hj.Hijack()
		if err != nil {
			t.Errorf("hijack 失败: %v", err)
			return
		}
		_ = conn.Close()
	})
	mux.HandleFunc("/v1/groups/list", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{"groups": []interface{}{}})
	})
	mux.HandleFunc("/v1/moments/user", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{"moments": []interface{}{}})
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	svc := NewService(db, Options{SourceHost: srv.URL, IncludeGroups: true})
	job, err := svc.Store().CreateJob(context.Background(), userID, "1001", srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := svc.Run(ctx, job.ID, Credentials{Username: "me", Password: "good-pass"}); err != nil {
		t.Fatalf("好友列表失败不应让整个迁移失败: %v", err)
	}

	got, err := svc.Store().GetJob(context.Background(), job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.Status != StatusDone {
		t.Fatalf("任务状态应为 done，实际 %s（%s）", got.Status, got.Error)
	}
	var warned bool
	events, err := svc.Store().ListEvents(context.Background(), job.ID, 500)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range events {
		if e.Level == "warn" && e.Stage == "好友" {
			warned = true
		}
	}
	if !warned {
		t.Fatal("应当记录一条好友列表失败的警告")
	}
}
