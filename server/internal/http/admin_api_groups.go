package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type groupDismissReq struct {
	GroupID string `json:"group_id"`
}

func (a *API) handleAdminAPIGroups(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize := 50
	offset := (page - 1) * pageSize

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	groups, err := a.groups.ListAdminPage(ctx, pageSize, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list groups")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": map[string]any{
			"groups": groups,
			"page":   page,
		},
	})
}

func (a *API) handleAdminAPIGroupDismiss(w http.ResponseWriter, r *http.Request) {
	var req groupDismissReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.GroupID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "group_id required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	if err := a.groups.Delete(ctx, groupID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to dismiss group")
		return
	}
	_ = a.groupBans.Unban(ctx, groupID)

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "group dismissed"})
}
