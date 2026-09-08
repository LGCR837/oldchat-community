package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type adminReportResolveReq struct {
	ID     string `json:"id"`
	Action string `json:"action"` // "ban_user", "ban_device", "dismiss"
	Note   string `json:"note"`
}

type adminCourtResolveReq struct {
	CaseID   string `json:"case_id"`
	Action   string `json:"action"` // "guilty", "innocent", "dismiss", "close"
	Note     string `json:"note"`
	BanHours int    `json:"ban_hours"`
}

// handleAdminAPIReports GET /v1/admin/reports
func (a *API) handleAdminAPIReports(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize := 50
	offset := (page - 1) * pageSize

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	reports, err := a.reportStore.ListUserReportsPage(ctx, pageSize, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list reports")
		return
	}
	total, _ := a.reportStore.CountUserReports(ctx)

	writeJSON(w, http.StatusOK, map[string]any{
		"code":      200,
		"reports":   reports,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

// handleAdminAPIReportResolve POST /v1/admin/reports/resolve — 处理举报工单
func (a *API) handleAdminAPIReportResolve(w http.ResponseWriter, r *http.Request) {
	var req adminReportResolveReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	rep, err := a.reportStore.GetUserReportByID(ctx, req.ID)
	if err != nil || rep == nil {
		writeError(w, http.StatusNotFound, "report_not_found", "report not found")
		return
	}

	resultText := "管理员处理"
	if req.Note != "" {
		resultText = req.Note
	}

	switch req.Action {
	case "ban_user":
		_ = a.devices.BanUser(ctx, rep.TargetUID, "举报核实封禁: "+resultText, 0)
		_ = a.reportStore.SetUserReportStatus(ctx, req.ID, "resolved", "已封禁账号: "+resultText)
	case "ban_device":
		_ = a.devices.BanUser(ctx, rep.TargetUID, "举报核实封号封设备: "+resultText, 0)
		_ = a.devices.BanDevice(ctx, rep.TargetUID, "举报核实封禁设备", 0)
		_ = a.reportStore.SetUserReportStatus(ctx, req.ID, "resolved", "已封号+封设备: "+resultText)
	case "dismiss":
		_ = a.reportStore.SetUserReportStatus(ctx, req.ID, "dismissed", "驳回忽略: "+resultText)
	default:
		_ = a.reportStore.SetUserReportStatus(ctx, req.ID, "resolved", resultText)
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "举报工单处理完成"})
}

// handleAdminAPICourt GET /v1/admin/court
func (a *API) handleAdminAPICourt(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	cases, err := a.publicCourt.ListCases(ctx, "all", 100, time.Time{}, "")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list court cases")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code":  200,
		"cases": cases,
	})
}

// handleAdminAPICourtResolve POST /v1/admin/court/resolve — 法庭案件直接裁决
func (a *API) handleAdminAPICourtResolve(w http.ResponseWriter, r *http.Request) {
	var req adminCourtResolveReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.CaseID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "case_id required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	c, err := a.publicCourt.GetByID(ctx, req.CaseID)
	if err != nil || c == nil {
		writeError(w, http.StatusNotFound, "case_not_found", "case not found")
		return
	}

	note := strings.TrimSpace(req.Note)
	if note == "" {
		note = "管理员直接仲裁"
	}

	switch req.Action {
	case "guilty":
		banH := req.BanHours
		if banH <= 0 {
			banH = 24 * 7 // 默认7天
		}
		_, err = a.publicCourt.FinalizeCase(ctx, req.CaseID, "guilty", note, banH)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", err.Error())
			return
		}
		// 封禁被告
		_ = a.devices.BanUser(ctx, c.DefendantUID, "法庭判定有罪: "+note, banH)
	case "innocent":
		_, err = a.publicCourt.FinalizeCase(ctx, req.CaseID, "innocent", note, 0)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", err.Error())
			return
		}
	case "dismiss", "close":
		_, err = a.publicCourt.WithdrawCase(ctx, req.CaseID, c.ReporterID, "管理员关闭撤销: "+note)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", err.Error())
			return
		}
	default:
		writeError(w, http.StatusBadRequest, "invalid_action", "unknown action")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "法庭案件裁决完成"})
}
