package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDeveloperTokenDocuments(t *testing.T) {
	for _, tc := range []struct {
		path    string
		handler http.HandlerFunc
		want    []string
	}{
		{"/bot-api.md", handleBotAPIDocument, []string{"/bot-apply", "/v2/buttons/send", "/v2/buttons/responses", "Bearer"}},
		{"/channel-api.md", handleChannelAPIDocument, []string{"/channel-api/apply", "/v2/channel-api/channels", "/v2/channel-api/posts", "Bearer"}},
	} {
		rec := httptest.NewRecorder()
		tc.handler(rec, httptest.NewRequest(http.MethodGet, tc.path, nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("%s status=%d", tc.path, rec.Code)
		}
		if got := rec.Header().Get("Content-Type"); !strings.Contains(got, "text/markdown") {
			t.Fatalf("%s content-type=%q", tc.path, got)
		}
		for _, want := range tc.want {
			if !strings.Contains(rec.Body.String(), want) {
				t.Fatalf("%s missing %q", tc.path, want)
			}
		}
	}
}

func TestModerationShowsBotAndChannelApplications(t *testing.T) {
	api := newModerationTestAPI(t)
	_, err := api.db.Exec(`
CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, uid TEXT NOT NULL, username TEXT NOT NULL);
CREATE TABLE bot_permits (id TEXT PRIMARY KEY,user_id TEXT NOT NULL,token_hash TEXT NOT NULL DEFAULT '',status TEXT NOT NULL,scopes TEXT NOT NULL,reason TEXT NOT NULL,review_reason TEXT NOT NULL DEFAULT '',created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE channel_api_permits (id TEXT PRIMARY KEY,user_id TEXT NOT NULL,token_hash TEXT NOT NULL DEFAULT '',status TEXT NOT NULL,scopes TEXT NOT NULL,reason TEXT NOT NULL,review_reason TEXT NOT NULL DEFAULT '',created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO users(id,uid,username) VALUES('u-1','UID100','developer');
INSERT INTO bot_permits(id,user_id,status,scopes,reason) VALUES('bot-1','u-1','applied','group','button bot');
INSERT INTO channel_api_permits(id,user_id,status,scopes,reason) VALUES('channel-1','u-1','applied','channel:post','news publisher');`)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ section, want, action string }{
		{"bots", "button bot", "/admins/moderation/bot/review"},
		{"channels", "news publisher", "/admins/moderation/channel/review"},
	} {
		rec := getModeration(t, api, tc.section, "")
		if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), tc.want) || !strings.Contains(rec.Body.String(), tc.action) {
			t.Fatalf("section=%s code=%d body=%s", tc.section, rec.Code, rec.Body.String())
		}
	}
}

func TestPermitReviewStateMachine(t *testing.T) {
	api := newModerationTestAPI(t)
	_, err := api.db.Exec(`
CREATE TABLE bot_permits (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL, token_hash TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL, scopes TEXT NOT NULL, reason TEXT NOT NULL,
 review_reason TEXT NOT NULL DEFAULT '', expires_at DATETIME,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at DATETIME
);
CREATE TABLE channel_api_permits (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL, token_hash TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL, scopes TEXT NOT NULL, reason TEXT NOT NULL,
 review_reason TEXT NOT NULL DEFAULT '', expires_at DATETIME,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at DATETIME
);
INSERT INTO bot_permits(id,user_id,status,scopes,reason) VALUES('bot-1','u-1','applied','group','test bot');
INSERT INTO channel_api_permits(id,user_id,status,scopes,reason) VALUES('channel-1','u-1','applied','channel:post','test channel');`)
	if err != nil {
		t.Fatal(err)
	}

	updated, err := api.reviewBotPermit(context.Background(), "bot-1", "approve", "ok")
	if err != nil || !updated {
		t.Fatalf("approve bot updated=%v err=%v", updated, err)
	}
	updated, err = api.reviewBotPermit(context.Background(), "bot-1", "reject", "late")
	if err != nil || updated {
		t.Fatalf("second bot review updated=%v err=%v", updated, err)
	}
	updated, err = api.reviewChannelAPIPermit(context.Background(), "channel-1", "reject", "scope too broad")
	if err != nil || !updated {
		t.Fatalf("reject channel updated=%v err=%v", updated, err)
	}

	var botStatus, botReason, botTokenHash, channelStatus, channelReason string
	if err = api.db.QueryRow(`SELECT status,review_reason,token_hash FROM bot_permits WHERE id='bot-1'`).Scan(&botStatus, &botReason, &botTokenHash); err != nil {
		t.Fatal(err)
	}
	if err = api.db.QueryRow(`SELECT status,review_reason FROM channel_api_permits WHERE id='channel-1'`).Scan(&channelStatus, &channelReason); err != nil {
		t.Fatal(err)
	}
	if botStatus != "approved" || botReason != "ok" || botTokenHash != "" || channelStatus != "rejected" || channelReason != "scope too broad" {
		t.Fatalf("unexpected review results: bot=%s/%s hash=%q channel=%s/%s", botStatus, botReason, botTokenHash, channelStatus, channelReason)
	}
}
