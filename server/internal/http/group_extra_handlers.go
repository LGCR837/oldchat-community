package httpapi

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

type groupListItem struct {
	GroupID               string `json:"group_id"`
	Name                  string `json:"name"`
	AvatarURL             string `json:"avatar_url"`
	JoinApproval          bool   `json:"join_approval"`
	GlobalMute            bool   `json:"global_mute"`
	Announcement          string `json:"announcement"`
	AnnouncementMode      int16  `json:"announcement_mode"`
	AnnouncementUpdatedAt int64  `json:"announcement_updated_at"`
	AnnouncementReadAt    int64  `json:"announcement_read_at"`
	MemberCount           int    `json:"member_count"`
	MemberVersion         int64  `json:"member_version"`
	MessageSeq            int64  `json:"message_seq"`
	Role                  int16  `json:"role"`
}

type groupListResponse struct {
	Groups []groupListItem `json:"groups"`
}

type groupMemberItem struct {
	UID         string `json:"uid"`
	Ncuid       string `json:"ncuid"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	UserTitle   string `json:"user_title"`
	AvatarURL   string `json:"avatar_url"`
	Role        int16  `json:"role"`
	JoinedAt    int64  `json:"joined_at"`
}

type groupMembersResponse struct {
	Members       []groupMemberItem `json:"members"`
	Total         int               `json:"total,omitempty"`
	Offset        int               `json:"offset,omitempty"`
	Limit         int               `json:"limit,omitempty"`
	HasMore       bool              `json:"has_more,omitempty"`
	MemberVersion int64             `json:"member_version,omitempty"`
}

type groupInviteRequest struct {
	GroupID   string `json:"group_id"`
	UserUID   string `json:"user_uid"`
	UserNcuid string `json:"user_ncuid"`
}

type groupAdminRequest struct {
	GroupID   string `json:"group_id"`
	UserUID   string `json:"user_uid"`
	UserNcuid string `json:"user_ncuid"`
	Admin     bool   `json:"admin"`
}

type groupAvatarRequest struct {
	GroupID   string `json:"group_id"`
	AvatarURL string `json:"avatar_url"`
}

type groupJoinRequestItem struct {
	RequestID   string `json:"request_id"`
	UID         string `json:"uid"`
	Ncuid       string `json:"ncuid"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	UserTitle   string `json:"user_title"`
	AvatarURL   string `json:"avatar_url"`
	CreatedAt   int64  `json:"created_at"`
}

type groupJoinRequestsResponse struct {
	Requests []groupJoinRequestItem `json:"requests"`
}

func (a *API) handleGroupList(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	groups, err := a.groups.ListByUser(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := groupListResponse{Groups: make([]groupListItem, 0, len(groups))}
	for _, g := range groups {
		readAt := int64(0)
		if g.AnnouncementReadAt.Valid {
			readAt = g.AnnouncementReadAt.Time.Unix()
		}
		updatedAt := int64(0)
		if !g.AnnouncementUpdatedAt.IsZero() {
			updatedAt = g.AnnouncementUpdatedAt.Unix()
		}
		resp.Groups = append(resp.Groups, groupListItem{
			GroupID:               g.ID,
			Name:                  g.Name,
			AvatarURL:             g.AvatarURL,
			JoinApproval:          g.JoinApproval,
			GlobalMute:            g.GlobalMute,
			Announcement:          g.Announcement,
			AnnouncementMode:      g.AnnouncementMode,
			AnnouncementUpdatedAt: updatedAt,
			AnnouncementReadAt:    readAt,
			MemberCount:           g.MemberCount,
			MemberVersion:         g.MemberVersion,
			MessageSeq:            g.MessageSeq,
			Role:                  g.Role,
		})
	}

	writeJSON(w, http.StatusOK, resp)
}


func (a *API) handleGroupMembers(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("group_id")))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	limitText := strings.TrimSpace(r.URL.Query().Get("limit"))
	offsetText := strings.TrimSpace(r.URL.Query().Get("offset"))
	limit, offset := 0, 0
	paged := limitText != "" || offsetText != ""
	if paged {
		limit = 100
		offset = 0
		if limitText != "" {
			limit, _ = strconv.Atoi(limitText)
		}
		if offsetText != "" {
			offset, _ = strconv.Atoi(offsetText)
		}
		if limit < 1 {
			limit = 100
		}
		if limit > 200 {
			limit = 200
		}
		if offset < 0 {
			offset = 0
		}
	}
	var total int
	var err error
	var members []data.GroupMemberEntry
	if paged {
		members, total, err = a.groups.ListMembersPage(ctx, groupID, limit, offset)
	} else {
		members, err = a.groups.ListMembers(ctx, groupID)
		total = len(members)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := groupMembersResponse{Members: make([]groupMemberItem, 0, len(members)), Total: total, Offset: offset, Limit: limit}
	for _, m := range members {
		resp.Members = append(resp.Members, groupMemberItem{
			UID:         m.UID,
			Ncuid:       m.Ncuid,
			Username:    m.Username,
			DisplayName: m.DisplayName,
			UserTitle:   m.UserTitle,
			AvatarURL:   m.AvatarURL,
			Role:        m.Role,
			JoinedAt:    m.JoinedAt.Unix(),
		})
	}
	if paged {
		resp.HasMore = offset+len(members) < total
	}
	if mv, err := a.groups.GetMemberVersion(ctx, groupID); err == nil {
		resp.MemberVersion = mv
	}

	writeJSON(w, http.StatusOK, resp)
}

