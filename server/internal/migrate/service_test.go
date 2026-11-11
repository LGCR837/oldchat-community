package migrate

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func newTestDB(t *testing.T) *sqlx.DB {
	t.Helper()
	schema, err := os.ReadFile(filepath.Join("..", "..", "schema.sql"))
	if err != nil {
		t.Fatalf("读取 schema.sql 失败: %v", err)
	}
	db, err := sqlx.Open("sqlite", filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { _ = db.Close() })
	if _, err := db.Exec(string(schema)); err != nil {
		t.Fatalf("初始化数据库失败: %v", err)
	}
	return db
}

func newFakeOfficial(t *testing.T, directCalls *int) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/auth/login", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["password"] != "good-pass" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"bad_credentials","message":"用户名或密码错误"}`))
			return
		}
		writeTestJSON(w, map[string]interface{}{
			"access_token": "official-token",
			"user":         map[string]interface{}{"uid": "1001", "display_name": "我"},
		})
	})
	mux.HandleFunc("/v1/me", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{
			"uid":              "1001",
			"ncuid":            "NC1001",
			"username":         "laowang_official",
			"display_name":     "我",
			"user_title":       "老司机",
			"signature":        "旧站的签名",
			"avatar_url":       "https://ocf.oss-cn-shanghai.aliyuncs.com/avatars/me.png",
			"cover_url":        "https://ocf.oss-cn-shanghai.aliyuncs.com/covers/me.png",
			"coin_balance":     1234,
			"reputation_score": 150,
		})
	})
	// 旧站 uploads 目录里的图片副本：OSS 停流后媒体只能从这里取。
	mux.HandleFunc("/v1/uploads/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Content-Length", "1200")
		_, _ = w.Write([]byte(strings.Repeat("p", 1200)))
	})
	mux.HandleFunc("/v1/friends", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{"friends": []map[string]interface{}{
			{"uid": "2002", "display_name": "老王", "remark_name": "王哥", "user_title": "邻居", "avatar_url": "https://ocf.oss-cn-shanghai.aliyuncs.com/avatars/2002.png"},
			{"uid": "3003", "display_name": "小李"},
		}})
	})
	mux.HandleFunc("/v1/direct/messages", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		*directCalls++
		with := r.URL.Query().Get("with_uid")
		switch with {
		case "2002":
			writeTestJSON(w, map[string]interface{}{"messages": []map[string]interface{}{
				{"id": "d1", "from_uid": "2002", "body": "在吗", "msg_type": "text", "created_at": 1700000000},
				{"id": "d2", "from_uid": "1001", "body": "在", "msg_type": "text", "created_at": 1700000060},
			}, "has_more": false})
		case "3003":
			writeTestJSON(w, map[string]interface{}{"messages": []map[string]interface{}{
				{"id": "d3", "from_uid": "3003", "body": "图片", "msg_type": "image", "media_url": "https://oss.example/x.jpg", "created_at": 1700000120},
			}, "has_more": false})
		default:
			writeTestJSON(w, map[string]interface{}{"messages": []interface{}{}, "has_more": false})
		}
	})
	mux.HandleFunc("/v1/groups/list", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{"groups": []map[string]interface{}{
			{"group_id": "GP-0001", "name": "老友群", "role": 1, "avatar_url": "/oss/avatars/group1.png"},
		}})
	})
	mux.HandleFunc("/v1/groups/messages", func(w http.ResponseWriter, r *http.Request) {
		requireAuth(t, w, r)
		writeTestJSON(w, map[string]interface{}{"messages": []map[string]interface{}{
			{"id": "g1", "group_id": "GP-0001", "from_uid": "1001", "from_name": "我", "body": "大家好", "created_at": 1700000200},
			{"id": "g2", "group_id": "GP-0001", "from_uid": "2002", "from_name": "老王", "body": "你好", "created_at": 1700000260},
		}, "has_more": false})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func requireAuth(t *testing.T, w http.ResponseWriter, r *http.Request) {
	t.Helper()
	if r.Header.Get("Authorization") != "Bearer official-token" {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
	}
}

func writeTestJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func seedLocalUser(t *testing.T, db *sqlx.DB) string {
	t.Helper()
	_, err := db.Exec(`INSERT INTO users (id, uid, ncuid, email, username, display_name, password_hash)
VALUES ('ME', 'USR-ME', 'USR-ME', 'me@example.com', 'me', '我', 'x')`)
	if err != nil {
		t.Fatal(err)
	}
	return "ME"
}

func TestRunImportsFriendsDirectAndGroups(t *testing.T) {
	db := newTestDB(t)
	userID := seedLocalUser(t, db)
	directCalls := 0
	srv := newFakeOfficial(t, &directCalls)

	svc := NewService(db, Options{SourceHost: srv.URL, IncludeGroups: true})
	job, err := svc.Store().CreateJob(context.Background(), userID, "1001", srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.Run(context.Background(), job.ID, Credentials{Username: "me", Password: "good-pass"}); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}

	var users int
	if err := db.Get(&users, `SELECT COUNT(1) FROM users`); err != nil {
		t.Fatal(err)
	}
	if users != 3 {
		t.Fatalf("占位账号数量异常，期望 3 个用户，实际 %d", users)
	}

	var shadowUID string
	if err := db.Get(&shadowUID, `SELECT uid FROM users WHERE official_uid = '2002'`); err != nil {
		t.Fatalf("未找到好友的占位账号: %v", err)
	}
	if shadowUID != "2002" {
		t.Fatalf("占位账号 UID 应沿用官方 UID，实际 %q", shadowUID)
	}

	var direct int
	if err := db.Get(&direct, `SELECT COUNT(1) FROM direct_messages`); err != nil {
		t.Fatal(err)
	}
	if direct != 3 {
		t.Fatalf("私聊消息数量异常，期望 3 条，实际 %d", direct)
	}

	var createdAt string
	if err := db.Get(&createdAt, `SELECT created_at FROM direct_messages WHERE official_msg_id = 'd1'`); err != nil {
		t.Fatal(err)
	}
	if createdAt == "" || createdAt[:4] != "2023" {
		t.Fatalf("消息应保留官方时间戳，实际 %q", createdAt)
	}

	var groupMsgs int
	if err := db.Get(&groupMsgs, `SELECT COUNT(1) FROM group_messages`); err != nil {
		t.Fatal(err)
	}
	if groupMsgs != 2 {
		t.Fatalf("群消息数量异常，期望 2 条，实际 %d", groupMsgs)
	}

	var imported int
	if err := db.Get(&imported, `SELECT imported FROM groups WHERE official_group_id = 'GP-0001'`); err != nil {
		t.Fatal(err)
	}
	if imported != 1 {
		t.Fatalf("群应标记为导入，实际 imported=%d", imported)
	}

	finished, err := svc.Store().GetJob(context.Background(), job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if finished.Status != StatusDone || finished.Percent != 100 {
		t.Fatalf("任务状态异常：%s %d%%", finished.Status, finished.Percent)
	}
	events, err := svc.Store().ListEvents(context.Background(), job.ID, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) < 5 {
		t.Fatalf("过程日志过少：%d", len(events))
	}
}

func TestRunMigratesProfileAndImages(t *testing.T) {
	db := newTestDB(t)
	userID := seedLocalUser(t, db)
	directCalls := 0
	srv := newFakeOfficial(t, &directCalls)
	uploadDir := t.TempDir()

	svc := NewService(db, Options{SourceHost: srv.URL, UploadDir: uploadDir, IncludeGroups: true})
	job, err := svc.Store().CreateJob(context.Background(), userID, "1001", srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.Run(context.Background(), job.ID, Credentials{Username: "me", Password: "good-pass"}); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}

	var me struct {
		UID             string `db:"uid"`
		Ncuid           string `db:"ncuid"`
		Username        string `db:"username"`
		DisplayName     string `db:"display_name"`
		UserTitle       string `db:"user_title"`
		Signature       string `db:"signature"`
		AvatarURL       string `db:"avatar_url"`
		CoverURL        string `db:"cover_url"`
		CoinBalance     int    `db:"coin_balance"`
		ReputationScore int    `db:"reputation_score"`
	}
	if err := db.Get(&me, `SELECT uid, ncuid, username, display_name, user_title, signature, avatar_url, cover_url, coin_balance, reputation_score FROM users WHERE id = ?`, userID); err != nil {
		t.Fatal(err)
	}
	if me.DisplayName != "我" || me.UserTitle != "老司机" || me.Signature != "旧站的签名" {
		t.Fatalf("资料未迁移：%+v", me)
	}
	if me.CoinBalance != 1234 || me.ReputationScore != 150 {
		t.Fatalf("金币或声望未迁移：金币 %d 声望 %d", me.CoinBalance, me.ReputationScore)
	}
	if me.UID != "1001" || me.Ncuid != "NC1001" || me.Username != "laowang_official" {
		t.Fatalf("账号标识未迁移：%+v", me)
	}
	if !strings.HasPrefix(me.AvatarURL, "/v1/uploads/avatars/") {
		t.Fatalf("头像应换成本地地址，实际 %q", me.AvatarURL)
	}
	if !strings.HasPrefix(me.CoverURL, "/v1/uploads/covers/") {
		t.Fatalf("封面应换成本地地址，实际 %q", me.CoverURL)
	}
	for _, rel := range []string{me.AvatarURL, me.CoverURL} {
		path := filepath.Join(uploadDir, strings.TrimPrefix(rel, "/v1/uploads/"))
		info, err := os.Stat(path)
		if err != nil || info.Size() != 1200 {
			t.Fatalf("本地图片缺失或大小不符：%s err=%v", rel, err)
		}
	}

	var friend struct {
		AvatarURL string `db:"avatar_url"`
		UserTitle string `db:"user_title"`
	}
	if err := db.Get(&friend, `SELECT avatar_url, user_title FROM users WHERE official_uid = '2002'`); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(friend.AvatarURL, "/v1/uploads/avatars/") || friend.UserTitle != "邻居" {
		t.Fatalf("好友资料未迁移：%+v", friend)
	}

	var groupAvatar string
	if err := db.Get(&groupAvatar, `SELECT avatar_url FROM groups WHERE official_group_id = 'GP-0001'`); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(groupAvatar, "/v1/uploads/avatars/") {
		t.Fatalf("群头像应换成本地地址，实际 %q", groupAvatar)
	}
}

func TestRunIsIdempotent(t *testing.T) {
	db := newTestDB(t)
	userID := seedLocalUser(t, db)
	directCalls := 0
	srv := newFakeOfficial(t, &directCalls)
	svc := NewService(db, Options{SourceHost: srv.URL, IncludeGroups: true})

	for i := 0; i < 2; i++ {
		job, err := svc.Store().CreateJob(context.Background(), userID, "1001", srv.URL)
		if err != nil {
			t.Fatal(err)
		}
		if err := svc.Run(context.Background(), job.ID, Credentials{Username: "me", Password: "good-pass"}); err != nil {
			t.Fatalf("第 %d 次迁移失败: %v", i+1, err)
		}
	}

	counts := map[string]int{}
	query := func(key, sql string) {
		var n int
		if err := db.Get(&n, sql); err != nil {
			t.Fatal(err)
		}
		counts[key] = n
	}
	query("users", `SELECT COUNT(1) FROM users`)
	query("direct", `SELECT COUNT(1) FROM direct_messages`)
	query("threads", `SELECT COUNT(1) FROM direct_threads`)
	query("groups", `SELECT COUNT(1) FROM groups`)
	query("groupMsgs", `SELECT COUNT(1) FROM group_messages`)

	want := map[string]int{"users": 3, "direct": 3, "threads": 2, "groups": 1, "groupMsgs": 2}
	for key, expected := range want {
		if counts[key] != expected {
			t.Fatalf("%s 数量异常：期望 %d，实际 %d", key, expected, counts[key])
		}
	}
}

func TestRunReportsBadCredentials(t *testing.T) {
	db := newTestDB(t)
	userID := seedLocalUser(t, db)
	directCalls := 0
	srv := newFakeOfficial(t, &directCalls)
	svc := NewService(db, Options{SourceHost: srv.URL, IncludeGroups: false})
	job, err := svc.Store().CreateJob(context.Background(), userID, "1001", srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.Run(context.Background(), job.ID, Credentials{Username: "me", Password: "bad-pass"}); err == nil {
		t.Fatal("错误密码应返回失败")
	}
	failed, err := svc.Store().GetJob(context.Background(), job.ID)
	if err != nil {
		t.Fatal(err)
	}
	if failed.Status != StatusFailed || failed.Error == "" {
		t.Fatalf("任务应标记失败并带错误信息，实际 %s %q", failed.Status, failed.Error)
	}
	if !strings.HasPrefix(failed.Error, "旧服务器") {
		t.Fatalf("错误文案不够友好：%q", failed.Error)
	}
}

func TestImportDirectHonoursMessageCap(t *testing.T) {
	db := newTestDB(t)
	userID := seedLocalUser(t, db)
	directCalls := 0
	srv := newFakeOfficial(t, &directCalls)
	svc := NewService(db, Options{SourceHost: srv.URL, IncludeGroups: false, MaxMessagesPerChat: 1})
	job, err := svc.Store().CreateJob(context.Background(), userID, "1001", srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.Run(context.Background(), job.ID, Credentials{Username: "me", Password: "good-pass"}); err != nil {
		t.Fatal(err)
	}
	var direct int
	if err := db.Get(&direct, `SELECT COUNT(1) FROM direct_messages`); err != nil {
		t.Fatal(err)
	}
	if direct != 2 {
		t.Fatalf("上限为 1 时应每个会话最多 1 条，实际 %d 条", direct)
	}
	_ = strconv.Itoa(direct)
}
