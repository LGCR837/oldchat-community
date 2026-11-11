package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type adminBugStatusReq struct {
	ID        string `json:"id"`
	Status    string `json:"status"` // "resolved", "closed", "open", "in_progress"
	AdminNote string `json:"admin_note"`
}

// handleAdminAPIBugsList GET /v1/admin/bugs
func (a *API) handleAdminAPIBugsList(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize := 50
	offset := (page - 1) * pageSize

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	list, err := a.bugReportStore.ListRecentPage(ctx, pageSize, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list bug reports")
		return
	}
	total, _ := a.bugReportStore.Count(ctx)

	writeJSON(w, http.StatusOK, map[string]any{
		"code":      200,
		"bugs":      list,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

// handleAdminAPIBugStatus POST /v1/admin/bugs/status — 更新 Bug 处理状态
func (a *API) handleAdminAPIBugStatus(w http.ResponseWriter, r *http.Request) {
	var req adminBugStatusReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id and status required")
		return
	}
	req.Status = strings.ToLower(strings.TrimSpace(req.Status))
	if req.Status == "" {
		req.Status = "resolved"
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.bugReportStore.SetStatus(ctx, req.ID, req.Status, req.AdminNote); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to update bug status")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "Bug工单状态已更新", "status": req.Status})
}
