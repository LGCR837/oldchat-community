package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/data"
)

func newAdminBugHandlerTestAPI(t *testing.T) (*API, *data.BugReportStore) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	store := data.NewBugReportStore(db)
	if err := store.EnsureTable(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return &API{bugReportStore: store, adminSessions: newAdminSessions()}, store
}

func addAdminSession(req *http.Request, api *API) {
	token := api.adminSessions.New(adminSessionTTL)
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: token})
}

func postAdminBugClear(api *API, confirmation string) *httptest.ResponseRecorder {
	form := url.Values{"confirm": []string{confirmation}}
	req := httptest.NewRequest(http.MethodPost, "https://example.com/admins/bug-reports/clear", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://example.com")
	addAdminSession(req, api)
	rec := httptest.NewRecorder()
	api.handleAdminBugReportsClear(rec, req)
	return rec
}

func TestAdminBugClearRequiresExactConfirmation(t *testing.T) {
	api, store := newAdminBugHandlerTestAPI(t)
	if err := store.Create(context.Background(), &data.BugReport{UserUID: "10001", Content: "不能误删"}); err != nil {
		t.Fatal(err)
	}
	rec := postAdminBugClear(api, "1")
	if rec.Code != http.StatusSeeOther || !strings.Contains(rec.Header().Get("Location"), "err=") {
		t.Fatalf("确认错误时响应异常：code=%d location=%q", rec.Code, rec.Header().Get("Location"))
	}
	total, _ := store.Count(context.Background())
	if total != 1 {
		t.Fatalf("错误确认值删除了反馈：%d", total)
	}
}

func TestAdminBugClearDeletesAll(t *testing.T) {
	api, store := newAdminBugHandlerTestAPI(t)
	for i := 0; i < 2; i++ {
		if err := store.Create(context.Background(), &data.BugReport{UserUID: "10001", Content: "待清空"}); err != nil {
			t.Fatal(err)
		}
	}
	rec := postAdminBugClear(api, "DELETE_ALL_BUG_REPORTS")
	if rec.Code != http.StatusSeeOther || !strings.Contains(rec.Header().Get("Location"), "ok=") {
		t.Fatalf("清空响应异常：code=%d location=%q", rec.Code, rec.Header().Get("Location"))
	}
	total, _ := store.Count(context.Background())
	if total != 0 {
		t.Fatalf("反馈未清空：%d", total)
	}
}

func TestAdminPostAllowsSameOrigin(t *testing.T) {
	api, store := newAdminBugHandlerTestAPI(t)
	if err := store.Create(context.Background(), &data.BugReport{UserUID: "10001", Content: "同源可操作"}); err != nil {
		t.Fatal(err)
	}
	form := url.Values{"confirm": []string{"DELETE_ALL_BUG_REPORTS"}}
	req := httptest.NewRequest(http.MethodPost, "https://example.com/admins/bug-reports/clear", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://example.com")
	addAdminSession(req, api)
	rec := httptest.NewRecorder()
	api.handleAdminBugReportsClear(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("同源管理写操作应通过：%d", rec.Code)
	}
	total, _ := store.Count(context.Background())
	if total != 0 {
		t.Fatalf("反馈未清空：%d", total)
	}
}
