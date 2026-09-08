package httpapi

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"metrochat/internal/data"
)

func TestIsRejectedLegacyPayloadRejectsStructuredStringVersion(t *testing.T) {
	body := `{"v":"2","text":"[聊天记录]","forward_v2":{"title":"聊天记录","items":[{"sid":"abc"}]}}`
	if !isRejectedLegacyPayload(body) {
		t.Fatalf("expected structured payload with string version to be rejected")
	}
}

func TestSanitizeMessageBodyForSendRejectsStructuredStringVersion(t *testing.T) {
	api := &API{}
	body := `{"v":"2","text":"[聊天记录]","forward_v2":{"title":"聊天记录","items":[{"sid":"abc"}]}}`
	cleaned, ok := api.sanitizeMessageBodyForSend(context.Background(), "SENDER001", "", "text", body)
	if ok {
		t.Fatalf("expected sanitize to reject payload, got ok with body=%q", cleaned)
	}
}

func TestSanitizeMessageBodyForSendRejectsLegacyForward(t *testing.T) {
	api := &API{}
	body := `{"v":1,"text":"[聊天记录] 共1条","forward":{"title":"聊天记录","items":[{"source_message_id":"abc"}]}}`
	if _, ok := api.sanitizeMessageBodyForSend(context.Background(), "SENDER001", "", "text", body); ok {
		t.Fatalf("expected legacy forward payload to be rejected")
	}
}

func TestSanitizeMessageBodyForSendKeepsUnstructuredJSONText(t *testing.T) {
	api := &API{}
	body := `{"hello":"world"}`
	cleaned, ok := api.sanitizeMessageBodyForSend(context.Background(), "SENDER001", "", "text", body)
	if !ok {
		t.Fatalf("expected unstructured json text to pass")
	}
	if cleaned != body {
		t.Fatalf("expected body unchanged, got %q", cleaned)
	}
}

func TestSanitizeMessageBodyForSendReplacesDirectBurnQuotePreview(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	createPayloadGuardTestUser(t, api, "user-sender", "SENDER001", "发送者")
	createPayloadGuardTestUser(t, api, "user-viewer", "VIEWER001", "查看者")
	threadID, err := api.direct.GetOrCreateThread(ctx, "user-sender", "user-viewer", "thread-1")
	if err != nil {
		t.Fatalf("create thread: %v", err)
	}
	err = api.direct.CreateMessage(ctx, &data.DirectMessage{
		ID:               "direct-burn-1",
		ThreadID:         threadID,
		SenderID:         "user-sender",
		Body:             "真正的阅后即焚内容",
		MsgType:          "text",
		BurnAfterSeconds: 30,
	})
	if err != nil {
		t.Fatalf("create direct burn message: %v", err)
	}
	body := `{"v":2,"text":"回复一下","quote":{"id":"direct-burn-1"}}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "user-viewer", "", "text", body)
	if !ok {
		t.Fatalf("expected direct burn quote payload to pass")
	}
	assertBurnReferenceSanitized(t, cleaned)
}

func TestSanitizeMessageBodyForSendReplacesGroupBurnQuotePreview(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	createPayloadGuardTestUser(t, api, "group-owner", "OWNER001", "群主")
	createPayloadGuardTestUser(t, api, "group-member", "MEMBER001", "成员")
	err := api.groups.Create(ctx, &data.Group{ID: "group-1", Name: "测试群"}, "group-owner")
	if err != nil {
		t.Fatalf("create group: %v", err)
	}
	if err = api.groups.AddMember(ctx, "group-1", "group-member", data.GroupRoleMember); err != nil {
		t.Fatalf("add member: %v", err)
	}
	err = api.groupMsgs.Create(ctx, &data.GroupMessage{
		ID:               "group-burn-1",
		GroupID:          "group-1",
		SenderID:         "group-owner",
		Body:             "群里的阅后即焚内容",
		MsgType:          "text",
		BurnAfterSeconds: 45,
	})
	if err != nil {
		t.Fatalf("create group burn message: %v", err)
	}
	body := `{"v":2,"text":"群里回复","quote":{"id":"group-burn-1"}}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "group-member", "group-1", "text", body)
	if !ok {
		t.Fatalf("expected group burn quote payload to pass")
	}
	assertBurnReferenceSanitized(t, cleaned)
}

