package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type userBanReq struct {
	UID    string `json:"uid"`
	Reason string `json:"reason"`
}

type userUnbanReq struct {
	UID string `json:"uid"`
}

func (a *API) handleAdminAPIUsers(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize := 50
	offset := (page - 1) * pageSize
	keyword := strings.TrimSpace(r.URL.Query().Get("q"))

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	rows, total, err := a.users.SearchAdminPage(ctx, keyword, pageSize, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to search users")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": map[string]any{
			"users":     rows,
			"total":     total,
			"page":      page,
			"page_size": pageSize,
			"keyword":   keyword,
		},
	})
}

func (a *API) handleAdminAPIUserBan(w http.ResponseWriter, r *http.Request) {
	var req userBanReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	reason := req.Reason
	if reason == "" {
		reason = "管理员封禁"
	}
	if err := a.devices.BanUser(ctx, req.UID, reason, 0); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to ban user")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "user banned"})
}

func (a *API) handleAdminAPIUserUnban(w http.ResponseWriter, r *http.Request) {
	var req userUnbanReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.devices.UnbanUser(ctx, req.UID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to unban user")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "user unbanned"})
}
