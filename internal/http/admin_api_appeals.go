package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type adminAppealRespondReq struct {
	ID        string `json:"id"`
	Status    string `json:"status"` // "approved", "rejected"
	AdminNote string `json:"admin_note"`
}

// handleAdminAPIAppealsList GET /v1/admin/appeals
func (a *API) handleAdminAPIAppealsList(w http.ResponseWriter, r *http.Request) {
	status := strings.TrimSpace(r.URL.Query().Get("status"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit < 1 || limit > 500 {
		limit = 100
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	list, err := a.banAppeals.ListRecent(ctx, status, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list appeals")
		return
	}
	pendingCount, _ := a.banAppeals.CountPending(ctx)

	writeJSON(w, http.StatusOK, map[string]any{
		"code":          200,
		"appeals":       list,
		"pending_count": pendingCount,
	})
}

// handleAdminAPIAppealRespond POST /v1/admin/appeals/respond — 审批申诉
func (a *API) handleAdminAPIAppealRespond(w http.ResponseWriter, r *http.Request) {
	var req adminAppealRespondReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id and status required")
		return
	}
	req.Status = strings.ToLower(strings.TrimSpace(req.Status))
	if req.Status != "approved" && req.Status != "rejected" {
		writeError(w, http.StatusBadRequest, "invalid_status", "status must be approved or rejected")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	appeal, err := a.banAppeals.GetByID(ctx, req.ID)
	if err != nil || appeal == nil {
		writeError(w, http.StatusNotFound, "appeal_not_found", "appeal not found")
		return
	}

	if err := a.banAppeals.SetStatus(ctx, req.ID, req.Status, req.AdminNote); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to update appeal status")
		return
	}

	// 若申诉通过（approved），自动为该用户解封账号
	if req.Status == "approved" {
		_ = a.devices.UnbanUser(ctx, appeal.UID)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code":    200,
		"message": "申诉处置成功",
		"status":  req.Status,
	})
}
