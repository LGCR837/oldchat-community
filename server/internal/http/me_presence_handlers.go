package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"metrochat/internal/data"
)

type updatePresenceRequest struct {
	PresenceStatus string `json:"presence_status"`
}

func (a *API) handleUpdatePresence(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req updatePresenceRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	status := strings.ToLower(strings.TrimSpace(req.PresenceStatus))
	if status != "online" && status != "invisible" {
		writeError(w, http.StatusBadRequest, "invalid_status", "status must be online or invisible")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	if err := a.users.UpdatePresenceStatus(ctx, claims.Subject, status); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	user, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	a.broadcastPresenceToFriends(user)

	writeJSON(w, http.StatusOK, toSelfUserResponse(user))
}

func (a *API) broadcastPresenceToFriends(user *data.User) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	friendIDs, err := a.friends.ListFriendIDs(ctx, user.ID)
	if err != nil {
		return
	}

	isOnline := a.wsHub.IsUserOnline(user.ID)
	envelope := wsEnvelopePresence{
		Type: "presence",
		Data: presenceData{
			UID:            user.UID,
			IsOnline:       isOnline,
			PresenceStatus: user.PresenceStatus,
		},
	}

	payload, err := json.Marshal(envelope)
	if err != nil {
		return
	}

	a.wsHub.BroadcastToUsers(friendIDs, payload)
}