func TestSanitizeMessageBodyForSendReplacesBurnForwardPreview(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	createPayloadGuardTestUser(t, api, "forward-sender", "FORWARD001", "转发源")
	createPayloadGuardTestUser(t, api, "forward-viewer", "FORWARD002", "转发者")
	threadID, err := api.direct.GetOrCreateThread(ctx, "forward-sender", "forward-viewer", "thread-2")
	if err != nil {
		t.Fatalf("create thread: %v", err)
	}
	err = api.direct.CreateMessage(ctx, &data.DirectMessage{
		ID:               "direct-burn-forward-1",
		ThreadID:         threadID,
		SenderID:         "forward-sender",
		Body:             "不能出现在转发摘要里",
		MsgType:          "text",
		BurnAfterSeconds: 60,
	})
	if err != nil {
		t.Fatalf("create direct burn message: %v", err)
	}
	body := `{"v":2,"text":"[聊天记录]","forward_v2":{"title":"聊天记录","items":[{"sid":"direct-burn-forward-1"}]}}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "forward-viewer", "", "text", body)
	if !ok {
		t.Fatalf("expected burn forward payload to pass")
	}
	assertBurnReferenceSanitized(t, cleaned)
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		t.Fatalf("parse sanitized forward payload failed: %s", cleaned)
	}
	if payload.Forward == nil || len(payload.Forward.Items) != 1 {
		t.Fatalf("expected one forward item, got %#v", payload.Forward)
	}
	if payload.Forward.Items[0].Text != burnLockedReferencePreview {
		t.Fatalf("expected burn forward item preview %q, got %q", burnLockedReferencePreview, payload.Forward.Items[0].Text)
	}
}

func newPayloadGuardTestAPI(t *testing.T) *API {
	t.Helper()
	serverRoot := chdirToServerRoot(t)
	db, err := data.OpenDB(context.Background(), filepath.Join(t.TempDir(), "payload-guard-test.db"))
	if err != nil {
		t.Fatalf("open test db from %s: %v", serverRoot, err)
	}
	t.Cleanup(func() {
		_ = db.Close()
	})
	return &API{
		db:        db,
		users:     data.NewUserStore(db),
		direct:    data.NewDirectStore(db),
		groups:    data.NewGroupStore(db),
		groupMsgs: data.NewGroupMessageStore(db),
	}
}

func chdirToServerRoot(t *testing.T) string {
	t.Helper()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	dir := wd
	for {
		if _, err = os.Stat(filepath.Join(dir, "schema.sql")); err == nil {
			if chdirErr := os.Chdir(dir); chdirErr != nil {
				t.Fatalf("chdir %s: %v", dir, chdirErr)
			}
			t.Cleanup(func() {
				_ = os.Chdir(wd)
			})
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("schema.sql not found from %s", wd)
		}
		dir = parent
	}
}

func createPayloadGuardTestUser(t *testing.T, api *API, id, uid, displayName string) {
	t.Helper()
	ctx := context.Background()
	user := &data.User{
		ID:           id,
		UID:          uid,
		Email:        strings.ToLower(uid) + "@example.com",
		Username:     strings.ToLower(uid),
		DisplayName:  displayName,
		PasswordHash: "hash",
	}
	if err := api.users.Create(ctx, user); err != nil {
		t.Fatalf("create user %s: %v", uid, err)
	}
}

func assertBurnReferenceSanitized(t *testing.T, cleaned string) {
	t.Helper()
	if strings.Contains(cleaned, "真正的阅后即焚内容") || strings.Contains(cleaned, "群里的阅后即焚内容") || strings.Contains(cleaned, "不能出现在转发摘要里") {
		t.Fatalf("sanitized payload leaked burn content: %s", cleaned)
	}
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		t.Fatalf("parse sanitized payload failed: %s", cleaned)
	}
	if payload.Quote != nil {
		if payload.Quote.Text != burnLockedReferencePreview {
			t.Fatalf("expected burn quote preview %q, got %q", burnLockedReferencePreview, payload.Quote.Text)
		}
		if payload.Quote.ThumbURL != "" {
			t.Fatalf("expected burn quote thumb to be cleared, got %q", payload.Quote.ThumbURL)
		}
	}
}

func createPayloadGuardTestUserWithNcuid(t *testing.T, api *API, id, uid, ncuid, displayName string) {
	t.Helper()
	ctx := context.Background()
	user := &data.User{
		ID:           id,
		UID:          uid,
		Ncuid:        ncuid,
		Email:        strings.ToLower(uid) + "@example.com",
		Username:     strings.ToLower(uid),
		DisplayName:  displayName,
		PasswordHash: "hash",
	}
	if err := api.users.Create(ctx, user); err != nil {
		t.Fatalf("create user %s: %v", uid, err)
	}
}

func TestSanitizeMentionBackfillsNcuidFromUID(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	// Ncuid 默认等于 UID（Create 自动填充）。
	createPayloadGuardTestUser(t, api, "mention-user", "MENTION001", "被@者")
	body := `{"v":2,"text":"@被@者","mentions":[{"uid":"MENTION001","name":"被@者"}]}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "SENDER001", "GRP-TEST", "text", body)
	if !ok {
		t.Fatalf("expected mention payload to pass")
	}
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		t.Fatalf("parse sanitized payload failed: %s", cleaned)
	}
	if len(payload.Mentions) != 1 {
		t.Fatalf("expected 1 mention, got %d", len(payload.Mentions))
	}
	if payload.Mentions[0].UID != "MENTION001" || payload.Mentions[0].Ncuid != "MENTION001" {
		t.Fatalf("expected ncuid backfilled to MENTION001, got uid=%q ncuid=%q",
			payload.Mentions[0].UID, payload.Mentions[0].Ncuid)
	}
}

