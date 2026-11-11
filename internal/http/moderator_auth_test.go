package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"metrochat/internal/auth"
	"metrochat/internal/config"
	"metrochat/internal/data"
	"metrochat/internal/ratelimit"
	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"
)

func newModeratorAuthTestAPI(t *testing.T) (*API, *data.ModeratorStore) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(`
CREATE TABLE moderators (
    id VARCHAR(32) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    nickname TEXT NOT NULL DEFAULT '',
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at DATETIME
);
CREATE TABLE bug_reports (
    id VARCHAR(32) PRIMARY KEY,
    user_uid VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'open',
    note TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME NULL
);
CREATE TABLE ban_appeals (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    uid VARCHAR(32) NOT NULL,
    username VARCHAR(64) NOT NULL,
    ban_reason TEXT NOT NULL,
    appeal_text TEXT NOT NULL,
    contact TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    admin_note TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at DATETIME NULL
);
CREATE TABLE user_reports (
    id VARCHAR(32) PRIMARY KEY,
    reporter_uid VARCHAR(32) NOT NULL,
    target_uid VARCHAR(32) NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    result TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at DATETIME NULL
);
CREATE TABLE banned_users (
    user_id VARCHAR(32) PRIMARY KEY,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    banned_until DATETIME NULL
);
CREATE TABLE banned_devices (
    device_id VARCHAR(128) PRIMARY KEY,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    banned_until DATETIME NULL
);
CREATE TABLE crash_reports (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(32),
    crash_log TEXT NOT NULL,
    device_model VARCHAR(128) NOT NULL DEFAULT '',
    android_version VARCHAR(32) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE users (
    id VARCHAR(32) PRIMARY KEY,
    uid VARCHAR(32) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    display_name VARCHAR(64) NOT NULL DEFAULT '',
    email VARCHAR(128) NOT NULL DEFAULT '',
    avatar_url TEXT NOT NULL DEFAULT '',
    coin_balance INTEGER NOT NULL DEFAULT 0,
    reputation_score INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
`); err != nil {
		t.Fatal(err)
	}

	modStore := data.NewModeratorStore(db)
	api := &API{
		cfg: config.Config{
			AdminUser:     "admin",
			AdminPassword: "adminpassword",
		},
		db:                 db,
		moderatorStore:     modStore,
		moderatorSessions:  newModeratorSessions(),
		adminSessions:      newAdminSessions(),
		bugReportStore:     data.NewBugReportStore(db),
		banAppeals:         data.NewBanAppealStore(db),
		reportStore:        data.NewReportStore(db),
		devices:            data.NewDeviceStore(db),
		crashReportStore:   data.NewCrashReportStore(db),
		adminLoginLimiter:  ratelimit.NewLimiter(100.0, 100),
	}
	t.Cleanup(func() { db.Close() })
	return api, modStore
}

