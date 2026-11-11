package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"

	"metrochat/internal/auth"
	"metrochat/internal/data"
	"metrochat/internal/ws"
)

func TestSystemNotificationTargetedAndBroadcast(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("failed to open test db: %v", err)
	}
	defer db.Close()

	if _, err := db.Exec(`
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    uid TEXT UNIQUE NOT NULL,
    ncuid TEXT NOT NULL DEFAULT '',
    uid_changed_at TIMESTAMP,
    email TEXT UNIQUE NOT NULL,
    username TEXT UNIQUE NOT NULL,
    display_name TEXT NOT NULL,
    avatar_url TEXT NOT NULL DEFAULT '',
    signature TEXT NOT NULL DEFAULT '',
    cover_url TEXT NOT NULL DEFAULT '',
    password_hash TEXT NOT NULL,
    token_version INTEGER NOT NULL DEFAULT 0,
    coin_balance INTEGER NOT NULL DEFAULT 0,
    reputation_score INTEGER NOT NULL DEFAULT 110,
    user_title TEXT NOT NULL DEFAULT '',
    user_title_price INTEGER NOT NULL DEFAULT 0,
    presence_status TEXT NOT NULL DEFAULT '',
    ban_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS system_notifications (
    id TEXT PRIMARY KEY,
    target_user_id TEXT NOT NULL DEFAULT '',
    title TEXT NOT NULL DEFAULT '',
    body TEXT NOT NULL,
    important INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
`); err != nil {
		t.Fatalf("failed to create tables: %v", err)
	}

	userStore := data.NewUserStore(db)
	notifStore := data.NewNotificationStore(db)
	hub := ws.NewHub()
	api := &API{
		db:            db,
		users:         userStore,
		notifications: notifStore,
		wsHub:         hub,
	}

	ctx := context.Background()

	// 1. 创建测试用户 UserA 和 UserB
	userA := &data.User{
		ID:           "USER_A_ID",
		UID:          "user_a",
		Email:        "a@test.com",
		Username:     "usera",
		DisplayName:  "User A",
		PasswordHash: "hash",
	}
	if err := userStore.Create(ctx, userA); err != nil {
		t.Fatalf("create userA failed: %v", err)
	}

	userB := &data.User{
		ID:           "USER_B_ID",
		UID:          "user_b",
		Email:        "b@test.com",
		Username:     "userb",
		DisplayName:  "User B",
		PasswordHash: "hash",
	}
	if err := userStore.Create(ctx, userB); err != nil {
		t.Fatalf("create userB failed: %v", err)
	}

	// 2. 发送一条全员广播通知
	if err := api.sendSystemNotification(ctx, "", "全员通知", "这是一条全员广播", false); err != nil {
		t.Fatalf("send broadcast failed: %v", err)
	}
	time.Sleep(10 * time.Millisecond)

	// 3. 发送一条给 UserA 的定向通知（如信誉分变更）
	if err := api.sendSystemNotification(ctx, userA.ID, "信誉分变更通知", "您的信誉分恢复为 120", true); err != nil {
		t.Fatalf("send userA notif failed: %v", err)
	}
	time.Sleep(10 * time.Millisecond)

	// 4. 发送一条给 UserB 的定向通知（如举报审核结果）
	if err := api.sendSystemNotification(ctx, userB.ID, "举报审核结果通知", "您的举报已处理完毕", false); err != nil {
		t.Fatalf("send userB notif failed: %v", err)
	}

	// 5. 验证 UserA 拉取通知
	{
		req := httptest.NewRequest(http.MethodGet, "/v1/notifications", nil)
		claims := &auth.AccessClaims{RegisteredClaims: jwt.RegisteredClaims{Subject: userA.ID}}
		req = req.WithContext(context.WithValue(req.Context(), claimsKey, claims))
		w := httptest.NewRecorder()

		api.handleNotificationList(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("userA get notifications status: %d", w.Code)
		}

		var resp struct {
			Notifications []struct {
				ID           string `json:"id"`
				TargetUserID string `json:"target_user_id"`
				Title        string `json:"title"`
				Body         string `json:"body"`
			} `json:"notifications"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("unmarshal userA resp failed: %v", err)
		}

		if len(resp.Notifications) != 2 {
			t.Fatalf("expected 2 notifications for UserA, got %d", len(resp.Notifications))
		}
		for _, n := range resp.Notifications {
			if n.TargetUserID != "" && n.TargetUserID != userA.ID {
				t.Fatalf("UserA received unexpected notif for user: %s", n.TargetUserID)
			}
		}
	}

	// 6. 验证 UserB 拉取通知
	{
		req := httptest.NewRequest(http.MethodGet, "/v1/notifications", nil)
		claims := &auth.AccessClaims{RegisteredClaims: jwt.RegisteredClaims{Subject: userB.ID}}
		req = req.WithContext(context.WithValue(req.Context(), claimsKey, claims))
		w := httptest.NewRecorder()

		api.handleNotificationList(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("userB get notifications status: %d", w.Code)
		}

		var resp struct {
			Notifications []struct {
				ID           string `json:"id"`
				TargetUserID string `json:"target_user_id"`
				Title        string `json:"title"`
				Body         string `json:"body"`
			} `json:"notifications"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("unmarshal userB resp failed: %v", err)
		}

		if len(resp.Notifications) != 2 {
			t.Fatalf("expected 2 notifications for UserB, got %d", len(resp.Notifications))
		}
		for _, n := range resp.Notifications {
			if n.TargetUserID != "" && n.TargetUserID != userB.ID {
				t.Fatalf("UserB received unexpected notif for user: %s", n.TargetUserID)
			}
		}
	}

	// 7. 验证未登录/匿名用户只收到全员广播通知
	{
		req := httptest.NewRequest(http.MethodGet, "/v1/notifications", nil)
		w := httptest.NewRecorder()

		api.handleNotificationList(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("anon get notifications status: %d", w.Code)
		}

		var resp struct {
			Notifications []struct {
				ID           string `json:"id"`
				TargetUserID string `json:"target_user_id"`
				Title        string `json:"title"`
				Body         string `json:"body"`
			} `json:"notifications"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
			t.Fatalf("unmarshal anon resp failed: %v", err)
		}

		if len(resp.Notifications) != 1 {
			t.Fatalf("expected 1 broadcast notif for anon, got %d", len(resp.Notifications))
		}
		if resp.Notifications[0].Title != "全员通知" {
			t.Fatalf("expected '全员通知', got '%s'", resp.Notifications[0].Title)
		}
	}
}