func TestSanitizeMentionBackfillsUIDFromNcuid(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	// 模拟改过 UID 的用户：ncuid 与 uid 不同。
	createPayloadGuardTestUserWithNcuid(t, api, "mention-user", "NEWUID001", "OLDNC001", "改名用户")
	body := `{"v":2,"text":"@改名用户","mentions":[{"ncuid":"OLDNC001","name":"改名用户"}]}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "SENDER001", "GRP-TEST", "text", body)
	if !ok {
		t.Fatalf("expected mention payload to pass")
	}
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		t.Fatalf("parse sanitized payload failed: %s", cleaned)
	}
	if len(payload.Mentions) != 1 {
		t.Fatalf("expected 1 mention, got %d", len(payload.Mentions))
	}
	if payload.Mentions[0].UID != "NEWUID001" || payload.Mentions[0].Ncuid != "OLDNC001" {
		t.Fatalf("expected uid backfilled to NEWUID001 and ncuid OLDNC001, got uid=%q ncuid=%q",
			payload.Mentions[0].UID, payload.Mentions[0].Ncuid)
	}
}

func TestSanitizeMentionDropsUnknownUser(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	body := `{"v":2,"text":"@不存在","mentions":[{"uid":"NOBODY001","name":"不存在"}]}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "SENDER001", "GRP-TEST", "text", body)
	if !ok {
		t.Fatalf("expected mention payload to pass")
	}
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		// 未知 mention 被全部丢弃且无其他 extras 时，服务端降级为纯文本。
		if strings.TrimSpace(cleaned) != "@不存在" {
			t.Fatalf("expected plain text fallback %q, got %q", "@不存在", cleaned)
		}
		return
	}
	if len(payload.Mentions) != 0 {
		t.Fatalf("expected unknown mention to be dropped, got %d", len(payload.Mentions))
	}
}

