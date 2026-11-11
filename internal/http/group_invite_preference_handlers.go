package httpapi

import (
	"context"
	"net/http"
	"time"

	"metrochat/internal/data"
)

type groupInvitePreferenceRequest struct {
	Reject bool `json:"reject"`
}

type groupInvitePreferenceResponse struct {
	Reject bool `json:"reject"`
}

func (a *API) handleGetGroupInvitePreference(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	enabled, err := a.users.RejectGroupInvites(ctx, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, groupInvitePreferenceResponse{Reject: enabled})
}

func (a *API) handleSetGroupInvitePreference(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req groupInvitePreferenceRequest
	if decodeJSON(w, r, &req) != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if err := a.users.SetRejectGroupInvites(ctx, claims.Subject, req.Reject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, groupInvitePreferenceResponse{Reject: req.Reject})
}
