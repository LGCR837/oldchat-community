package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/data"
)

func TestMergeGroupEvents(t *testing.T) {
	msgs := []data.GroupMessage{
		{GroupSeq: 1, ID: "m1", Body: "hello"},
		{GroupSeq: 3, ID: "m3", Body: "third"},
	}
	events := []data.GroupEvent{
		{GroupSeq: 2, EventType: "GROUP_MESSAGE_RECALL", RefID: "m2"},
		{GroupSeq: 4, EventType: "GROUP_READ_CURSOR_UPDATE", RefID: "m4"},
	}
	merged := mergeGroupEvents(msgs, events, 10)
	if len(merged) != 4 {
		t.Fatalf("merged len = %d, want 4", len(merged))
	}
	// 严格升序
	for i := 1; i < len(merged); i++ {
		if merged[i].GroupSeq <= merged[i-1].GroupSeq {
			t.Fatalf("not strictly ascending at %d: %+v", i, merged[i])
		}
	}
	if merged[0].GroupSeq != 1 || merged[0].EventType != "MESSAGE_NEW" {
		t.Fatalf("merged[0] = %+v", merged[0])
	}
	if merged[1].GroupSeq != 2 || merged[1].EventType != "GROUP_MESSAGE_RECALL" {
		t.Fatalf("merged[1] = %+v", merged[1])
	}
	// limit 截断
	merged2 := mergeGroupEvents(msgs, events, 2)
	if len(merged2) != 2 {
		t.Fatalf("limited merged len = %d, want 2", len(merged2))
	}
}

func TestGroupEventsAfterHandlerMerge(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	ctx := context.Background()
	// 手动建最小 schema
	for _, q := range []string{
		`CREATE TABLE group_message_sequences (group_id VARCHAR(32) PRIMARY KEY, last_seq INTEGER NOT NULL DEFAULT 0)`,
		`CREATE TABLE group_messages (
			id VARCHAR(32) PRIMARY KEY, group_id VARCHAR(32) NOT NULL, group_seq INTEGER NULL,
			sender_id VARCHAR(32) NOT NULL, body TEXT NOT NULL, msg_type TEXT NOT NULL DEFAULT 'text',
			media_url TEXT NOT NULL DEFAULT '', thumb_url TEXT NOT NULL DEFAULT '', original_url TEXT NOT NULL DEFAULT '', duration_ms INTEGER NOT NULL DEFAULT 0,
			burn_after_seconds INTEGER NOT NULL DEFAULT 0, burn_start_at DATETIME NULL, burned_at DATETIME NULL,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE users (id VARCHAR(32) PRIMARY KEY, uid VARCHAR(32) UNIQUE NOT NULL, ncuid VARCHAR(32) NOT NULL DEFAULT '',
			email VARCHAR(255) UNIQUE NOT NULL, username VARCHAR(32) UNIQUE NOT NULL, display_name VARCHAR(64) NOT NULL DEFAULT '',
			password_hash TEXT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE groups (id VARCHAR(32) PRIMARY KEY, name VARCHAR(64) NOT NULL, avatar_url VARCHAR(512) NOT NULL DEFAULT '',
			owner_id VARCHAR(32) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`,
		`CREATE TABLE group_members (group_id VARCHAR(32) NOT NULL, user_id VARCHAR(32) NOT NULL, role SMALLINT NOT NULL,
			joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_read_at DATETIME NULL, PRIMARY KEY (group_id, user_id))`,
		`CREATE TABLE IF NOT EXISTS banned_groups (group_id VARCHAR(32) PRIMARY KEY, banned_until DATETIME NULL)`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	t.Cleanup(func() { _ = db.Close() })

	// 种子数据
	if _, err := db.Exec(`INSERT INTO users(id, uid, ncuid, email, username, password_hash) VALUES ('u1','U1','nc1','a@qq.com','user1','x')`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO users(id, uid, ncuid, email, username, password_hash) VALUES ('u2','U2','nc2','b@qq.com','user2','x')`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO groups(id, name, owner_id) VALUES ('g1','测试群','u1')`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO group_members(group_id, user_id, role) VALUES ('g1','u1',1),('g1','u2',0)`); err != nil {
		t.Fatal(err)
	}
	// 消息 seq=1
	groupMsgs := data.NewGroupMessageStore(db)
	if err := groupMsgs.Create(ctx, &data.GroupMessage{ID: "m1", GroupID: "g1", SenderID: "u1", Body: "第一条"}); err != nil {
		t.Fatal(err)
	}
	// 事件 seq=2
	groupEvents := data.NewGroupEventStore(db)
	if err := groupEvents.EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	if _, err := groupEvents.EmitGroupEvent(ctx, "g1", "GROUP_MESSAGE_RECALL", "m2", map[string]interface{}{"message_id": "m2"}); err != nil {
		t.Fatal(err)
	}

	api := &API{
		groups:      data.NewGroupStore(db),
		groupMsgs:   groupMsgs,
		groupEvents: groupEvents,
	}
	req := httptest.NewRequest(http.MethodGet, "/groups/events/after?group_id=g1&after_seq=0", nil)
	addTestUserClaims(req, "u2")
	rec := httptest.NewRecorder()
	api.handleGroupEventsAfter(rec, req)
	if rec.Code != 200 {
		t.Fatalf("code = %d body = %s", rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	if !strings.Contains(body, `"MESSAGE_NEW"`) || !strings.Contains(body, `"GROUP_MESSAGE_RECALL"`) {
		t.Fatalf("merged events missing types: %s", body)
	}
	if !strings.Contains(body, `"next_group_seq":2`) {
		t.Fatalf("next_group_seq wrong: %s", body)
	}
	// 事件顺序：seq1 消息在 seq2 事件前
	idxMsg := strings.Index(body, `"MESSAGE_NEW"`)
	idxRecall := strings.Index(body, `"GROUP_MESSAGE_RECALL"`)
	if idxMsg < 0 || idxRecall < 0 || idxMsg > idxRecall {
		t.Fatalf("event order wrong: msg=%d recall=%d", idxMsg, idxRecall)
	}

	// 非成员 403
	req2 := httptest.NewRequest(http.MethodGet, "/groups/events/after?group_id=g1&after_seq=0", nil)
	addTestUserClaims(req2, "u-other")
	rec2 := httptest.NewRecorder()
	api.handleGroupEventsAfter(rec2, req2)
	if rec2.Code != 403 {
		t.Fatalf("non-member code = %d", rec2.Code)
	}
}

// 确保 groupEventsAfterResponse 可 JSON 序列化（payload RawMessage 兼容）。
func TestGroupEventEnvelopeJSON(t *testing.T) {
	raw := json.RawMessage(`{"a":1}`)
	env := groupEventEnvelope{GroupSeq: 1, EventType: "MESSAGE_NEW", Payload: raw}
	out, err := json.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(out), `"a":1`) {
		t.Fatalf("payload not preserved: %s", out)
	}
}