func TestSanitizeMentionKeepsBothFieldsWhenProvided(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	body := `{"v":2,"text":"@双字段","mentions":[{"uid":"MENTION001","ncuid":"MENTION001","name":"双字段"}]}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "SENDER001", "GRP-TEST", "text", body)
	if !ok {
		t.Fatalf("expected mention payload to pass")
	}
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		t.Fatalf("parse sanitized payload failed: %s", cleaned)
	}
	if len(payload.Mentions) != 1 {
		t.Fatalf("expected 1 mention, got %d", len(payload.Mentions))
	}
	if payload.Mentions[0].UID != "MENTION001" || payload.Mentions[0].Ncuid != "MENTION001" {
		t.Fatalf("expected both fields preserved, got uid=%q ncuid=%q",
			payload.Mentions[0].UID, payload.Mentions[0].Ncuid)
	}
}

func TestSanitizeQuoteBackfillsFromNcuid(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	createPayloadGuardTestUser(t, api, "quote-sender", "QUOTES001", "引用者")
	createPayloadGuardTestUser(t, api, "quote-owner", "QUOTEO001", "被引用者")
	err := api.groups.Create(ctx, &data.Group{ID: "group-2", Name: "引用测试群"}, "quote-owner")
	if err != nil {
		t.Fatalf("create group: %v", err)
	}
	if err = api.groups.AddMember(ctx, "group-2", "quote-sender", data.GroupRoleMember); err != nil {
		t.Fatalf("add member: %v", err)
	}
	err = api.groupMsgs.Create(ctx, &data.GroupMessage{
		ID:       "group-quote-1",
		GroupID:  "group-2",
		SenderID: "quote-owner",
		Body:     "被引用的内容",
		MsgType:  "text",
	})
	if err != nil {
		t.Fatalf("create group message: %v", err)
	}
	body := `{"v":2,"text":"引用一下","quote":{"id":"group-quote-1"}}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "quote-sender", "group-2", "text", body)
	if !ok {
		t.Fatalf("expected quote payload to pass")
	}
	payload, ok := parseGuardPayload(cleaned)
	if !ok {
		t.Fatalf("parse sanitized payload failed: %s", cleaned)
	}
	if payload.Quote == nil {
		t.Fatalf("expected quote preserved")
	}
	if payload.Quote.FromUID != "QUOTEO001" || payload.Quote.FromNcuid != "QUOTEO001" {
		t.Fatalf("expected quote from_ncuid backfilled to QUOTEO001, got from_uid=%q from_ncuid=%q",
			payload.Quote.FromUID, payload.Quote.FromNcuid)
	}
}

func TestSanitizeQuoteRejectsCrossGroupReference(t *testing.T) {
	ctx := context.Background()
	api := newPayloadGuardTestAPI(t)
	createPayloadGuardTestUser(t, api, "cross-sender", "CROSS001", "跨群者")
	createPayloadGuardTestUser(t, api, "cross-owner", "CROSS002", "群主")
	err := api.groups.Create(ctx, &data.Group{ID: "group-a", Name: "A群"}, "cross-owner")
	if err != nil {
		t.Fatalf("create group-a: %v", err)
	}
	if err = api.groups.AddMember(ctx, "group-a", "cross-sender", data.GroupRoleMember); err != nil {
		t.Fatalf("add member group-a: %v", err)
	}
	err = api.groups.Create(ctx, &data.Group{ID: "group-b", Name: "B群"}, "cross-owner")
	if err != nil {
		t.Fatalf("create group-b: %v", err)
	}
	if err = api.groups.AddMember(ctx, "group-b", "cross-sender", data.GroupRoleMember); err != nil {
		t.Fatalf("add member group-b: %v", err)
	}
	err = api.groupMsgs.Create(ctx, &data.GroupMessage{
		ID:       "cross-msg-a",
		GroupID:  "group-a",
		SenderID: "cross-owner",
		Body:     "A群消息",
		MsgType:  "text",
	})
	if err != nil {
		t.Fatalf("create group-a message: %v", err)
	}
	// 在 B 群里引用 A 群的消息 -> quote 被清空降级为纯文本，不阻断正常消息
	body := `{"v":2,"text":"跨群引用","quote":{"id":"cross-msg-a"}}`
	cleaned, ok := api.sanitizeMessageBodyForSend(ctx, "cross-sender", "group-b", "text", body)
	if !ok {
		t.Fatalf("expected cross-group quote reference to degrade gracefully, but was rejected")
	}
	if strings.Contains(cleaned, "cross-msg-a") {
		t.Fatalf("expected cross-msg-a to be stripped, got %q", cleaned)
	}
}
