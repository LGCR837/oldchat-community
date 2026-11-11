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

// newModerationTestAPI 构造包含法庭/举报/Bug 三个 store 的测试 API。
func newModerationTestAPI(t *testing.T) *API {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	ctx := context.Background()

	bugStore := data.NewBugReportStore(db)
	if err := bugStore.EnsureTable(ctx); err != nil {
		t.Fatal(err)
	}
	// 法庭/举报表由 OpenDB 迁移创建，测试内存库需手动建表。
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS ban_appeals (
    id VARCHAR(32) PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    uid VARCHAR(32) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    ban_reason TEXT NOT NULL DEFAULT '',
    banned_until DATETIME NULL,
    appeal_text TEXT NOT NULL DEFAULT '',
    contact TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending',
    admin_note TEXT NOT NULL DEFAULT '',
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS user_reports (
    id VARCHAR(32) PRIMARY KEY,
    reporter_id VARCHAR(32),
    reporter_uid VARCHAR(32) NOT NULL DEFAULT '',
    target_user_id VARCHAR(32),
    target_uid VARCHAR(32) NOT NULL DEFAULT '',
    target_device_id VARCHAR(128) NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending',
    result TEXT NOT NULL DEFAULT '',
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(32) PRIMARY KEY,
    uid VARCHAR(32) NOT NULL DEFAULT '',
    username VARCHAR(64) NOT NULL DEFAULT '',
    coin_balance INTEGER NOT NULL DEFAULT 0,
    reputation_score INTEGER NOT NULL DEFAULT 100,
    ban_count INTEGER NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS banned_users (
    user_id VARCHAR(32) PRIMARY KEY,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    banned_until DATETIME NULL
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS banned_devices (
    device_id VARCHAR(128) PRIMARY KEY,
    reason TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    banned_until DATETIME NULL
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS crash_reports (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(32),
    crash_log TEXT NOT NULL,
    device_model VARCHAR(128) NOT NULL DEFAULT '',
    android_version VARCHAR(32) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS public_court_cases (
    id VARCHAR(32) PRIMARY KEY,
    report_id VARCHAR(32) NOT NULL DEFAULT '',
    reporter_id VARCHAR(32) NOT NULL,
    reporter_uid VARCHAR(32) NOT NULL DEFAULT '',
    defendant_id VARCHAR(32) NOT NULL,
    defendant_uid VARCHAR(32) NOT NULL DEFAULT '',
    report_reason TEXT NOT NULL DEFAULT '',
    report_evidence TEXT NOT NULL DEFAULT '',
    defense_reason TEXT NOT NULL DEFAULT '',
    defense_evidence TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'open',
    verdict VARCHAR(32) NOT NULL DEFAULT '',
    admin_note TEXT NOT NULL DEFAULT '',
    ban_hours INTEGER NOT NULL DEFAULT 0,
    reward_processed INTEGER NOT NULL DEFAULT 0,
    closed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS public_court_votes (
    case_id VARCHAR(32) NOT NULL,
    voter_id VARCHAR(32) NOT NULL,
    vote VARCHAR(16) NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    evidence TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, voter_id)
);
CREATE TABLE IF NOT EXISTS public_court_statements (
    case_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    evidence TEXT NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, user_id)
);
CREATE TABLE IF NOT EXISTS public_court_discussions (
    id VARCHAR(32) PRIMARY KEY,
    case_id VARCHAR(32) NOT NULL,
    user_id VARCHAR(32) NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);`); err != nil {
		t.Fatal(err)
	}
	appealStore := data.NewBanAppealStore(db)
	reportStore := data.NewReportStore(db)
	deviceStore := data.NewDeviceStore(db)
	crashStore := data.NewCrashReportStore(db)
	courtStore := data.NewPublicCourtStore(db)
	t.Cleanup(func() { _ = db.Close() })
	return &API{
		db:                db,
		bugReportStore:    bugStore,
		banAppeals:        appealStore,
		reportStore:       reportStore,
		devices:           deviceStore,
		crashReportStore:  crashStore,
		publicCourt:       courtStore,
		adminSessions:     newAdminSessions(),
		moderatorSessions: newModeratorSessions(),
	}
}

// seedModerationData 向三个 store 各写入 count 条未处理数据。
func seedModerationData(t *testing.T, api *API, count int) {
	t.Helper()
	ctx := context.Background()
	for i := 0; i < count; i++ {
		uid := "UID-" + string(rune('A'+i%26)) + strings.Repeat("0", i/26) + itoa(i)
		if err := api.bugReportStore.Create(ctx, &data.BugReport{UserUID: uid, Content: "bug " + itoa(i)}); err != nil {
			t.Fatal(err)
		}
		if err := api.banAppeals.Create(ctx, &data.BanAppeal{UserID: uid, UID: uid, Username: "u" + itoa(i), BanReason: "reason", AppealText: "appeal " + itoa(i)}); err != nil {
			t.Fatal(err)
		}
		if err := api.reportStore.CreateUserReport(ctx, &data.UserReport{ReporterUID: uid, TargetUID: "t-" + itoa(i), Reason: "report " + itoa(i)}); err != nil {
			t.Fatal(err)
		}
		if err := api.crashReportStore.Create(ctx, &data.CrashReport{
			ID:             "crash-" + itoa(i),
			UserID:         &uid,
			CrashLog:       "java.lang.OutOfMemoryError: failed to allocate\n\tat com.im.oldchat.ui.ChatActivity.onCreate(SourceFile:100)\n\tat android.app.ActivityThread.performLaunchActivity(ActivityThread.java:1630)",
			DeviceModel:    "Lenovo A288t",
			AndroidVersion: "2.3.5",
		}); err != nil {
			t.Fatal(err)
		}
	}
}

// markSomeHandled 把部分条目标记为已处理：
// bugs 前 8 条 resolved、appeals 前 5 条 approved、reports 前 3 条 resolved。
func markSomeHandled(t *testing.T, api *API) {
	t.Helper()
	ctx := context.Background()
	bugs, err := api.bugReportStore.ListRecent(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 8 && i < len(bugs); i++ {
		if err := api.bugReportStore.SetStatus(ctx, bugs[i].ID, "resolved", "已处理"); err != nil {
			t.Fatal(err)
		}
	}
	appeals, err := api.banAppeals.ListRecent(ctx, "all", 100)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 5 && i < len(appeals); i++ {
		if err := api.banAppeals.SetStatus(ctx, appeals[i].ID, "approved", "通过"); err != nil {
			t.Fatal(err)
		}
	}
	reports, err := api.reportStore.ListRecentUserReports(ctx, 100)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3 && i < len(reports); i++ {
		if err := api.reportStore.SetUserReportStatus(ctx, reports[i].ID, "resolved", "已处理"); err != nil {
			t.Fatal(err)
		}
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}

func getModeration(t *testing.T, api *API, section, page string) *httptest.ResponseRecorder {
	t.Helper()
	path := "/admins/moderation?section=" + section
	if page != "" {
		path += "&page=" + page
	}
	req := httptest.NewRequest(http.MethodGet, "https://example.com"+path, nil)
	addAdminSession(req, api)
	rec := httptest.NewRecorder()
	api.handleAdminModeration(rec, req)
	return rec
}

func TestModerationRequiresAdmin(t *testing.T) {
	api := newModerationTestAPI(t)
	req := httptest.NewRequest(http.MethodGet, "https://example.com/admins/moderation", nil)
	rec := httptest.NewRecorder()
	api.handleAdminModeration(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("未登录应重定向至审核台独立登录页，得到：%d", rec.Code)
	}
	if loc := rec.Header().Get("Location"); loc != "/admins/moderation/login" {
		t.Fatalf("重定向目标应为 /admins/moderation/login，得到：%s", loc)
	}

	loginReq := httptest.NewRequest(http.MethodGet, "https://example.com/admins/moderation/login", nil)
	loginRec := httptest.NewRecorder()
	api.handleModerationLoginPage(loginRec, loginReq)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("访问审核台登录页应返回 200，得到：%d", loginRec.Code)
	}
	if !strings.Contains(loginRec.Body.String(), "内容审核台") {
		t.Fatalf("审核台登录页应包含内容审核台标题")
	}
}

func TestModerationSectionsAndPagination(t *testing.T) {
	api := newModerationTestAPI(t)
	seedModerationData(t, api, 40)

	var rec *httptest.ResponseRecorder
	var body string
	for _, tc := range []struct{ section, want string }{
		{"court", "法庭"},
		{"reports", "用户举报"},
		{"bugs", "Bug 反馈"},
		{"crashes", "崩溃日志"},
	} {
		rec = getModeration(t, api, tc.section, "")
		if rec.Code != http.StatusOK {
			t.Fatalf("section=%s code=%d", tc.section, rec.Code)
		}
		body = rec.Body.String()
		if !strings.Contains(body, tc.want) {
			t.Fatalf("section=%s 缺少标题 %q", tc.section, tc.want)
		}
		// 40 条数据分 3 页（每页 15）。
		if !strings.Contains(body, "第 1 / 3 页") {
			t.Fatalf("section=%s 分页信息缺失: %q", tc.section, body)
		}
		if !strings.Contains(body, "共 40 条") {
			t.Fatalf("section=%s 总数缺失", tc.section)
		}
	}

	// 崩溃日志分区：显示堆栈与设备信息，且 40 条分 3 页。
	rec = getModeration(t, api, "crashes", "2")
	body = rec.Body.String()
	if !strings.Contains(body, "OutOfMemoryError") || !strings.Contains(body, "Lenovo A288t") {
		t.Fatalf("crashes 应显示堆栈与设备信息")
	}
	if got := strings.Count(body, `class="body`); got != 15 {
		t.Fatalf("crashes 第二页卡片数 = %d, want 15", got)
	}

	// 第二页应显示 15 条卡片（每页 15 条），且与第一页不同。
	rec = getModeration(t, api, "bugs", "2")
	body = rec.Body.String()
	if !strings.Contains(body, "第 2 / 3 页") {
		t.Fatalf("bugs 第二页分页信息缺失")
	}
	if got := strings.Count(body, `class="status `); got != 15 {
		t.Fatalf("bugs 第二页卡片数 = %d, want 15", got)
	}

	// 越界页码应回落到最后一页且不报错。
	rec = getModeration(t, api, "bugs", "999")
	body = rec.Body.String()
	if rec.Code != http.StatusOK || !strings.Contains(body, "第 3 / 3 页") {
		t.Fatalf("越界页码未回落最后一页")
	}
	if got := strings.Count(body, `class="status `); got != 10 {
		t.Fatalf("bugs 最后一页卡片数 = %d, want 10 (40-15*2)", got)
	}
}

func TestModerationArchivedSection(t *testing.T) {
	api := newModerationTestAPI(t)
	seedModerationData(t, api, 40)
	// 处理前：三个主分区各 40 条未处理，已处理 0 条。
	rec := getModeration(t, api, "bugs", "")
	if !strings.Contains(rec.Body.String(), "40 待处理") {
		t.Fatalf("bugs Tab 应显示 40 待处理")
	}
	rec = getModeration(t, api, "archived", "")
	if !strings.Contains(rec.Body.String(), "暂无已处理条目") {
		t.Fatalf("archived 初始应为空")
	}

	// 标记部分已处理：bugs 8、appeals 5、reports 3 → 共 16 条已处理。
	markSomeHandled(t, api)

	// 主分区只显示未处理：bugs 40-8=32 → 3 页。
	rec = getModeration(t, api, "bugs", "")
	body := rec.Body.String()
	if !strings.Contains(body, "32 待处理") || !strings.Contains(body, "共 32 条") {
		t.Fatalf("bugs 未处理数应为 32: %q", body)
	}
	if !strings.Contains(body, "第 1 / 3 页") {
		t.Fatalf("bugs 32 条应分 3 页")
	}

	// archived：16 条已处理 → 2 页，聚合三类。
	rec = getModeration(t, api, "archived", "")
	body = rec.Body.String()
	if !strings.Contains(body, "16 条") || !strings.Contains(body, "共 16 条") {
		t.Fatalf("archived 总数应为 16: %q", body)
	}
	if !strings.Contains(body, "第 1 / 2 页") {
		t.Fatalf("archived 16 条应分 2 页")
	}
	if got := strings.Count(body, `class="status `); got != 15 {
		t.Fatalf("archived 第一页卡片数 = %d, want 15", got)
	}
	// 三类类型徽标齐全。
	if !strings.Contains(body, "COURT") || !strings.Contains(body, "REPORT") || !strings.Contains(body, "BUG ") {
		t.Fatalf("archived 应包含三种类型徽标")
	}
	// 已处理项带更新表单（可回改状态）。
	if !strings.Contains(body, "更新状态") {
		t.Fatalf("archived 应保留状态操作表单")
	}

	// archived 第二页只剩 1 条。
	rec = getModeration(t, api, "archived", "2")
	body = rec.Body.String()
	if !strings.Contains(body, "第 2 / 2 页") {
		t.Fatalf("archived 第二页分页信息缺失")
	}
	if got := strings.Count(body, `class="status `); got != 1 {
		t.Fatalf("archived 第二页卡片数 = %d, want 1", got)
	}
}

func postModerationReportBan(t *testing.T, api *API, id string, banUser, banDevice bool, duration string) *httptest.ResponseRecorder {
	t.Helper()
	form := url.Values{
		"id":             {id},
		"reason":         {"测试封禁"},
		"duration_hours": {duration},
	}
	if banUser {
		form.Set("ban_user", "1")
	}
	if banDevice {
		form.Set("ban_device", "1")
	}
	req := httptest.NewRequest(http.MethodPost, "https://example.com/admins/moderation/report/ban", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Origin", "https://example.com")
	addAdminSession(req, api)
	rec := httptest.NewRecorder()
	api.handleAdminModerationReportBan(rec, req)
	return rec
}

func TestModerationReportBan(t *testing.T) {
	api := newModerationTestAPI(t)
	ctx := context.Background()
	report := &data.UserReport{
		ReporterUID:  "REPORTER-1",
		TargetUID:    "TARGET-1",
		TargetDevice: "DEVICE-ABC-123",
		Reason:       "刷屏",
	}
	if err := api.reportStore.CreateUserReport(ctx, report); err != nil {
		t.Fatal(err)
	}
	// TargetUserID 为空时，封号走 TargetUID → GetByUID 反查。
	// 测试不建 users 表，因此封号会静默失败，只验证设备封禁与状态更新路径。
	rec := postModerationReportBan(t, api, report.ID, false, true, "48")
	if rec.Code != http.StatusSeeOther || !strings.Contains(rec.Header().Get("Location"), "ok=") {
		t.Fatalf("封禁响应异常：code=%d location=%q", rec.Code, rec.Header().Get("Location"))
	}
	updated, err := api.reportStore.GetUserReportByID(ctx, report.ID)
	if err != nil {
		t.Fatal(err)
	}
	if updated.Status != "resolved" {
		t.Fatalf("举报状态 = %q, want resolved", updated.Status)
	}
	if !strings.Contains(updated.Result, "封设备") {
		t.Fatalf("处理结果应记录封设备：%q", updated.Result)
	}
}

func TestModerationReportBanDeviceAndUser(t *testing.T) {
	api := newModerationTestAPI(t)
	ctx := context.Background()
	// 直接构造带 TargetUserID 的举报（绕过 users 表）。
	report := &data.UserReport{
		ReporterUID:  "REPORTER-2",
		TargetUserID: "USER-2",
		TargetUID:    "TARGET-2",
		TargetDevice: "DEVICE-222",
		Reason:       "骚扰",
	}
	if err := api.reportStore.CreateUserReport(ctx, report); err != nil {
		t.Fatal(err)
	}
	rec := postModerationReportBan(t, api, report.ID, true, true, "0")
	if rec.Code != http.StatusSeeOther || !strings.Contains(rec.Header().Get("Location"), "ok=") {
		t.Fatalf("封禁响应异常：code=%d location=%q", rec.Code, rec.Header().Get("Location"))
	}
	updated, err := api.reportStore.GetUserReportByID(ctx, report.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(updated.Result, "封号") || !strings.Contains(updated.Result, "封设备") {
		t.Fatalf("处理结果应记录封号+封设备：%q", updated.Result)
	}
}

func TestModerationPublicCourtReviewConditions(t *testing.T) {
	api := newModerationTestAPI(t)
	ctx := context.Background()

	// 1. 创建一个只有 2 票且处于 open 状态的案件
	case1 := &data.PublicCourtCase{
		ID:           "CASE-TEST-1",
		ReporterID:   "u1",
		ReporterUID:  "USR-1",
		DefendantID:  "u2",
		DefendantUID: "USR-2",
		ReportReason: "测试案件1",
		Status:       "open",
	}
	if _, _, err := api.publicCourt.OpenOrCreate(ctx, case1); err != nil {
		t.Fatal(err)
	}
	// 插入 2 票
	_, _ = api.db.Exec(`INSERT INTO public_court_votes (case_id, voter_id, vote) VALUES ('CASE-TEST-1', 'v1', 'ban'), ('CASE-TEST-1', 'v2', 'ban')`)

	// 尝试二审裁决 -> 应被拦截（未达 5 票且非待二审）
	form := url.Values{}
	form.Set("case_id", "CASE-TEST-1")
	form.Set("verdict", "ban")
	form.Set("ban_hours", "24")
	req := httptest.NewRequest(http.MethodPost, "/admins/moderation/public-court/review", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	cookie := api.adminSessions.New(adminSessionTTL)
	req.AddCookie(&http.Cookie{Name: adminCookie, Value: cookie})
	rec := httptest.NewRecorder()
	api.handleAdminModerationPublicCourtReview(rec, req)

	if !strings.Contains(rec.Header().Get("Location"), "err=") {
		t.Fatalf("少于 5 票且非待二审案件应禁止判决，location=%q", rec.Header().Get("Location"))
	}

	// 2. 插入满 5 票后再次尝试裁决 -> 应成功
	_, _ = api.db.Exec(`INSERT INTO public_court_votes (case_id, voter_id, vote) VALUES ('CASE-TEST-1', 'v3', 'ban'), ('CASE-TEST-1', 'v4', 'keep'), ('CASE-TEST-1', 'v5', 'keep')`)
	req2 := httptest.NewRequest(http.MethodPost, "/admins/moderation/public-court/review", strings.NewReader(form.Encode()))
	req2.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req2.AddCookie(&http.Cookie{Name: adminCookie, Value: cookie})
	rec2 := httptest.NewRecorder()
	api.handleAdminModerationPublicCourtReview(rec2, req2)

	if !strings.Contains(rec2.Header().Get("Location"), "ok=") {
		t.Fatalf("满 5 票案件应允许判决，location=%q", rec2.Header().Get("Location"))
	}

	// 3. 测试处于 pending_review（待二审）状态的案件，即使票数少于 5 票也应允许判决
	case2 := &data.PublicCourtCase{
		ID:           "CASE-TEST-2",
		ReporterID:   "u3",
		ReporterUID:  "USR-3",
		DefendantID:  "u4",
		DefendantUID: "USR-4",
		ReportReason: "测试案件2待二审",
		Status:       "open",
	}
	if _, _, err := api.publicCourt.OpenOrCreate(ctx, case2); err != nil {
		t.Fatal(err)
	}
	_, _ = api.db.Exec(`UPDATE public_court_cases SET status = 'pending_review' WHERE id = 'CASE-TEST-2'`)
	form2 := url.Values{}
	form2.Set("case_id", "CASE-TEST-2")
	form2.Set("verdict", "keep")
	req3 := httptest.NewRequest(http.MethodPost, "/admins/moderation/public-court/review", strings.NewReader(form2.Encode()))
	req3.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req3.AddCookie(&http.Cookie{Name: adminCookie, Value: cookie})
	rec3 := httptest.NewRecorder()
	api.handleAdminModerationPublicCourtReview(rec3, req3)

	if !strings.Contains(rec3.Header().Get("Location"), "ok=") {
		t.Fatalf("待二审状态案件应允许判决，location=%q", rec3.Header().Get("Location"))
	}
}
