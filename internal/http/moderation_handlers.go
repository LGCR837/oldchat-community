package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"metrochat/internal/data"
)

// moderationPageSize 审核台每个分区的每页条数。
const moderationPageSize = 15

// moderationPageData 内容审核台（法庭/举报/Bug 反馈/已处理/崩溃日志/公开法庭）数据。
type moderationPageData struct {
	Error              string
	Success            string
	ActiveSection      string
	CurrentUserName    string
	IsSuperAdmin       bool
	BanAppeals         []data.BanAppeal
	UserReports        []data.UserReport
	BugReports         []data.BugReport
	CrashReports       []data.CrashReport
	ArchivedItems      []moderationArchivedItem
	BotPermits         []moderationPermitItem
	ChannelPermits     []moderationPermitItem
	ActiveTokens       []moderationTokenItem
	CipItems           []data.CipStoreItem
	ModeratorLogs      []data.ModeratorActionLog
	PublicCourtCases     []moderationPublicCourtItem
	PublicCourtPending   int
	PublicCourtOpen      int
	PublicCourtClosed    int
	PublicCourtUnhandled int
	PublicCourtSub       string
	CipUnhandled         int
	TokensUnhandled    int
	CourtUnhandled     int
	ReportsUnhandled   int
	BugsUnhandled      int
	BotsUnhandled      int
	ChannelsUnhandled  int
	CrashTotal         int
	HandledTotal       int
	Page               int
	TotalPages         int
	Total              int
	Inspector          moderationInspectorData
}

// moderationInspectorData 查成分画像数据。
type moderationInspectorData struct {
	Query            string
	MatchedUsers     []data.User
	TargetUser       *data.User
	IsBanned         bool
	FriendsCount     int
	GroupsCount      int
	MomentsCount     int
	Moments          []moderationMomentItem
	MomentPage       int
	MomentTotalPages int
}

// moderationMomentItem 查成分动态单条。
type moderationMomentItem struct {
	Moment        data.Moment
	LikesCount    int
	CommentsCount int
	Comments      []data.MomentComment
}

// moderationPublicCourtItem 审核台公开法庭单条展示项。
type moderationPublicCourtItem struct {
	Summary                data.PublicCourtCaseSummary
	Votes                  []data.PublicCourtVote
	Discussions            []data.PublicCourtDiscussion
	Statements             []data.PublicCourtStatement
	MergedReports          []data.PublicCourtMergedReport
	DefendantReportedCount int
	DefendantBanCount      int
}

// moderationArchivedItem 已处理分区中聚合后的单条展示项。
type moderationPermitItem struct {
	ID           string    `db:"id"`
	UID          string    `db:"uid"`
	Username     string    `db:"username"`
	Status       string    `db:"status"`
	Scopes       string    `db:"scopes"`
	Reason       string    `db:"reason"`
	ReviewReason string    `db:"review_reason"`
	CreatedAt    time.Time `db:"created_at"`
}

// moderationTokenItem 审核台 tokens 分区中的单条 Token 展示项。
type moderationTokenItem struct {
	Kind        string     `db:"kind"` // bot | channel
	ID          string     `db:"id"`
	UID         string     `db:"uid"`
	Username    string     `db:"username"`
	Status      string     `db:"status"`
	Scopes      string     `db:"scopes"`
	Reason      string     `db:"reason"`
	TokenIssued bool       `db:"token_issued"`
	ExpiresAt   *time.Time `db:"expires_at"`
	LastUsedAt  *time.Time `db:"last_used_at"`
	CreatedAt   time.Time  `db:"created_at"`
}

type moderationArchivedItem struct {
	Kind            string // appeal | report | bug
	ID              string
	Title           string
	Meta            string
	Body            string
	StatusClass     string
	StatusText      string
	NoteValue       string
	NotePlaceholder string
	ActionURL       string
	ShowUnban       bool
	StatusOptions   []moderationStatusOption
	CreatedAt       time.Time
}

// moderationStatusOption 状态下拉选项。
type moderationStatusOption struct {
	Value    string
	Label    string
	Selected bool
}

// moderationStatusLabels 状态中文文案。
var moderationStatusLabels = map[string]string{
	"pending":    "待处理",
	"open":       "待处理",
	"processing": "处理中",
	"approved":   "已解决",
	"resolved":   "已解决",
	"rejected":   "驳回",
	"closed":     "已关闭",
	"applied":    "待审核",
	"revoked":    "已撤销",
}

func moderationStatusText(s string) string {
	if label, ok := moderationStatusLabels[s]; ok {
		return label
	}
	return s
}

// moderationStatusClass 状态样式 class（模板 .st-{{.}}）。
func moderationStatusClass(s string) string {
	switch s {
	case "approved", "resolved":
		return "resolved"
	case "rejected", "closed":
		return "rejected"
	}
	return "pending"
}

func publicCourtStatusText(s string) string {
	switch s {
	case "pending_review":
		return "待二审"
	case "open":
		return "审理中"
	case "closed":
		return "已结案"
	case "withdrawn":
		return "已撤诉"
	default:
		return s
	}
}

func publicCourtStatusClass(s string) string {
	switch s {
	case "pending_review":
		return "rejected"
	case "open":
		return "pending"
	case "closed":
		return "resolved"
	default:
		return "pending"
	}
}

// moderationTpl 内容审核台页面模板。
var moderationTpl = template.Must(template.New("moderation").Funcs(template.FuncMap{
	"add": func(a, b int) int { return a + b },
	"sum5": func(a, b, c, d, e int) int { return a + b + c + d + e },
	"sumAll": func(nums ...int) int {
		total := 0
		for _, n := range nums {
			total += n
		}
		return total
	},
	"sub":                    func(a, b int) int { return a - b },
	"divMul":                 func(a, b, mul int) int { if b <= 0 { return 0 }; return (a * mul) / b },
	"moderationStatusText":   moderationStatusText,
	"moderationStatusClass":  moderationStatusClass,
	"publicCourtStatusText":  publicCourtStatusText,
	"publicCourtStatusClass": publicCourtStatusClass,
}).Parse(moderationHTML))

func (a *API) logModeratorAction(r *http.Request, actionType, targetType, targetID, detail string) {
	if a.moderatorLogs == nil {
		return
	}
	modName, isSuper, ok := a.isModeratorOrAdmin(r)
	if !ok {
		return
	}
	modID := "admin"
	if !isSuper {
		if sess, ok := a.isModerator(r); ok {
			modID = sess.ModeratorID
		}
	}
	ip := clientIP(r)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = a.moderatorLogs.Log(ctx, modID, modName, actionType, targetType, targetID, detail, ip)
}

// normalizeModerationSection 归一化审核台分区，默认法庭。
func normalizeModerationSection(section string) string {
	switch section {
	case "public_court", "court", "reports", "bugs", "bots", "channels", "tokens", "crashes", "cip", "logs", "archived", "inspector":
		return section
	}
	return "public_court"
}

