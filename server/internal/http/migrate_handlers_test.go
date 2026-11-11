package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/auth"
	"metrochat/internal/config"
	"metrochat/internal/data"
	"metrochat/internal/migrate"
	"metrochat/internal/ratelimit"
)

func newMigrateTestAPI(t *testing.T, sourceHost string) (*API, *sqlx.DB) {
	t.Helper()
	t.Chdir(filepath.Join("..", ".."))
	db, err := data.OpenDB(context.Background(), filepath.Join(t.TempDir(), "migrate.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	api := &API{
		cfg: config.Config{
			MigrateEnabled:    true,
			MigrateSourceHost: sourceHost,
			MigrateAllowClaim: true,
			CaptchaMode:       config.CaptchaModeOff,
		},
		db:                db,
		users:             data.NewUserStore(db),
		ipLimiter:         ratelimit.NewLimiter(10, 10),
		tokenVersionCache: make(map[string]tokenVersionEntry),
		migrate: migrate.NewService(db, migrate.Options{
			SourceHost:    sourceHost,
			IncludeGroups: false,
		}),
	}
	return api, db
}

func fakeOfficialServer(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/auth/login", func(w http.ResponseWriter, r *http.Request) {
		var body map[string]interface{}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body["password"] != "good-pass" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"bad_credentials"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"access_token": "token",
			"user":         map[string]interface{}{"uid": "1001", "display_name": "我"},
		})
	})
	mux.HandleFunc("/v1/me", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"uid": "1001", "display_name": "我"})
	})
	mux.HandleFunc("/v1/friends", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"friends": []interface{}{}})
	})
	mux.HandleFunc("/v1/groups/list", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"groups": []interface{}{}})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func waitMigrateJob(t *testing.T, api *API, jobID string) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		job, err := api.migrate.Store().GetJob(context.Background(), jobID)
		if err == nil && (job.Status == migrate.StatusDone || job.Status == migrate.StatusFailed) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestMigratePageShowsWorkflowAndPolling(t *testing.T) {
	page := renderMigratePage(config.Config{CaptchaMode: config.CaptchaModeOff, MigrateSourceHost: "https://example.invalid"})
	for _, want := range []string{
		"从旧服务器迁移数据", "迁移并注册", "我已注册，只导入",
		"/migrate/start", "setInterval(poll, 10000)", "旧服务器账号",
	} {
		if !strings.Contains(page, want) {
			t.Fatalf("迁移页缺少 %q", want)
		}
	}
	if strings.Contains(page, "__MIG_CFG_JSON__") {
		t.Fatalf("迁移页配置占位符没有被替换")
	}
}

func TestMigrateStartCreatesAccountForNewUser(t *testing.T) {
	srv := fakeOfficialServer(t)
	api, db := newMigrateTestAPI(t, srv.URL)

	body := map[string]interface{}{
		"mode":              "register",
		"official_username": "me",
		"official_password": "good-pass",
		"username":          "tester",
		"email":             "tester@example.com",
		"password":          "password123",
	}
	raw, _ := json.Marshal(body)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/migrate/start", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	api.handleMigrateStart(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("发起迁移失败：%d %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Job     string `json:"job"`
		Account string `json:"account"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.Job == "" || resp.Account != "created" {
		t.Fatalf("返回异常：%+v", resp)
	}
	waitMigrateJob(t, api, resp.Job)

	user, err := api.users.GetByEmailOrUsername(context.Background(), "tester")
	if err != nil {
		t.Fatal(err)
	}
	if user.UID != "1001" {
		t.Fatalf("账号应沿用旧 UID，实际 %q", user.UID)
	}
	var officialUID string
	if err := db.Get(&officialUID, `SELECT official_uid FROM users WHERE id = ?`, user.ID); err != nil {
		t.Fatal(err)
	}
	if officialUID != "1001" {
		t.Fatalf("账号应绑定旧 UID，实际 %q", officialUID)
	}
	ok, err := auth.VerifyPassword("password123", user.PasswordHash)
	if err != nil || !ok {
		t.Fatalf("新账号密码不可用：%v", err)
	}
}

func TestMigrateStartClaimsShadowAccountWithHistory(t *testing.T) {
	srv := fakeOfficialServer(t)
	api, db := newMigrateTestAPI(t, srv.URL)

	shadowID, created, err := api.migrate.Store().UpsertShadowUser(context.Background(), migrate.ShadowProfile{
		OfficialUID: "1001",
		DisplayName: "我",
	})
	if err != nil || !created {
		t.Fatalf("预置占位账号失败：%v", err)
	}

	rec := httptest.NewRecorder()
	body := `{"mode":"register","official_username":"me","official_password":"good-pass","username":"claimed","email":"claimed@example.com","password":"password123"}`
	req := httptest.NewRequest(http.MethodPost, "/migrate/start", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	api.handleMigrateStart(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("认领失败：%d %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Account string `json:"account"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp.Account != "claimed" {
		t.Fatalf("应走认领分支，实际 %q", resp.Account)
	}
	var jobResp struct {
		Job string `json:"job"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &jobResp)
	waitMigrateJob(t, api, jobResp.Job)

	var isShadow int
	var email string
	if err := db.Get(&isShadow, `SELECT is_shadow FROM users WHERE id = ?`, shadowID); err != nil {
		t.Fatal(err)
	}
	if isShadow != 0 {
		t.Fatalf("占位账号应升级为真账号")
	}
	if err := db.Get(&email, `SELECT email FROM users WHERE id = ?`, shadowID); err != nil {
		t.Fatal(err)
	}
	if email != "claimed@example.com" {
		t.Fatalf("认领后邮箱未更新：%q", email)
	}
}

func TestMigrateStartRejectsBadOfficialPassword(t *testing.T) {
	srv := fakeOfficialServer(t)
	api, _ := newMigrateTestAPI(t, srv.URL)
	rec := httptest.NewRecorder()
	body := `{"mode":"register","official_username":"me","official_password":"wrong","username":"tester","email":"tester@example.com","password":"password123"}`
	req := httptest.NewRequest(http.MethodPost, "/migrate/start", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	api.handleMigrateStart(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("错误密码应返回 400，实际 %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "official_rejected") {
		t.Fatalf("错误码异常：%s", rec.Body.String())
	}
}

func TestMigrateStatusReturnsEvents(t *testing.T) {
	api, db := newMigrateTestAPI(t, "https://example.invalid")
	if _, err := db.Exec(`INSERT INTO users (id, uid, ncuid, email, username, display_name, password_hash)
VALUES ('ME','USR-ME','USR-ME','me@example.com','me','我','x')`); err != nil {
		t.Fatal(err)
	}
	job, err := api.migrate.Store().CreateJob(context.Background(), "ME", "1001", "https://example.invalid")
	if err != nil {
		t.Fatal(err)
	}
	api.migrate.Store().UpdateProgress(context.Background(), job.ID, "迁移私聊（1/2）", 42, map[string]int64{"friends": 2, "direct_messages": 30})
	api.migrate.Store().AddEvent(context.Background(), job.ID, "ok", "私聊", "与 老王 的私聊完成：10 条消息")

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/migrate/status?job="+job.ID, nil)
	api.handleMigrateStatus(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("状态查询失败：%d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{`"percent":42`, "迁移私聊", "与 老王 的私聊完成", `"direct_messages":30`} {
		if !strings.Contains(body, want) {
			t.Fatalf("状态响应缺少 %q：%s", want, body)
		}
	}
}
