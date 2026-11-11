package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

type groupCreateRequest struct {
	Name         string   `json:"name"`
	MemberUIDs   []string `json:"member_uids"`
	MemberNcuids []string `json:"member_ncuids"`
}

type groupCreateResponse struct {
	GroupID           string `json:"group_id"`
	Name              string `json:"name"`
	InvitationCount   int    `json:"invitation_count"`
	AutoRejectedCount int    `json:"auto_rejected_count"`
}

type groupJoinRequest struct {
	GroupID string `json:"group_id"`
}

type groupJoinResponse struct {
	Status string `json:"status"`
}

type groupApproveRequest struct {
	RequestID string `json:"request_id"`
	Accept    bool   `json:"accept"`
}

func (a *API) handleGroupCreate(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupCreateRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	name := strings.TrimSpace(req.Name)
	if !isValidGroupName(name) {
		writeError(w, http.StatusBadRequest, "invalid_name", "invalid name")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	currentUser, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	memberIDs := make([]string, 0, len(req.MemberUIDs)+len(req.MemberNcuids))
	seen := make(map[string]struct{})
	useNcuids := len(req.MemberNcuids) > 0
	identities := req.MemberUIDs
	if useNcuids {
		identities = req.MemberNcuids
	}
	for _, raw := range identities {
		var uid, ncuid string
		if useNcuids {
			ncuid = strings.TrimSpace(raw)
		} else {
			uid = strings.TrimSpace(raw)
		}
		user, err := a.resolveUserIdentity(ctx, uid, ncuid)
		if err != nil {
			writeUserIdentityError(w, err)
			return
		}
		if user.ID == currentUser.ID {
			continue
		}
		if _, exists := seen[user.ID]; exists {
			continue
		}
		seen[user.ID] = struct{}{}
		areFriends, err := a.friends.AreFriends(ctx, currentUser.ID, user.ID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		if !areFriends {
			writeError(w, http.StatusForbidden, "not_friends", "not friends")
			return
		}
		memberIDs = append(memberIDs, user.ID)
	}

	inviteeIDs := make([]string, 0, len(memberIDs))
	autoRejectedIDs := make([]string, 0)
	for _, memberID := range memberIDs {
		reject, prefErr := a.users.RejectGroupInvites(ctx, memberID)
		if prefErr != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		if reject {
			autoRejectedIDs = append(autoRejectedIDs, memberID)
		} else {
			inviteeIDs = append(inviteeIDs, memberID)
		}
	}
	groupID, err := a.createGroupWithInvitationsRetry(ctx, claims.Subject, name, inviteeIDs)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusCreated, groupCreateResponse{
		GroupID: groupID, Name: name, InvitationCount: len(inviteeIDs),
		AutoRejectedCount: len(autoRejectedIDs),
	})
	for _, rejectedID := range autoRejectedIDs {
		go a.sendGroupInvitationRejectedNotification(claims.Subject, rejectedID, groupID, true)
	}
}

func (a *API) handleGroupJoin(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupJoinRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	group, err := a.groups.GetByID(ctx, groupID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err == nil {
		writeJSON(w, http.StatusOK, groupJoinResponse{Status: "joined"})
		return
	} else if err != data.ErrNotFound {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if !group.JoinApproval {
		if err := a.groups.AddMember(ctx, groupID, claims.Subject, data.GroupRoleMember); err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		writeJSON(w, http.StatusOK, groupJoinResponse{Status: "joined"})
		return
	}

	pending, err := a.groupJoins.Pending(ctx, groupID, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if pending {
		writeJSON(w, http.StatusOK, groupJoinResponse{Status: "pending"})
		return
	}

	id := nanoid.New()
	jr := &data.GroupJoinRequest{
		ID:      id,
		GroupID: groupID,
		UserID:  claims.Subject,
		Status:  data.GroupJoinPending,
	}
	if err := a.groupJoins.Create(ctx, jr); err != nil {
		if isUniqueViolation(err, "idx_group_join_pending") {
			writeJSON(w, http.StatusOK, groupJoinResponse{Status: "pending"})
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, groupJoinResponse{Status: "pending"})
}

func (a *API) handleGroupApprove(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupApproveRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	requestID := strings.TrimSpace(req.RequestID)
	if requestID == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "invalid request")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	joinReq, err := a.groupJoins.GetByID(ctx, requestID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "request_not_found", "request not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if joinReq.Status != data.GroupJoinPending {
		writeError(w, http.StatusConflict, "request_closed", "request already handled")
		return
	}

	role, err := a.groups.GetRole(ctx, joinReq.GroupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_admin", "not allowed")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if role < data.GroupRoleAdmin {
		writeError(w, http.StatusForbidden, "not_admin", "not allowed")
		return
	}

	if req.Accept {
		if _, _, err := a.groupJoins.AcceptAndAdd(ctx, requestID); err != nil {
			if err == data.ErrNotFound {
				writeError(w, http.StatusNotFound, "request_not_found", "request not found")
				return
			}
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
	} else {
		if err := a.groupJoins.Deny(ctx, requestID); err != nil {
			if err == data.ErrNotFound {
				writeError(w, http.StatusNotFound, "request_not_found", "request not found")
				return
			}
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

type groupLeaveRequest struct {
	GroupID string `json:"group_id"`
}

func (a *API) handleGroupLeave(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupLeaveRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.TrimSpace(req.GroupID)
	if groupID == "" {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	_, err := a.groups.GetByID(ctx, groupID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	role, err := a.groups.GetRole(ctx, groupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if role == data.GroupRoleOwner {
		writeError(w, http.StatusForbidden, "owner_cannot_leave", "owner cannot leave, must dissolve group")
		return
	}

	if err := a.groups.RemoveMember(ctx, groupID, claims.Subject); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

func (a *API) handleGroupMessageDelete(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	// 从URL路径中提取消息ID
	messageID := strings.TrimPrefix(r.URL.Path, "/v1/groups/messages/")
	if messageID == "" || messageID == r.URL.Path {
		writeError(w, http.StatusBadRequest, "invalid_message_id", "invalid message id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	msg, err := a.groupMsgs.GetByID(ctx, messageID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	canDelete := msg.SenderID == claims.Subject
	if !canDelete {
		role, err := a.groups.GetRole(ctx, msg.GroupID, claims.Subject)
		if err != nil {
			if err == data.ErrNotFound {
				writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
				return
			}
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		if role < data.GroupRoleAdmin {
			writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
			return
		}
		if role == data.GroupRoleAdmin {
			senderRole, err := a.groups.GetRole(ctx, msg.GroupID, msg.SenderID)
			if err != nil && err != data.ErrNotFound {
				writeError(w, http.StatusInternalServerError, "db_error", "internal error")
				return
			}
			if err == nil && senderRole == data.GroupRoleOwner {
				writeError(w, http.StatusForbidden, "permission_denied", "cannot recall owner message")
				return
			}
		}
		canDelete = true
	}
	if !canDelete {
		writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
		return
	}

	sender, err := a.users.GetByID(ctx, msg.SenderID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	// 删除消息（本人可删自己；管理员/群主可删他人）
	if msg.SenderID == claims.Subject {
		err = a.groupMsgs.DeleteMessage(ctx, messageID, claims.Subject)
	} else {
		err = a.groupMsgs.DeleteMessageByID(ctx, messageID)
	}
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})

	memberIDs, err := a.groups.ListMemberIDs(ctx, msg.GroupID)
	if err != nil {
		return
	}
	env := wsEnvelope{
		Type: "group_recall",
		Data: map[string]interface{}{
			"message_id": msg.ID,
			"group_id":   msg.GroupID,
			"from_uid":   sender.UID,
		},
	}
	if payload, err := json.Marshal(env); err == nil {
		a.wsHub.BroadcastToUsers(memberIDs, payload)
	}

	// 撤回只写一条群级事件；在线成员已收到上面的 WS，离线成员按群水位补偿。
	if a.groupEvents != nil {
		recallPayload := map[string]interface{}{
			"message_id":       msg.ID,
			"group_id":         msg.GroupID,
			"recall_edit_type": "recall",
			"operator_uid":     sender.UID,
		}
		_, _ = a.groupEvents.EmitGroupEvent(context.Background(), msg.GroupID, "GROUP_MESSAGE_RECALL", msg.ID, recallPayload)
	}
}