// handleAdminModeration 内容审核台：法庭申诉 / 用户举报 / Bug 反馈 / 已处理（需管理员登录）。
// 支持 ?section=court|reports|bugs|archived 分区与 ?page=N 分页。
// 前三个分区只显示未处理项，已处理项统一进入 archived 分区。
func (a *API) handleAdminModeration(w http.ResponseWriter, r *http.Request) {
	modName, isSuperAdmin, ok := a.isModeratorOrAdmin(r)
	if !ok {
		http.Redirect(w, r, "/admins/moderation/login", http.StatusSeeOther)
		return
	}
	section := normalizeModerationSection(strings.TrimSpace(r.URL.Query().Get("section")))
	page := 1
	if raw := strings.TrimSpace(r.URL.Query().Get("page")); raw != "" {
		if p, err := strconv.Atoi(raw); err == nil && p > 1 {
			page = p
		}
	}
	if page > 100000 {
		page = 100000
	}
	offset := (page - 1) * moderationPageSize

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	pageData := moderationPageData{
		Error:           strings.TrimSpace(r.URL.Query().Get("err")),
		Success:         strings.TrimSpace(r.URL.Query().Get("ok")),
		ActiveSection:   section,
		CurrentUserName: modName,
		IsSuperAdmin:    isSuperAdmin,
		Page:            page,
	}
	// 各分区未处理计数（Tab 徽标用）。
	pageData.CourtUnhandled, _ = a.banAppeals.CountByHandled(ctx, false)
	pageData.ReportsUnhandled, _ = a.reportStore.CountUserReportsByHandled(ctx, false)
	pageData.BugsUnhandled, _ = a.bugReportStore.CountByHandled(ctx, false)
	pageData.HandledTotal, _ = a.handledTotal(ctx)
	pageData.CrashTotal, _ = a.crashReportStore.Count(ctx)
	pageData.BotsUnhandled = a.countModerationPermits(ctx, "bot", true)
	pageData.ChannelsUnhandled = a.countModerationPermits(ctx, "channel", true)
	if a.publicCourt != nil {
		pageData.PublicCourtPending, _ = a.publicCourt.CountCasesByStatus(ctx, "pending_review")
		pageData.PublicCourtOpen, _ = a.publicCourt.CountCasesByStatus(ctx, "open")
		pageData.PublicCourtClosed, _ = a.publicCourt.CountCasesByStatus(ctx, "closed")
		pageData.PublicCourtUnhandled = pageData.PublicCourtPending
	}
	if a.cipStore != nil {
		pendingCips, _ := a.cipStore.ListSubmissions(ctx, "pending", 100, 0)
		pageData.CipUnhandled = len(pendingCips)
	}

	switch section {
	case "public_court":
		sub := strings.TrimSpace(r.URL.Query().Get("sub"))
		if sub != "pending_review" && sub != "open" && sub != "closed" {
			sub = "all"
		}
		pageData.PublicCourtSub = sub
		if a.publicCourt != nil {
			total, _ := a.publicCourt.CountCasesByStatus(ctx, sub)
			pageData.Total = total
			summaries, _ := a.publicCourt.ListCasesPage(ctx, sub, moderationPageSize, offset)
			pageData.PublicCourtCases = a.loadPublicCourtCaseItems(ctx, summaries)
		}
	case "court":
		total, _ := a.banAppeals.CountByHandled(ctx, false)
		appeals, _ := a.banAppeals.ListByHandledPage(ctx, false, moderationPageSize, offset)
		if appeals == nil {
			appeals = []data.BanAppeal{}
		}
		pageData.BanAppeals = appeals
		pageData.Total = total
	case "reports":
		total, _ := a.reportStore.CountUserReportsByHandled(ctx, false)
		reports, _ := a.reportStore.ListUserReportsByHandledPage(ctx, false, moderationPageSize, offset)
		if reports == nil {
			reports = []data.UserReport{}
		}
		pageData.UserReports = reports
		pageData.Total = total
	case "bugs":
		total, _ := a.bugReportStore.CountByHandled(ctx, false)
		bugs, _ := a.bugReportStore.ListByHandledPage(ctx, false, moderationPageSize, offset)
		if bugs == nil {
			bugs = []data.BugReport{}
		}
		pageData.BugReports = bugs
		pageData.Total = total
	case "bots":
		pageData.Total = a.countModerationPermits(ctx, "bot", false)
		pageData.BotPermits = a.listModerationPermits(ctx, "bot", moderationPageSize, offset)
	case "channels":
		pageData.Total = a.countModerationPermits(ctx, "channel", false)
		pageData.ChannelPermits = a.listModerationPermits(ctx, "channel", moderationPageSize, offset)
	case "tokens":
		pageData.Total = a.countActiveTokens(ctx)
		pageData.ActiveTokens = a.listActiveTokens(ctx, moderationPageSize, offset)
	case "cip":
		if a.cipStore != nil {
			items, _ := a.cipStore.ListSubmissions(ctx, "all", moderationPageSize, offset)
			if items == nil {
				items = []data.CipStoreItem{}
			}
			pageData.CipItems = items
			allSubmissions, _ := a.cipStore.ListSubmissions(ctx, "all", 10000, 0)
			pageData.Total = len(allSubmissions)
		}
	case "crashes":
		total, _ := a.crashReportStore.Count(ctx)
		crashes, _ := a.crashReportStore.ListRecentPage(ctx, moderationPageSize, offset)
		if crashes == nil {
			crashes = []data.CrashReport{}
		}
		pageData.CrashReports = crashes
		pageData.Total = total
	case "logs":
		if a.moderatorLogs != nil {
			total, _ := a.moderatorLogs.Count(ctx)
			logs, _ := a.moderatorLogs.ListRecentPage(ctx, moderationPageSize, offset)
			if logs == nil {
				logs = []data.ModeratorActionLog{}
			}
			pageData.ModeratorLogs = logs
			pageData.Total = total
		}
	case "archived":
		total, _ := a.handledTotal(ctx)
		pageData.ArchivedItems, pageData.Total = a.buildArchivedPage(ctx, offset, total)
	case "inspector":
		query := strings.TrimSpace(r.URL.Query().Get("q"))
		targetUserID := strings.TrimSpace(r.URL.Query().Get("user_id"))
		mPage := 1
		if raw := strings.TrimSpace(r.URL.Query().Get("m_page")); raw != "" {
			if p, err := strconv.Atoi(raw); err == nil && p > 1 {
				mPage = p
			}
		}
		if mPage > 10000 {
			mPage = 10000
		}
		inspector := moderationInspectorData{
			Query:      query,
			MomentPage: mPage,
		}

		var targetUser *data.User
		if targetUserID != "" {
			targetUser, _ = a.users.GetByID(ctx, targetUserID)
		}

		if query != "" {
			matched, _ := a.users.SearchUsersForInspector(ctx, query, 20)
			inspector.MatchedUsers = matched
			if targetUser == nil && len(matched) > 0 {
				targetUser = &matched[0]
			}
		}

		if targetUser != nil {
			inspector.TargetUser = targetUser
			inspector.IsBanned, _ = a.users.IsUserBanned(ctx, targetUser.ID)
			inspector.FriendsCount, _ = a.users.CountFriends(ctx, targetUser.ID)
			inspector.GroupsCount, _ = a.users.CountJoinedGroups(ctx, targetUser.ID)
			mTotal, _ := a.moments.CountByUserID(ctx, targetUser.ID)
			inspector.MomentsCount = mTotal
			inspector.MomentTotalPages = (mTotal + moderationPageSize - 1) / moderationPageSize
			if inspector.MomentTotalPages < 1 {
				inspector.MomentTotalPages = 1
			}
			mOffset := (mPage - 1) * moderationPageSize
			rawMoments, _ := a.moments.ListByUsersWithOffset(ctx, []string{targetUser.ID}, moderationPageSize, mOffset)
			momentItems := make([]moderationMomentItem, 0, len(rawMoments))
			if len(rawMoments) > 0 {
				momentIDs := make([]string, len(rawMoments))
				for i, m := range rawMoments {
					momentIDs[i] = m.ID
				}
				likesMap, _ := a.moments.CountLikes(ctx, momentIDs)
				commentsCountMap, _ := a.moments.CountComments(ctx, momentIDs)
				for _, m := range rawMoments {
					item := moderationMomentItem{
						Moment:        m,
						LikesCount:    likesMap[m.ID],
						CommentsCount: commentsCountMap[m.ID],
					}
					item.Comments, _ = a.moments.ListComments(ctx, m.ID, 100, time.Time{})
					momentItems = append(momentItems, item)
				}
			}
			inspector.Moments = momentItems
		}
		pageData.Inspector = inspector
	}
	pageData.TotalPages = (pageData.Total + moderationPageSize - 1) / moderationPageSize
	if pageData.TotalPages < 1 {
		pageData.TotalPages = 1
	}
	if page > pageData.TotalPages {
		page = pageData.TotalPages
		pageData.Page = page
		offset = (page - 1) * moderationPageSize
		// 越界时重新查询最后一页。
		switch section {
		case "public_court":
			sub := pageData.PublicCourtSub
			if sub == "" {
				sub = "all"
			}
			if a.publicCourt != nil {
				summaries, _ := a.publicCourt.ListCasesPage(ctx, sub, moderationPageSize, offset)
				pageData.PublicCourtCases = a.loadPublicCourtCaseItems(ctx, summaries)
			}
		case "court":
			appeals, _ := a.banAppeals.ListByHandledPage(ctx, false, moderationPageSize, offset)
			if appeals == nil {
				appeals = []data.BanAppeal{}
			}
			pageData.BanAppeals = appeals
		case "reports":
			reports, _ := a.reportStore.ListUserReportsByHandledPage(ctx, false, moderationPageSize, offset)
			if reports == nil {
				reports = []data.UserReport{}
			}
			pageData.UserReports = reports
		case "bugs":
			bugs, _ := a.bugReportStore.ListByHandledPage(ctx, false, moderationPageSize, offset)
			if bugs == nil {
				bugs = []data.BugReport{}
			}
			pageData.BugReports = bugs
		case "bots":
			pageData.BotPermits = a.listModerationPermits(ctx, "bot", moderationPageSize, offset)
		case "channels":
			pageData.ChannelPermits = a.listModerationPermits(ctx, "channel", moderationPageSize, offset)
		case "tokens":
			pageData.ActiveTokens = a.listActiveTokens(ctx, moderationPageSize, offset)
		case "cip":
			if a.cipStore != nil {
				items, _ := a.cipStore.ListSubmissions(ctx, "all", moderationPageSize, offset)
				if items == nil {
					items = []data.CipStoreItem{}
				}
				pageData.CipItems = items
			}
		case "crashes":
			crashes, _ := a.crashReportStore.ListRecentPage(ctx, moderationPageSize, offset)
			if crashes == nil {
				crashes = []data.CrashReport{}
			}
			pageData.CrashReports = crashes
		case "logs":
			if a.moderatorLogs != nil {
				logs, _ := a.moderatorLogs.ListRecentPage(ctx, moderationPageSize, offset)
				if logs == nil {
					logs = []data.ModeratorActionLog{}
				}
				pageData.ModeratorLogs = logs
			}
		case "archived":
			total, _ := a.handledTotal(ctx)
			pageData.ArchivedItems, pageData.Total = a.buildArchivedPage(ctx, offset, total)
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = moderationTpl.Execute(w, pageData)
}

// handleAdminModerationInspectorDeleteMoment 审核员在查成分页面删除指定违规动态。
func (a *API) handleAdminModerationInspectorDeleteMoment(w http.ResponseWriter, r *http.Request) {
	_, _, ok := a.isModeratorOrAdmin(r)
	if !ok {
		http.Redirect(w, r, "/admins/moderation/login", http.StatusSeeOther)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	_ = r.ParseMultipartForm(64 << 10)
	_ = r.ParseForm()
	momentID := strings.TrimSpace(r.FormValue("moment_id"))
	userID := strings.TrimSpace(r.FormValue("user_id"))
	q := strings.TrimSpace(r.FormValue("q"))
	mPage := strings.TrimSpace(r.FormValue("m_page"))
	if momentID == "" {
		http.Redirect(w, r, "/admins/moderation?section=inspector&err=missing_moment_id", http.StatusSeeOther)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.moments.Delete(ctx, momentID); err != nil {
		http.Redirect(w, r, "/admins/moderation?section=inspector&user_id="+url.QueryEscape(userID)+"&q="+url.QueryEscape(q)+"&m_page="+url.QueryEscape(mPage)+"&err=delete_failed", http.StatusSeeOther)
		return
	}
	a.logModeratorAction(r, "delete_moment", "moment", momentID, "从查成分页面删除动态: "+momentID)
	http.Redirect(w, r, "/admins/moderation?section=inspector&user_id="+url.QueryEscape(userID)+"&q="+url.QueryEscape(q)+"&m_page="+url.QueryEscape(mPage)+"&ok=moment_deleted", http.StatusSeeOther)
}


func (a *API) loadPublicCourtCaseItems(ctx context.Context, summaries []data.PublicCourtCaseSummary) []moderationPublicCourtItem {
	if a.publicCourt == nil || len(summaries) == 0 {
		return []moderationPublicCourtItem{}
	}
	items := make([]moderationPublicCourtItem, 0, len(summaries))
	for _, sum := range summaries {
		votes, _ := a.publicCourt.ListRecentVotes(ctx, sum.ID, 50)
		if votes == nil {
			votes = []data.PublicCourtVote{}
		}
		discussions, _ := a.publicCourt.ListDiscussions(ctx, sum.ID, 50)
		if discussions == nil {
			discussions = []data.PublicCourtDiscussion{}
		}
		statements, _ := a.publicCourt.ListStatements(ctx, sum.ID, 50)
		if statements == nil {
			statements = []data.PublicCourtStatement{}
		}
		mergedReports, _, _ := a.publicCourt.ListMergedReports(ctx, sum.ID, 1, 50)
		if mergedReports == nil {
			mergedReports = []data.PublicCourtMergedReport{}
		}

		var defendantReportedCount int
		_ = a.db.GetContext(ctx, &defendantReportedCount, `
			SELECT COUNT(1) FROM user_reports
			WHERE (target_user_id != '' AND target_user_id = $1)
			   OR (target_uid != '' AND target_uid = $2)
		`, sum.DefendantID, sum.DefendantUID)

		var defendantBanCount int
		_ = a.db.GetContext(ctx, &defendantBanCount, `
			SELECT COALESCE(ban_count, 0) FROM users
			WHERE (id != '' AND id = $1) OR (uid != '' AND uid = $2)
			LIMIT 1
		`, sum.DefendantID, sum.DefendantUID)

		items = append(items, moderationPublicCourtItem{
			Summary:                sum,
			Votes:                  votes,
			Discussions:            discussions,
			Statements:             statements,
			MergedReports:          mergedReports,
			DefendantReportedCount: defendantReportedCount,
			DefendantBanCount:      defendantBanCount,
		})
	}
	return items
}

func (a *API) respondModerationAction(w http.ResponseWriter, r *http.Request, redirectURL, message string) {
	if r.Header.Get("X-Requested-With") == "XMLHttpRequest" || strings.Contains(r.Header.Get("Accept"), "application/json") {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = jsonNewEncoder(w).Encode(map[string]interface{}{
			"ok":      true,
			"message": message,
		})
		return
	}
	http.Redirect(w, r, redirectURL, http.StatusSeeOther)
}

func (a *API) respondModerationError(w http.ResponseWriter, r *http.Request, redirectURL, errorMsg string) {
	if r.Header.Get("X-Requested-With") == "XMLHttpRequest" || strings.Contains(r.Header.Get("Accept"), "application/json") {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusBadRequest)
		_ = jsonNewEncoder(w).Encode(map[string]interface{}{
			"ok":      false,
			"error":   errorMsg,
			"message": errorMsg,
		})
		return
	}
	http.Redirect(w, r, redirectURL, http.StatusSeeOther)
}

// handleAdminModerationPublicCourtReview 管理员在审核台二审/终审公开法庭案件
func (a *API) handleAdminModerationPublicCourtReview(w http.ResponseWriter, r *http.Request) {
	if _, _, ok := a.isModeratorOrAdmin(r); !ok {
		a.respondModerationError(w, r, "/admins/moderation/login", "未登录审核台或管理后台")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	_ = r.ParseMultipartForm(64 << 10)
	_ = r.ParseForm()
	caseID := strings.TrimSpace(r.FormValue("case_id"))
	if caseID == "" {
		caseID = strings.TrimSpace(r.FormValue("id")) // 兼容旧版页面字段名
	}
	if caseID == "" {
		caseID = strings.TrimSpace(r.FormValue("caseId")) // 兼容更早的驼峰字段名
	}
	if caseID == "" {
		caseID = strings.TrimSpace(r.FormValue("court_id"))
	}
	verdict := strings.TrimSpace(r.FormValue("verdict"))
	adminNote := strings.TrimSpace(r.FormValue("admin_note"))
	banHours := 0
	if verdict == "ban" {
		banHoursStr := strings.TrimSpace(r.FormValue("ban_hours"))
		banHours, _ = strconv.Atoi(banHoursStr)
		if banHours <= 0 {
			banHours = 24
		}
	}

	if caseID == "" || (verdict != "ban" && verdict != "keep") {
		// 诊断日志：打印收到的表单字段名（不打印值，避免敏感信息），
		// 用于排查旧缓存页面字段名不匹配导致的"请选择裁决结果并提供案件ID"。
		var keys []string
		for k := range r.Form {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		log.Printf("moderation public-court review rejected: case_id=%q verdict=%q form_fields=%v", caseID, verdict, keys)
		a.respondModerationError(w, r, "/admins/moderation?section=public_court&err="+url.QueryEscape("请选择裁决结果并提供案件ID"), "请选择裁决结果并提供案件ID")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()

	if a.publicCourt == nil {
		a.respondModerationError(w, r, "/admins/moderation?section=public_court&err="+url.QueryEscape("公开法庭模块未就绪"), "公开法庭模块未就绪")
		return
	}

	courtCase, err := a.publicCourt.GetByID(ctx, caseID)
	if err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=public_court&err="+url.QueryEscape("案件不存在: "+err.Error()), "案件不存在: "+err.Error())
		return
	}

	var voteCount int
	if a.db != nil {
		_ = a.db.GetContext(ctx, &voteCount, `SELECT COUNT(1) FROM public_court_votes WHERE case_id = $1`, caseID)
	}

	isPendingReview := courtCase.Status == "pending_review"
	hasEnoughVotes := voteCount >= 5
	if !isPendingReview && !hasEnoughVotes {
		errMsg := fmt.Sprintf("案件尚未满足裁决条件：当前投票数（%d 票）未达到 5 票且未进入待二审状态，暂不可进行裁决", voteCount)
		a.respondModerationError(w, r, "/admins/moderation?section=public_court&err="+url.QueryEscape(errMsg), errMsg)
		return
	}

	takedownResource := verdict == "ban" && r.FormValue("takedown_resource") != "" && r.FormValue("takedown_resource") != "0"
	var takedownActions []string
	if takedownResource {
		combinedReportText := courtCase.ReportReason + "\n" + courtCase.ReportEvidence
		if mergedReports, _, err := a.publicCourt.ListMergedReports(ctx, caseID, 1, 100); err == nil && len(mergedReports) > 0 {
			for _, mr := range mergedReports {
				combinedReportText += "\n" + mr.Reason
			}
		}
		takedownActions = a.takedownOffendingContent(ctx, combinedReportText)
	}

	finalAdminNote := adminNote
	if len(takedownActions) > 0 {
		if strings.TrimSpace(finalAdminNote) != "" {
			finalAdminNote = finalAdminNote + "；" + strings.Join(takedownActions, "；")
		} else {
			finalAdminNote = strings.Join(takedownActions, "；")
		}
	}

	_, err = a.publicCourt.FinalizeCase(ctx, caseID, verdict, finalAdminNote, banHours)
	if err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=public_court&err="+url.QueryEscape("二审裁决失败: "+err.Error()), "二审裁决失败: "+err.Error())
		return
	}

	// 若裁决为封禁，立即封禁被举报人
	if verdict == "ban" && a.devices != nil && courtCase.DefendantID != "" {
		banReason := "公开法庭案件 #" + caseID + " 裁决封禁: " + finalAdminNote
		if strings.TrimSpace(finalAdminNote) == "" {
			banReason = "公开法庭案件 #" + caseID + " 裁决封禁"
		}
		_ = a.devices.BanUser(ctx, courtCase.DefendantID, banReason, banHours)
	}

	if verdict == "keep" {
		a.logModeratorAction(r, "public_court_review", "case", caseID, "二审裁决案件 #"+caseID+" 为 维持无罪/不封禁(keep)，备注: "+finalAdminNote)
	} else {
		a.logModeratorAction(r, "public_court_review", "case", caseID, "二审裁决案件 #"+caseID+" 为 违规封禁(ban)（封禁"+strconv.Itoa(banHours)+"h），备注: "+finalAdminNote)
	}

	msg := "案件 #" + caseID + " 已成功完成二审裁决（" + verdict + "）并结案"
	if len(takedownActions) > 0 {
		msg += "（已同步：" + strings.Join(takedownActions, "；") + "）"
	}
	a.respondModerationAction(w, r, "/admins/moderation?section=public_court&ok="+url.QueryEscape(msg), msg)
}

func (a *API) countModerationPermits(ctx context.Context, kind string, pendingOnly bool) int {
	if a.db == nil {
		return 0
	}
	table := "bot_permits"
	if kind == "channel" {
		table = "channel_api_permits"
	}
	query := "SELECT COUNT(1) FROM " + table
	if pendingOnly {
		query += " WHERE status='applied'"
	}
	var count int
	_ = a.db.GetContext(ctx, &count, query)
	return count
}

func (a *API) listModerationPermits(ctx context.Context, kind string, limit, offset int) []moderationPermitItem {
	rows := []moderationPermitItem{}
	if a.db == nil {
		return rows
	}
	table := "bot_permits"
	if kind == "channel" {
		table = "channel_api_permits"
	}
	query := `SELECT p.id,u.uid,u.username,p.status,p.scopes,p.reason,p.review_reason,p.created_at
FROM ` + table + ` p JOIN users u ON u.id=p.user_id
ORDER BY CASE WHEN p.status='applied' THEN 0 ELSE 1 END,p.created_at DESC LIMIT $1 OFFSET $2`
	_ = a.db.SelectContext(ctx, &rows, query, limit, offset)
	return rows
}

// countActiveTokens 统计已领取且仍有效的 Bot/Channel Token 总数。
func (a *API) countActiveTokens(ctx context.Context) int {
	if a.db == nil {
		return 0
	}
	var count int
	_ = a.db.GetContext(ctx, &count, `SELECT COUNT(1) FROM (
SELECT id FROM bot_permits WHERE status='approved' AND token_hash!=''
UNION ALL
SELECT id FROM channel_api_permits WHERE status='approved' AND token_hash!=''
)`)
	return count
}

// listActiveTokens 列出已领取且仍有效的 Bot/Channel Token（含到期、最近使用时间）。
func (a *API) listActiveTokens(ctx context.Context, limit, offset int) []moderationTokenItem {
	rows := []moderationTokenItem{}
	if a.db == nil {
		return rows
	}
	query := `SELECT * FROM (
SELECT 'bot' AS kind,p.id,u.uid,u.username,p.status,p.scopes,p.reason,
CASE WHEN p.token_hash='' THEN 0 ELSE 1 END AS token_issued,
p.expires_at,p.last_used_at,p.created_at
FROM bot_permits p JOIN users u ON u.id=p.user_id
WHERE p.status='approved' AND p.token_hash!=''
UNION ALL
SELECT 'channel' AS kind,p.id,u.uid,u.username,p.status,p.scopes,p.reason,
CASE WHEN p.token_hash='' THEN 0 ELSE 1 END AS token_issued,
p.expires_at,p.last_used_at,p.created_at
FROM channel_api_permits p JOIN users u ON u.id=p.user_id
WHERE p.status='approved' AND p.token_hash!=''
) ORDER BY created_at DESC LIMIT $1 OFFSET $2`
	_ = a.db.SelectContext(ctx, &rows, query, limit, offset)
	return rows
}

// handleAdminModerationTokenRevoke 审核台：停用（撤销）Bot/Channel Token。
func (a *API) handleAdminModerationTokenRevoke(w http.ResponseWriter, r *http.Request) {
	if !a.ensureModerationPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	kind := strings.ToLower(strings.TrimSpace(r.FormValue("kind")))
	if id == "" || (kind != "bot" && kind != "channel") {
		a.respondModerationError(w, r, "/admins/moderation?section=tokens&err="+url.QueryEscape("停用参数无效")+"#tokens", "停用参数无效")
		return
	}
	table := "bot_permits"
	if kind == "channel" {
		table = "channel_api_permits"
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	result, err := a.db.ExecContext(ctx, `UPDATE `+table+` SET status='revoked',review_reason='管理员停用',reviewed_at=CURRENT_TIMESTAMP WHERE id=$1 AND status='approved' AND token_hash!=''`, id)
	if err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=tokens&err="+url.QueryEscape("停用失败，请重试")+"#tokens", "停用失败，请重试")
		return
	}
	affected, _ := result.RowsAffected()
	if affected != 1 {
		a.respondModerationError(w, r, "/admins/moderation?section=tokens&err="+url.QueryEscape("Token 不存在或已停用")+"#tokens", "Token 不存在或已停用")
		return
	}
	a.logModeratorAction(r, "token_revoke", kind, id, "停用 "+kind+" Token (ID: "+id+")")
	a.respondModerationAction(w, r, "/admins/moderation?section=tokens&ok="+url.QueryEscape("Token 已停用")+"#tokens", "Token 已停用")
}

// handledTotal 返回三类已处理条目总数。
func (a *API) handledTotal(ctx context.Context) (int, error) {
	appeals, _ := a.banAppeals.CountByHandled(ctx, true)
	reports, _ := a.reportStore.CountUserReportsByHandled(ctx, true)
	bugs, _ := a.bugReportStore.CountByHandled(ctx, true)
	return appeals + reports + bugs, nil
}

// buildArchivedPage 聚合三类已处理条目，按时间倒序取当前页。
// 每类最多查询 offset+pageSize 条，合并排序后截取 [offset, offset+pageSize)。
func (a *API) buildArchivedPage(ctx context.Context, offset, total int) ([]moderationArchivedItem, int) {
	fetchLimit := offset + moderationPageSize
	items := make([]moderationArchivedItem, 0, moderationPageSize)

	appeals, _ := a.banAppeals.ListByHandledPage(ctx, true, fetchLimit, 0)
	for i := range appeals {
		items = append(items, moderationArchivedItem{
			Kind:            "appeal",
			ID:              appeals[i].ID,
			Title:           appeals[i].Username + " · " + appeals[i].UID,
			Meta:            "封禁原因: " + appeals[i].BanReason,
			Body:            appeals[i].AppealText,
			StatusClass:     moderationStatusClass(appeals[i].Status),
			StatusText:      moderationStatusText(appeals[i].Status),
			NoteValue:       appeals[i].AdminNote,
			NotePlaceholder: "处理备注",
			ActionURL:       "/admins/moderation/appeal/status",
			ShowUnban:       true,
			CreatedAt:       appeals[i].CreatedAt,
			StatusOptions: []moderationStatusOption{
				{Value: "pending", Label: "待处理", Selected: appeals[i].Status == "pending"},
				{Value: "processing", Label: "处理中", Selected: appeals[i].Status == "processing"},
				{Value: "approved", Label: "通过（解封）", Selected: appeals[i].Status == "approved"},
				{Value: "rejected", Label: "驳回", Selected: appeals[i].Status == "rejected"},
			},
		})
	}

	reports, _ := a.reportStore.ListUserReportsByHandledPage(ctx, true, fetchLimit, 0)
	for i := range reports {
		metaText := "举报人: " + reports[i].ReporterUID
		if reports[i].TargetReportedCount > 0 {
			metaText += " · 历史被举报: " + strconv.Itoa(reports[i].TargetReportedCount) + " 次"
		}
		if reports[i].TargetBanCount > 0 {
			metaText += " · 历史封禁: " + strconv.Itoa(reports[i].TargetBanCount) + " 次"
		}
		metaText += " · 目标设备: " + reports[i].TargetDevice
		items = append(items, moderationArchivedItem{
			Kind:            "report",
			ID:              reports[i].ID,
			Title:           "举报 " + reports[i].TargetUID,
			Meta:            metaText,
			Body:            reports[i].Reason,
			StatusClass:     moderationStatusClass(reports[i].Status),
			StatusText:      moderationStatusText(reports[i].Status),
			NoteValue:       reports[i].Result,
			NotePlaceholder: "处理结果",
			ActionURL:       "/admins/moderation/report/status",
			CreatedAt:       reports[i].CreatedAt,
			StatusOptions: []moderationStatusOption{
				{Value: "pending", Label: "待处理", Selected: reports[i].Status == "pending"},
				{Value: "processing", Label: "处理中", Selected: reports[i].Status == "processing"},
				{Value: "resolved", Label: "已处理", Selected: reports[i].Status == "resolved"},
				{Value: "rejected", Label: "无效举报", Selected: reports[i].Status == "rejected"},
			},
		})
	}

	bugs, _ := a.bugReportStore.ListByHandledPage(ctx, true, fetchLimit, 0)
	for i := range bugs {
		items = append(items, moderationArchivedItem{
			Kind:            "bug",
			ID:              bugs[i].ID,
			Title:           bugs[i].UserUID,
			Meta:            bugs[i].DeviceModel + " · Android " + bugs[i].AndroidVersion + " · v" + bugs[i].AppVersion,
			Body:            bugs[i].Content,
			StatusClass:     moderationStatusClass(bugs[i].Status),
			StatusText:      moderationStatusText(bugs[i].Status),
			NoteValue:       bugs[i].AdminNote,
			NotePlaceholder: "处理备注",
			ActionURL:       "/admins/moderation/bug/status",
			CreatedAt:       bugs[i].CreatedAt,
			StatusOptions: []moderationStatusOption{
				{Value: "open", Label: "待处理", Selected: bugs[i].Status == "open"},
				{Value: "resolved", Label: "已解决", Selected: bugs[i].Status == "resolved"},
				{Value: "closed", Label: "已关闭", Selected: bugs[i].Status == "closed"},
			},
		})
	}

	// 按时间倒序稳定排序。
	sort.SliceStable(items, func(i, j int) bool {
		return items[i].CreatedAt.After(items[j].CreatedAt)
	})
	if offset >= total {
		return []moderationArchivedItem{}, total
	}
	end := offset + moderationPageSize
	if end > total {
		end = total
	}
	return items[offset:end], total
}

// handleAdminModerationReportStatus 审核台：标记举报状态。
func (a *API) handleAdminModerationReportStatus(w http.ResponseWriter, r *http.Request) {
	if !a.ensureModerationPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	if id == "" {
		id = strings.TrimSpace(r.FormValue("report_id")) // 兼容旧版页面字段名
	}
	status := normalizeAdminReportStatus(r.FormValue("status"))
	result := strings.TrimSpace(r.FormValue("result"))
	takedownResource := r.FormValue("takedown_resource") != "" && r.FormValue("takedown_resource") != "0"
	if id == "" {
		a.respondModerationError(w, r, "/admins/moderation?section=reports&err="+url.QueryEscape("缺少举报ID")+"#reports", "缺少举报ID")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()

	var takedownActions []string
	if takedownResource {
		if report, err := a.reportStore.GetUserReportByID(ctx, id); err == nil && report != nil {
			takedownActions = a.takedownOffendingContent(ctx, report.Reason)
		}
	}

	if len(takedownActions) > 0 {
		if result != "" {
			result += "；"
		}
		result += strings.Join(takedownActions, "；")
	}

	report, _ := a.reportStore.GetUserReportByID(ctx, id)
	_ = a.reportStore.SetUserReportStatus(ctx, id, status, result)
	if report != nil && report.ReporterID != "" && status != "pending" {
		statusText := "已处理"
		if status == "rejected" {
			statusText = "已驳回"
		} else if status == "resolved" {
			statusText = "已采纳并处置"
		}
		targetName := report.TargetUID
		if targetName == "" {
			targetName = report.TargetUserID
		}
		resDesc := result
		if resDesc == "" {
			resDesc = "已完成审核"
		}
		body := fmt.Sprintf("【举报审核结果通知】\n您对用户 [%s] 发起的违规举报已完成审核。\n审核状态：%s\n处理结果说明：%s", targetName, statusText, resDesc)
		_ = a.sendSystemNotification(ctx, report.ReporterID, "举报审核结果通知", body, false)
	}
	a.logModeratorAction(r, "report_status", "report", id, "更新举报 #"+id+" 状态为 "+status+"，结论: "+result)
	msg := "举报状态已更新"
	if len(takedownActions) > 0 {
		msg += "（" + strings.Join(takedownActions, "；") + "）"
	}
	a.respondModerationAction(w, r, "/admins/moderation?section=reports&ok="+url.QueryEscape(msg)+"#reports", msg)
}

// handleAdminModerationReportBan 审核台：处理举报时直接封号/封设备/下架涉案违规资源。
// 表单字段：id（举报ID）、ban_user、ban_device、takedown_resource（勾选）、duration_hours（0=永久）、reason。
func (a *API) handleAdminModerationReportBan(w http.ResponseWriter, r *http.Request) {
	if !a.ensureModerationPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	if id == "" {
		id = strings.TrimSpace(r.FormValue("report_id")) // 兼容旧版页面字段名
	}
	if id == "" {
		a.respondModerationError(w, r, "/admins/moderation?section=reports&err="+url.QueryEscape("缺少举报ID")+"#reports", "缺少举报ID")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()

	report, err := a.reportStore.GetUserReportByID(ctx, id)
	if err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=reports&err="+url.QueryEscape("举报不存在")+"#reports", "举报不存在")
		return
	}

	banUser := r.FormValue("ban_user") != "" && r.FormValue("ban_user") != "0"
	banDevice := r.FormValue("ban_device") != "" && r.FormValue("ban_device") != "0"
	takedownResource := r.FormValue("takedown_resource") != "" && r.FormValue("takedown_resource") != "0"
	duration := parseDurationHours(r.FormValue("duration_hours"))
	reason := strings.TrimSpace(r.FormValue("reason"))
	if reason == "" {
		reason = "举报处理：封禁"
	}

	actions := []string{}
	// 封号：优先用 target_user_id，缺失时用 target_uid 反查。
	userID := strings.TrimSpace(report.TargetUserID)
	if banUser {
		if userID == "" && strings.TrimSpace(report.TargetUID) != "" {
			if u, err := a.users.GetByUID(ctx, strings.TrimSpace(report.TargetUID)); err == nil {
				userID = u.ID
			}
		}
		if userID != "" {
			if err := a.devices.BanUser(ctx, userID, reason, duration); err == nil {
				_, _ = a.db.ExecContext(ctx, `
UPDATE users
SET reputation_score = reputation_score - 100,
    updated_at = CURRENT_TIMESTAMP
WHERE id = $1
`, userID)
				a.clearBanStatusCache(userID)
				actions = append(actions, "封号(扣100信誉)")
				durDesc := "永久封禁"
				if duration > 0 {
					durDesc = fmt.Sprintf("封禁 %d 小时", duration)
				}
				punishBody := fmt.Sprintf("【账号处罚通知】\n因违规行为举报成立，您的账号已被处罚：%s。\n处罚原因：%s\n同时扣除信誉分 100 分。", durDesc, reason)
				_ = a.sendSystemNotification(ctx, userID, "账号处罚与信誉分扣除通知", punishBody, true)
			}
		}
	}
	// 封设备。
	if banDevice && strings.TrimSpace(report.TargetDevice) != "" {
		if err := a.devices.BanDevice(ctx, strings.TrimSpace(report.TargetDevice), reason, duration); err == nil {
			actions = append(actions, "封设备")
		}
	}

	// 涉案资源下架/删除与消息撤回
	if takedownResource {
		takedownActions := a.takedownOffendingContent(ctx, report.Reason)
		if len(takedownActions) > 0 {
			actions = append(actions, takedownActions...)
		}
	}

	// 无论是否执行封禁，都记录处理结果并把举报标记为已处理。
	result := strings.TrimSpace(r.FormValue("result"))
	if len(actions) > 0 {
		if result != "" {
			result += "；"
		}
		result += "已执行" + strings.Join(actions, "+") + "（" + reason + "）"
	}
	if result == "" {
		result = "举报已处理"
	}
	_ = a.reportStore.SetUserReportStatus(ctx, id, "resolved", result)
	if report.ReporterID != "" {
		targetName := report.TargetUID
		if targetName == "" {
			targetName = report.TargetUserID
		}
		repBody := fmt.Sprintf("【举报审核结果通知】\n您对用户 [%s] 发起的违规举报已完成审核与处置。\n处理结果说明：%s", targetName, result)
		_ = a.sendSystemNotification(ctx, report.ReporterID, "举报审核结果通知", repBody, false)
	}
	a.logModeratorAction(r, "report_ban", "report", id, "举报 #"+id+" 封禁处置: "+result)

	msg := "举报已处理"
	if len(actions) > 0 {
		msg += "：" + strings.Join(actions, "+")
	}
	a.respondModerationAction(w, r, "/admins/moderation?section=reports&ok="+url.QueryEscape(msg)+"#reports", msg)
}

// handleAdminModerationAppealStatus 审核台：标记法庭申诉状态。
func (a *API) handleAdminModerationAppealStatus(w http.ResponseWriter, r *http.Request) {
	if !a.ensureModerationPost(w, r) {
		return
	}
	appealID := strings.TrimSpace(r.FormValue("id"))
	status := normalizeBanAppealStatusInput(r.FormValue("status"))
	adminNote := strings.TrimSpace(r.FormValue("admin_note"))
	if appealID == "" {
		a.respondModerationError(w, r, "/admins/moderation?section=court&err="+url.QueryEscape("缺少申诉ID")+"#court", "缺少申诉ID")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	appeal, err := a.banAppeals.GetByID(ctx, appealID)
	if err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=court&err="+url.QueryEscape("申诉不存在")+"#court", "申诉不存在")
		return
	}
	if err = a.banAppeals.SetStatus(ctx, appealID, status, adminNote); err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=court&err="+url.QueryEscape("申诉处理失败")+"#court", "申诉处理失败")
		return
	}
	unbanOnApprove := strings.TrimSpace(r.FormValue("unban_on_approve"))
	shouldUnban := status == "approved" && unbanOnApprove != "0" && !strings.EqualFold(unbanOnApprove, "false")
	if shouldUnban && appeal != nil && strings.TrimSpace(appeal.UserID) != "" {
		_ = a.devices.UnbanUser(ctx, appeal.UserID)
	}
	a.logModeratorAction(r, "appeal_status", "appeal", appealID, "更新申诉 #"+appealID+" 状态为 "+status+"（通过时解封: "+strconv.FormatBool(shouldUnban)+"），备注: "+adminNote)
	a.respondModerationAction(w, r, "/admins/moderation?section=court&ok="+url.QueryEscape("申诉状态已更新")+"#court", "申诉状态已更新")
}

// handleAdminModerationBugStatus 审核台：标记 Bug 反馈状态。
func (a *API) handleAdminModerationBugStatus(w http.ResponseWriter, r *http.Request) {
	if !a.ensureModerationPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	if id == "" {
		id = strings.TrimSpace(r.FormValue("bug_id")) // 兼容旧版页面字段名
	}
	status := strings.ToLower(strings.TrimSpace(r.FormValue("status")))
	note := strings.TrimSpace(r.FormValue("note"))
	if id == "" {
		a.respondModerationError(w, r, "/admins/moderation?section=bugs&err="+url.QueryEscape("反馈 ID 不能为空")+"#bugs", "反馈 ID 不能为空")
		return
	}
	if status != "open" && status != "resolved" && status != "closed" {
		a.respondModerationError(w, r, "/admins/moderation?section=bugs&err="+url.QueryEscape("Bug 状态不合法")+"#bugs", "Bug 状态不合法")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if err := a.bugReportStore.SetStatus(ctx, id, status, note); err != nil {
		a.respondModerationError(w, r, "/admins/moderation?section=bugs&err="+url.QueryEscape("更新 Bug 状态失败")+"#bugs", "更新 Bug 状态失败")
		return
	}
	a.logModeratorAction(r, "bug_status", "bug", id, "更新Bug反馈 #"+id+" 状态为 "+status+"，备注: "+note)
	a.respondModerationAction(w, r, "/admins/moderation?section=bugs&ok="+url.QueryEscape("Bug 处理状态已更新")+"#bugs", "Bug 处理状态已更新")
}

func (a *API) handleAdminModerationBotReview(w http.ResponseWriter, r *http.Request) {
	a.handleAdminModerationPermitReview(w, r, "bots")
}

func (a *API) handleAdminModerationChannelReview(w http.ResponseWriter, r *http.Request) {
	a.handleAdminModerationPermitReview(w, r, "channels")
}

func (a *API) handleAdminModerationPermitReview(w http.ResponseWriter, r *http.Request, section string) {
	if !a.ensureModerationPost(w, r) {
		return
	}
	id := strings.TrimSpace(r.FormValue("id"))
	action := strings.ToLower(strings.TrimSpace(r.FormValue("action")))
	reviewReason := strings.TrimSpace(r.FormValue("review_reason"))
	if id == "" || (action != "approve" && action != "reject") {
		a.respondModerationError(w, r, "/admins/moderation?section="+section+"&err="+url.QueryEscape("审核参数无效"), "审核参数无效")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	var updated bool
	var err error
	if section == "bots" {
		updated, err = a.reviewBotPermit(ctx, id, action, reviewReason)
	} else {
		updated, err = a.reviewChannelAPIPermit(ctx, id, action, reviewReason)
	}
	if err != nil || !updated {
		a.respondModerationError(w, r, "/admins/moderation?section="+section+"&err="+url.QueryEscape("申请已处理或审核失败"), "申请已处理或审核失败")
		return
	}
	if section == "bots" {
		a.notifyPermitReviewEmail(ctx, "bot_permits", id, action, reviewReason, "https://oc.mcl0.dpdns.org/bot-apply")
	} else {
		a.notifyPermitReviewEmail(ctx, "channel_api_permits", id, action, reviewReason, "https://oc.mcl0.dpdns.org/channel-api/apply")
	}
	label := "已通过"
	if action == "reject" {
		label = "已拒绝"
	}
	a.logModeratorAction(r, "permit_review", section, id, "审核 "+section+" 申请 #"+id+" 为 "+label+"，理由: "+reviewReason)
	a.respondModerationAction(w, r, "/admins/moderation?section="+section+"&ok="+url.QueryEscape("申请"+label), "申请"+label)
}

type chatContextMessageRow struct {
	ID                string         `db:"id"`
	GroupID           string         `db:"group_id"`
	SenderID          string         `db:"sender_id"`
	SenderUID         sql.NullString `db:"sender_uid"`
	SenderUsername    sql.NullString `db:"sender_username"`
	SenderDisplayName sql.NullString `db:"sender_display_name"`
	MsgType           string         `db:"msg_type"`
	Body              string         `db:"body"`
	MediaURL          string         `db:"media_url"`
	ThumbURL          string         `db:"thumb_url"`
	GroupSeq          sql.NullInt64  `db:"group_seq"`
	CreatedAt         time.Time      `db:"created_at"`
}

type chatContextItemJSON struct {
	ID         string      `json:"id"`
	Seq        int64       `json:"seq"`
	SenderID   string      `json:"sender_id"`
	SenderUID  string      `json:"sender_uid"`
	SenderName string      `json:"sender_name"`
	MsgType    string      `json:"msg_type"`
	Body       string      `json:"body"`
	Text       string      `json:"text"`
	Quote      interface{} `json:"quote,omitempty"`
	MediaURL   string      `json:"media_url,omitempty"`
	ThumbURL   string      `json:"thumb_url,omitempty"`
	CreatedAt  string      `json:"created_at"`
	IsTarget   bool        `json:"is_target"`
}

// handleAdminModerationChatContext 审核台/管理后台：异步获取群聊涉案消息上下文（前后各 N 条及持续加载）
func (a *API) handleAdminModerationChatContext(w http.ResponseWriter, r *http.Request) {
	if _, _, ok := a.isModeratorOrAdmin(r); !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "请先登录审核台或管理后台")
		return
	}

	groupID := strings.TrimSpace(r.URL.Query().Get("group_id"))
	targetMsgID := strings.TrimSpace(r.URL.Query().Get("msg_id"))
	beforeSeqStr := strings.TrimSpace(r.URL.Query().Get("before_seq"))
	afterSeqStr := strings.TrimSpace(r.URL.Query().Get("after_seq"))

	limit := 10
	if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 && l <= 50 {
		limit = l
	}

	if groupID == "" {
		writeError(w, http.StatusBadRequest, "invalid_params", "缺少群ID参数")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	var rows []chatContextMessageRow
	baseSelect := `SELECT m.id, m.group_id, m.sender_id,
	                      u.uid AS sender_uid, u.username AS sender_username, u.display_name AS sender_display_name,
	                      m.msg_type, m.body, m.media_url, m.thumb_url, m.group_seq, m.created_at
	               FROM group_messages m
	               LEFT JOIN users u ON u.id = m.sender_id`

	if beforeSeqStr != "" {
		// 向上加载更早消息：seq < before_seq
		beforeSeq, _ := strconv.ParseInt(beforeSeqStr, 10, 64)
		query := baseSelect + ` WHERE m.group_id = $1 AND m.group_seq < $2 ORDER BY m.group_seq DESC LIMIT $3`
		var fetched []chatContextMessageRow
		_ = a.db.SelectContext(ctx, &fetched, query, groupID, beforeSeq, limit)
		// 反转为时间顺序 ASC
		for i := len(fetched) - 1; i >= 0; i-- {
			rows = append(rows, fetched[i])
		}
	} else if afterSeqStr != "" {
		// 向下加载更晚消息：seq > after_seq
		afterSeq, _ := strconv.ParseInt(afterSeqStr, 10, 64)
		query := baseSelect + ` WHERE m.group_id = $1 AND m.group_seq > $2 ORDER BY m.group_seq ASC LIMIT $3`
		_ = a.db.SelectContext(ctx, &rows, query, groupID, afterSeq, limit)
	} else {
		// 初始加载：以 targetMsgID 为中心，加载前 10 条 + 目标条 + 后 10 条
		var targetSeq int64 = -1
		if targetMsgID != "" {
			_ = a.db.GetContext(ctx, &targetSeq, `SELECT COALESCE(group_seq, -1) FROM group_messages WHERE group_id = $1 AND id = $2 LIMIT 1`, groupID, targetMsgID)
		}

		if targetSeq >= 0 {
			// 1. 目标消息之前的 N 条
			var beforeRows []chatContextMessageRow
			queryBefore := baseSelect + ` WHERE m.group_id = $1 AND m.group_seq < $2 ORDER BY m.group_seq DESC LIMIT $3`
			_ = a.db.SelectContext(ctx, &beforeRows, queryBefore, groupID, targetSeq, limit)
			for i := len(beforeRows) - 1; i >= 0; i-- {
				rows = append(rows, beforeRows[i])
			}

			// 2. 目标消息本身
			var targetRow chatContextMessageRow
			queryTarget := baseSelect + ` WHERE m.group_id = $1 AND m.id = $2 LIMIT 1`
			if err := a.db.GetContext(ctx, &targetRow, queryTarget, groupID, targetMsgID); err == nil {
				rows = append(rows, targetRow)
			}

			// 3. 目标消息之后的 N 条
			var afterRows []chatContextMessageRow
			queryAfter := baseSelect + ` WHERE m.group_id = $1 AND m.group_seq > $2 ORDER BY m.group_seq ASC LIMIT $3`
			_ = a.db.SelectContext(ctx, &afterRows, queryAfter, groupID, targetSeq, limit)
			rows = append(rows, afterRows...)
		} else {
			// 未找到目标消息或未提供 msg_id：展示该群最近的 20 条消息
			var latestRows []chatContextMessageRow
			queryLatest := baseSelect + ` WHERE m.group_id = $1 ORDER BY m.group_seq DESC LIMIT 20`
			_ = a.db.SelectContext(ctx, &latestRows, queryLatest, groupID)
			for i := len(latestRows) - 1; i >= 0; i-- {
				rows = append(rows, latestRows[i])
			}
		}
	}

	// 构造格式化 JSON
	items := make([]chatContextItemJSON, 0, len(rows))
	var minSeq int64 = -1
	var maxSeq int64 = -1

	for _, rMsg := range rows {
		seq := rMsg.GroupSeq.Int64
		if minSeq == -1 || seq < minSeq {
			minSeq = seq
		}
		if maxSeq == -1 || seq > maxSeq {
			maxSeq = seq
		}

		senderName := rMsg.SenderDisplayName.String
		if senderName == "" {
			senderName = rMsg.SenderUsername.String
		}
		if senderName == "" {
			senderName = rMsg.SenderUID.String
		}

		text := rMsg.Body
		var quote interface{} = nil
		mediaURL := rMsg.MediaURL
		thumbURL := rMsg.ThumbURL

		if strings.HasPrefix(strings.TrimSpace(rMsg.Body), "{") && strings.HasSuffix(strings.TrimSpace(rMsg.Body), "}") {
			var obj map[string]interface{}
			if err := jsonUnmarshal([]byte(rMsg.Body), &obj); err == nil {
				if t, ok := obj["text"].(string); ok {
					text = t
				} else if b, ok := obj["body"].(string); ok {
					text = b
				}
				if q, ok := obj["quote"]; ok {
					quote = q
				}
				if m, ok := obj["media_url"].(string); ok && mediaURL == "" {
					mediaURL = m
				}
				if m, ok := obj["thumb_url"].(string); ok && thumbURL == "" {
					thumbURL = m
				}
			}
		}

		items = append(items, chatContextItemJSON{
			ID:         rMsg.ID,
			Seq:        seq,
			SenderID:   rMsg.SenderID,
			SenderUID:  rMsg.SenderUID.String,
			SenderName: senderName,
			MsgType:    rMsg.MsgType,
			Body:       rMsg.Body,
			Text:       text,
			Quote:      quote,
			MediaURL:   mediaURL,
			ThumbURL:   thumbURL,
			CreatedAt:  rMsg.CreatedAt.Format("2006-01-02 15:04:05"),
			IsTarget:   rMsg.ID == targetMsgID,
		})
	}

	hasMoreBefore := false
	hasMoreAfter := false
	if minSeq > 0 {
		var cnt int
		_ = a.db.GetContext(ctx, &cnt, `SELECT COUNT(1) FROM group_messages WHERE group_id = $1 AND group_seq < $2 LIMIT 1`, groupID, minSeq)
		hasMoreBefore = cnt > 0
	}
	if maxSeq > 0 {
		var cnt int
		_ = a.db.GetContext(ctx, &cnt, `SELECT COUNT(1) FROM group_messages WHERE group_id = $1 AND group_seq > $2 LIMIT 1`, groupID, maxSeq)
		hasMoreAfter = cnt > 0
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_ = jsonNewEncoder(w).Encode(map[string]interface{}{
		"ok":              true,
		"group_id":        groupID,
		"target_msg_id":   targetMsgID,
		"messages":        items,
		"min_seq":         minSeq,
		"max_seq":         maxSeq,
		"has_more_before": hasMoreBefore,
		"has_more_after":  hasMoreAfter,
	})
}

func jsonUnmarshal(data []byte, v interface{}) error {
	return json.Unmarshal(data, v)
}

func jsonNewEncoder(w io.Writer) *json.Encoder {
	return json.NewEncoder(w)
}