func TestModeratorLoginAndAuthFlow(t *testing.T) {
	api, store := newModeratorAuthTestAPI(t)
	ctx := context.Background()

	// 1. Create a moderator in store
	pwd := "modPass123"
	hash, err := auth.HashPassword(pwd)
	if err != nil {
		t.Fatal(err)
	}
	mod, err := store.Create(ctx, "mod_alice", hash, "爱丽丝")
	if err != nil {
		t.Fatal(err)
	}

	// 2. Unauthenticated request to /admins/moderation redirects to /admins/moderation/login
	req := httptest.NewRequest(http.MethodGet, "/admins/moderation", nil)
	rec := httptest.NewRecorder()
	api.handleAdminModeration(rec, req)
	if rec.Code != http.StatusSeeOther || rec.Header().Get("Location") != "/admins/moderation/login" {
		t.Fatalf("expected redirect to login, got %d, loc: %s", rec.Code, rec.Header().Get("Location"))
	}

	// 3. Login with wrong password
	form := url.Values{}
	form.Set("username", "mod_alice")
	form.Set("password", "wrongpass")
	req = httptest.NewRequest(http.MethodPost, "/admins/moderation/login", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec = httptest.NewRecorder()
	api.handleModerationLogin(rec, req)
	if !strings.Contains(rec.Body.String(), "错误") {
		t.Fatalf("expected error message on wrong password")
	}

	// 4. Login with correct password
	form.Set("password", pwd)
	req = httptest.NewRequest(http.MethodPost, "/admins/moderation/login", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec = httptest.NewRecorder()
	api.handleModerationLogin(rec, req)
	if rec.Code != http.StatusSeeOther || rec.Header().Get("Location") != "/admins/moderation" {
		t.Fatalf("expected redirect to /admins/moderation on success, got %d, loc: %s", rec.Code, rec.Header().Get("Location"))
	}

	// Extract moderator cookie
	cookies := rec.Result().Cookies()
	var modCookie *http.Cookie
	for _, c := range cookies {
		if c.Name == moderatorCookie {
			modCookie = c
			break
		}
	}
	if modCookie == nil || modCookie.Value == "" {
		t.Fatalf("expected moderator_session cookie to be set")
	}

	// 5. Access /admins/moderation with moderator_session cookie
	req = httptest.NewRequest(http.MethodGet, "/admins/moderation", nil)
	req.AddCookie(modCookie)
	rec = httptest.NewRecorder()
	api.handleAdminModeration(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200 with moderator cookie, got %d", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "爱丽丝") {
		t.Fatalf("expected body to contain moderator nickname 爱丽丝")
	}

	// 6. Test Super Admin isModeratorOrAdmin
	superToken := api.adminSessions.New(1 * time.Hour)
	reqAdmin := httptest.NewRequest(http.MethodGet, "/admins/moderation", nil)
	reqAdmin.AddCookie(&http.Cookie{Name: adminCookie, Value: superToken})
	name, isSuper, ok := api.isModeratorOrAdmin(reqAdmin)
	if !ok || !isSuper || name != "超级管理员" {
		t.Fatalf("expected super admin, got ok=%v isSuper=%v name=%s", ok, isSuper, name)
	}

	// 7. Logout
	logoutReq := httptest.NewRequest(http.MethodPost, "/admins/moderation/logout", nil)
	logoutReq.AddCookie(modCookie)
	logoutRec := httptest.NewRecorder()
	api.handleModerationLogout(logoutRec, logoutReq)
	if logoutRec.Code != http.StatusSeeOther || logoutRec.Header().Get("Location") != "/admins/moderation/login" {
		t.Fatalf("expected redirect to login on logout, got %d", logoutRec.Code)
	}
	// Verify session invalidated
	if _, valid := api.moderatorSessions.Get(modCookie.Value); valid {
		t.Fatalf("expected session to be deleted after logout")
	}

	_ = mod
}

func TestAdminModeratorManagement(t *testing.T) {
	api, store := newModeratorAuthTestAPI(t)
	adminToken := api.adminSessions.New(1 * time.Hour)

	// 1. Create moderator via super admin form
	form := url.Values{}
	form.Set("username", "mod_bob")
	form.Set("password", "bobPassword123")
	form.Set("nickname", "鲍勃")
	req := httptest.NewRequest(http.MethodPost, "/admins/moderators/create", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: adminToken})
	rec := httptest.NewRecorder()
	api.handleAdminModeratorCreate(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("expected 303 redirect after create, got %d", rec.Code)
	}

	// Verify in DB
	mod, err := store.GetByUsername(context.Background(), "mod_bob")
	if err != nil || mod == nil {
		t.Fatalf("moderator mod_bob not found in store: %v", err)
	}
	if mod.Nickname != "鲍勃" || !mod.IsActive {
		t.Fatalf("unexpected mod: %+v", mod)
	}

	// 2. Toggle active
	toggleForm := url.Values{}
	toggleForm.Set("id", mod.ID)
	toggleForm.Set("active", "0")
	req = httptest.NewRequest(http.MethodPost, "/admins/moderators/toggle", strings.NewReader(toggleForm.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: adminToken})
	rec = httptest.NewRecorder()
	api.handleAdminModeratorToggle(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("expected 303 on toggle, got %d", rec.Code)
	}
	mod, _ = store.GetByID(context.Background(), mod.ID)
	if mod.IsActive {
		t.Fatalf("expected mod to be disabled")
	}

	// 3. Reset password
	resetForm := url.Values{}
	resetForm.Set("id", mod.ID)
	resetForm.Set("password", "newBobPass999")
	req = httptest.NewRequest(http.MethodPost, "/admins/moderators/reset-password", strings.NewReader(resetForm.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: adminToken})
	rec = httptest.NewRecorder()
	api.handleAdminModeratorResetPassword(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("expected 303 on reset password, got %d", rec.Code)
	}
	mod, _ = store.GetByID(context.Background(), mod.ID)
	ok, _ := auth.VerifyPassword("newBobPass999", mod.PasswordHash)
	if !ok {
		t.Fatalf("new password verify failed")
	}

	// 4. Delete moderator
	delForm := url.Values{}
	delForm.Set("id", mod.ID)
	req = httptest.NewRequest(http.MethodPost, "/admins/moderators/delete", strings.NewReader(delForm.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: adminToken})
	rec = httptest.NewRecorder()
	api.handleAdminModeratorDelete(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("expected 303 on delete, got %d", rec.Code)
	}
	mod, _ = store.GetByID(context.Background(), mod.ID)
	if mod != nil {
		t.Fatalf("expected mod to be deleted, found: %+v", mod)
	}
}
