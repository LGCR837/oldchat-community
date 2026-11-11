package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func TestChatContextAPI(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("打开内存数据库失败: %v", err)
	}
	defer db.Close()

	if _, err := db.Exec(`
	CREATE TABLE users (
		id VARCHAR(32) PRIMARY KEY,
		uid VARCHAR(32) NOT NULL DEFAULT '',
		username VARCHAR(32) NOT NULL DEFAULT '',
		display_name VARCHAR(64) NOT NULL DEFAULT ''
	);
	CREATE TABLE group_messages (
		id VARCHAR(32) PRIMARY KEY,
		group_id VARCHAR(32) NOT NULL DEFAULT '',
		sender_id VARCHAR(32) NOT NULL DEFAULT '',
		msg_type VARCHAR(32) NOT NULL DEFAULT 'text',
		body TEXT NOT NULL DEFAULT '',
		media_url TEXT NOT NULL DEFAULT '',
		thumb_url TEXT NOT NULL DEFAULT '',
		group_seq INTEGER NOT NULL DEFAULT 0,
		created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
	);
	`); err != nil {
		t.Fatalf("建表失败: %v", err)
	}

	_, _ = db.Exec(`INSERT INTO users (id, uid, username, display_name) VALUES ('u1', 'USR-1', 'alice', '爱丽丝'), ('u2', 'USR-2', 'bob', '鲍勃')`)
	for i := 1; i <= 25; i++ {
		_, _ = db.Exec(`INSERT INTO group_messages (id, group_id, sender_id, msg_type, body, group_seq, created_at) VALUES (?, 'GRP-TEST', ?, 'text', ?, ?, ?)`,
			"msg-"+strconvItoa(i), "u1", "消息 "+strconvItoa(i), i, time.Now().Add(time.Duration(i)*time.Second))
	}

	api := &API{
		db:                db,
		adminSessions:     newAdminSessions(),
		moderatorSessions: newModeratorSessions(),
	}

	// 1. 未鉴权请求
	req := httptest.NewRequest("GET", "/admins/moderation/chat-context?group_id=GRP-TEST&msg_id=msg-15", nil)
	rec := httptest.NewRecorder()
	api.handleAdminModerationChatContext(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("未登录期望返回 401，实际返回: %d", rec.Code)
	}

	// 2. 超级管理员登录
	adminToken := api.adminSessions.New(1 * time.Hour)
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: adminToken})
	rec = httptest.NewRecorder()
	api.handleAdminModerationChatContext(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("已登录期望返回 200，实际返回: %d, body: %s", rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	if !containsStr(body, "msg-15") || !containsStr(body, "爱丽丝") {
		t.Fatalf("返回内容应包含目标消息与发送者，实际返回: %s", body)
	}
}

func strconvItoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b []byte
	neg := false
	if i < 0 {
		neg = true
		i = -i
	}
	for i > 0 {
		b = append([]byte{byte('0' + i%10)}, b...)
		i /= 10
	}
	if neg {
		b = append([]byte{'-'}, b...)
	}
	return string(b)
}

func containsStr(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(substr) == 0 || (len(s) > 0 && len(substr) > 0 && indexStr(s, substr) >= 0))
}

func indexStr(s, substr string) int {
	for i := 0; i+len(substr) <= len(s); i++ {
		if s[i:i+len(substr)] == substr {
			return i
		}
	}
	return -1
}