// handleGroupMembersLookup 按 ncuid 批量查询成员资料（客户端优先缓存当前可见发送者）。
func (a *API) handleGroupMembersLookup(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("group_id")))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}
	raw := strings.TrimSpace(r.URL.Query().Get("ncuids"))
	if raw == "" {
		writeError(w, http.StatusBadRequest, "invalid_ncuids", "missing ncuids")
		return
	}
	parts := strings.Split(raw, ",")
	ncuids := make([]string, 0, len(parts))
	for _, p := range parts {
		// 注意：ncuid 是大小写敏感的（小写 nc_xxx / 大写 USR-xxx），
		// 不能 ToUpper，否则 nc_ 开头的全部匹配不到数据库里的原值。
		s := strings.TrimSpace(p)
		if s != "" {
			ncuids = append(ncuids, s)
		}
	}
	if len(ncuids) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_ncuids", "empty ncuids")
		return
	}
	if len(ncuids) > 50 {
		writeError(w, http.StatusBadRequest, "too_many_ncuids", "at most 50 ncuids")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	members, err := a.groups.LookupMembersByNcuids(ctx, groupID, ncuids)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := groupMembersResponse{Members: make([]groupMemberItem, 0, len(members)), Total: len(members)}
	for _, m := range members {
		resp.Members = append(resp.Members, groupMemberItem{
			UID:         m.UID,
			Ncuid:       m.Ncuid,
			Username:    m.Username,
			DisplayName: m.DisplayName,
			UserTitle:   m.UserTitle,
			AvatarURL:   m.AvatarURL,
			Role:        m.Role,
			JoinedAt:    m.JoinedAt.Unix(),
		})
	}
	writeJSON(w, http.StatusOK, resp)
}

func (a *API) handleGroupInvite(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupInviteRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_input", "invalid input")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	actorRole, err := a.groups.GetRole(ctx, groupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_admin", "not allowed")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if actorRole < data.GroupRoleAdmin {
		writeError(w, http.StatusForbidden, "not_admin", "not allowed")
		return
	}

	targetUser, err := a.resolveUserIdentity(ctx, req.UserUID, req.UserNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}

	areFriends, err := a.friends.AreFriends(ctx, claims.Subject, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if !areFriends {
		writeError(w, http.StatusForbidden, "not_friends", "not friends")
		return
	}

	if _, err = a.groups.GetRole(ctx, groupID, targetUser.ID); err == nil {
		writeError(w, http.StatusConflict, "already_member", "already member")
		return
	} else if err != data.ErrNotFound {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	rejectInvites, err := a.users.RejectGroupInvites(ctx, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if rejectInvites {
		group, groupErr := a.groups.GetByID(ctx, groupID)
		if groupErr != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
		go a.sendGroupInvitationRejectedNotification(group.OwnerID, targetUser.ID, groupID, true)
		writeJSON(w, http.StatusOK, groupInvitationCreatedResponse{Status: "auto_rejected"})
		return
	}
	pending, err := a.groupInvites.Pending(ctx, groupID, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if pending {
		writeError(w, http.StatusConflict, "invitation_pending", "invitation pending")
		return
	}
	invitation := &data.GroupInvitation{
		ID: nanoid.New(), GroupID: groupID, InviterID: claims.Subject,
		InviteeID: targetUser.ID, Status: data.GroupInvitationPending,
	}
	if err = a.groupInvites.Create(ctx, invitation); err != nil {
		if isUniqueViolation(err, "idx_group_invitation_pending") {
			writeError(w, http.StatusConflict, "invitation_pending", "invitation pending")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusCreated, groupInvitationCreatedResponse{
		Status: "pending", RequestID: invitation.ID,
	})
}

func (a *API) handleGroupAdmin(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupAdminRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_input", "invalid input")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	actorRole, err := a.groups.GetRole(ctx, groupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_owner", "not allowed")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if actorRole != data.GroupRoleOwner {
		writeError(w, http.StatusForbidden, "not_owner", "not allowed")
		return
	}

	targetUser, err := a.resolveUserIdentity(ctx, req.UserUID, req.UserNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}

	targetRole, err := a.groups.GetRole(ctx, groupID, targetUser.ID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "member_not_found", "member not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if targetRole == data.GroupRoleOwner {
		writeError(w, http.StatusForbidden, "cannot_update_owner", "not allowed")
		return
	}

	newRole := data.GroupRoleMember
	if req.Admin {
		newRole = data.GroupRoleAdmin
	}
	if err := a.groups.UpdateRole(ctx, groupID, targetUser.ID, newRole); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "member_not_found", "member not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

func (a *API) handleGroupAvatar(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupAvatarRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	avatarURL := strings.TrimSpace(req.AvatarURL)
	if !isValidGroupID(groupID) || !isValidAvatarURL(avatarURL) {
		writeError(w, http.StatusBadRequest, "invalid_input", "invalid input")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	role, err := a.groups.GetRole(ctx, groupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_owner", "not allowed")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if role != data.GroupRoleOwner {
		writeError(w, http.StatusForbidden, "not_owner", "not allowed")
		return
	}

	if err := a.groups.UpdateAvatar(ctx, groupID, avatarURL); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

func (a *API) handleGroupJoinRequests(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("group_id")))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	role, err := a.groups.GetRole(ctx, groupID, claims.Subject)
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

	requests, err := a.groupJoins.ListPendingByGroup(ctx, groupID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := groupJoinRequestsResponse{Requests: make([]groupJoinRequestItem, 0, len(requests))}
	for _, item := range requests {
		resp.Requests = append(resp.Requests, groupJoinRequestItem{
			RequestID:   item.ID,
			UID:         item.UID,
			Ncuid:       item.Ncuid,
			Username:    item.Username,
			DisplayName: item.DisplayName,
			UserTitle:   item.UserTitle,
			AvatarURL:   item.AvatarURL,
			CreatedAt:   item.CreatedAt.Unix(),
		})
	}

	writeJSON(w, http.StatusOK, resp)
}
