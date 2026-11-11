package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

type groupInvitationCreatedResponse struct {
	Status    string `json:"status"`
	RequestID string `json:"request_id"`
}

type groupInvitationRespondRequest struct {
	RequestID string `json:"request_id"`
	Accept    bool   `json:"accept"`
}

type groupInvitationItem struct {
	ID                 string `json:"id"`
	Status             int16  `json:"status"`
	Type               string `json:"type"`
	GroupID            string `json:"group_id"`
	GroupName          string `json:"group_name"`
	GroupAvatarURL     string `json:"group_avatar_url"`
	InviterUID         string `json:"inviter_uid"`
	InviterDisplayName string `json:"inviter_display_name"`
	InviterTitle       string `json:"inviter_title"`
	InviterAvatarURL   string `json:"inviter_avatar_url"`
	CreatedAt          int64  `json:"created_at"`
}

type groupInvitationsResponse struct {
	Requests []groupInvitationItem `json:"requests"`
}

func (a *API) handleGroupInvitations(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	items, err := a.groupInvites.ListIncoming(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	resp := groupInvitationsResponse{Requests: make([]groupInvitationItem, 0, len(items))}
	for _, item := range items {
		resp.Requests = append(resp.Requests, groupInvitationItem{
			ID: item.ID, Status: data.GroupInvitationPending, Type: "group_invite",
			GroupID: item.GroupID, GroupName: item.GroupName,
			GroupAvatarURL: item.GroupAvatarURL, InviterUID: item.InviterUID,
			InviterDisplayName: item.InviterDisplayName, InviterTitle: item.InviterTitle,
			InviterAvatarURL: item.InviterAvatarURL, CreatedAt: item.CreatedAt.Unix(),
		})
	}
	writeJSON(w, http.StatusOK, resp)
}

func (a *API) handleGroupInvitationRespond(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req groupInvitationRespondRequest
	if decodeJSON(w, r, &req) != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	requestID := strings.TrimSpace(req.RequestID)
	if requestID == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "invalid request")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	result, err := a.groupInvites.Respond(ctx, requestID, claims.Subject, req.Accept)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "request_not_found", "request not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if !req.Accept {
		go a.sendGroupInvitationRejectedNotification(result.OwnerID, claims.Subject, result.GroupID, false)
	}
	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

func (a *API) sendGroupInvitationRejectedNotification(ownerID, rejecterID, groupID string, automatic bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	owner, err := a.users.GetByID(ctx, ownerID)
	if err != nil {
		return
	}
	rejecter, err := a.users.GetByID(ctx, rejecterID)
	if err != nil {
		return
	}
	group, err := a.groups.GetByID(ctx, groupID)
	if err != nil {
		return
	}
	threadID, err := a.direct.GetThreadID(ctx, data.SystemNotificationUID, owner.ID)
	if err != nil {
		threadID, err = a.direct.GetOrCreateThread(ctx, data.SystemNotificationUID, owner.ID, nanoid.New())
		if err != nil {
			return
		}
	}
	name := strings.TrimSpace(rejecter.DisplayName)
	if name == "" {
		name = rejecter.UID
	}
	body := name + " 拒绝了加入群聊「" + group.Name + "」的邀请"
	if automatic {
		body = name + " 已开启默认拒绝群聊邀请，未加入群聊「" + group.Name + "」"
	}
	_ = a.direct.CreateMessage(ctx, &data.DirectMessage{
		ID: nanoid.New(), ThreadID: threadID, SenderID: data.SystemNotificationUID,
		Body: body, MsgType: "text",
	})
}
