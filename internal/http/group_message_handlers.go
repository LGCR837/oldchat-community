package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

type groupMessageSendRequest struct {
	GroupID          string `json:"group_id"`
	Body             string `json:"body"`
	MsgType          string `json:"msg_type"`
	MediaURL         string `json:"media_url"`
	ThumbURL         string `json:"thumb_url"`
	OriginalURL      string `json:"original_url,omitempty"`
	DurationMS       int    `json:"duration_ms"`
	BurnAfterSeconds int    `json:"burn_after_seconds"`
}

type groupMessageResponse struct {
	SortSeq          int64  `json:"sort_seq,omitempty"`
	GroupSeq         int64  `json:"group_seq,omitempty"`
	ID               string `json:"id"`
	GroupID          string `json:"group_id"`
	FromUID          string `json:"from_uid"`
	FromNcuid        string `json:"from_ncuid,omitempty"`
	FromName         string `json:"from_name,omitempty"`
	Body             string `json:"body"`
	MsgType          string `json:"msg_type"`
	MediaURL         string `json:"media_url,omitempty"`
	ThumbURL         string `json:"thumb_url,omitempty"`
	OriginalURL      string `json:"original_url,omitempty"`
	DurationMS       int    `json:"duration_ms,omitempty"`
	BurnAfterSeconds int    `json:"burn_after_seconds,omitempty"`
	BurnStartAt      *int64 `json:"burn_start_at,omitempty"`
	ReadCount        int    `json:"read_count,omitempty"`
	CreatedAt        int64  `json:"created_at"`
}

type groupMessagesResponse struct {
	Messages        []groupMessageResponse `json:"messages"`
	EffectiveOffset int                    `json:"effective_offset,omitempty"`
	HasMore         bool                   `json:"has_more"`
	NextCreatedAt   int64                  `json:"next_before_created_at,omitempty"`
	NextID          string                 `json:"next_before_id,omitempty"`
	NextGroupSeq    int64                  `json:"next_group_seq,omitempty"`
	ServerGroupSeq  int64                  `json:"server_group_seq,omitempty"`
}

type groupUnreadRequest struct {
	Limit  int `json:"limit"`
	Offset int `json:"offset"`
}

type groupReadRequest struct {
	GroupID string `json:"group_id"`
}

type groupUnreadMessageResponse struct {
	SortSeq          int64  `json:"sort_seq,omitempty"`
	GroupSeq         int64  `json:"group_seq,omitempty"`
	ID               string `json:"id"`
	GroupID          string `json:"group_id"`
	FromUID          string `json:"from_uid"`
	FromNcuid        string `json:"from_ncuid,omitempty"`
	Body             string `json:"body"`
	MsgType          string `json:"msg_type"`
	MediaURL         string `json:"media_url,omitempty"`
	ThumbURL         string `json:"thumb_url,omitempty"`
	OriginalURL      string `json:"original_url,omitempty"`
	DurationMS       int    `json:"duration_ms,omitempty"`
	BurnAfterSeconds int    `json:"burn_after_seconds,omitempty"`
	CreatedAt        int64  `json:"created_at"`
}

type groupUnreadResponse struct {
	Messages []groupUnreadMessageResponse `json:"messages"`
	HasMore  bool                         `json:"has_more"`
}

func (a *API) handleGroupMessageSend(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupMessageSendRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	body := strings.TrimSpace(req.Body)
	msgType := strings.ToLower(strings.TrimSpace(req.MsgType))
	if msgType == "" {
		msgType = "text"
	}
	mediaURL := strings.TrimSpace(req.MediaURL)
	thumbURL := strings.TrimSpace(req.ThumbURL)
	originalURL := strings.TrimSpace(req.OriginalURL)
	if msgType == "resource" && isResourceUploadURL(mediaURL) {
		writeError(w, http.StatusForbidden, "resource_share_disabled", "resource share disabled")
		return
	}
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}
	switch msgType {
	case "text":
		if !isValidMessage(body) {
			writeError(w, http.StatusBadRequest, "invalid_body", "invalid message")
			return
		}
	case "video":
		if !a.cfg.VideoEnabled {
			writeError(w, http.StatusForbidden, "video_disabled", "video send disabled")
			return
		}
		if mediaURL == "" || len(mediaURL) > 1024 {
			writeError(w, http.StatusBadRequest, "invalid_media", "invalid media url")
			return
		}
		if body == "" {
			body = placeholderBody(msgType)
		}
	case "image", "voice", "resource":
		if mediaURL == "" || len(mediaURL) > 1024 {
			writeError(w, http.StatusBadRequest, "invalid_media", "invalid media url")
			return
		}
		if msgType == "voice" {
			if req.DurationMS <= 0 {
				writeError(w, http.StatusBadRequest, "invalid_duration", "invalid duration")
				return
			}
			if req.DurationMS > maxVoiceDurationMS {
				writeError(w, http.StatusBadRequest, "duration_too_long", "voice duration too long")
				return
			}
		}
		if body == "" {
			body = placeholderBody(msgType)
		}
	default:
		writeError(w, http.StatusBadRequest, "invalid_type", "invalid message type")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
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

	role, err := a.groups.GetRole(ctx, groupID, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if group.GlobalMute && role < data.GroupRoleAdmin {
		writeError(w, http.StatusForbidden, "group_muted", "group is muted")
		return
	}

	// JWT 中的 UID 可能在用户修改公开 UID 后过期；消息身份必须来自数据库当前值，
	// 且客户端只以不可变 NCUID 作为关联键。
	sender, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	// 群组每日发言次数限制（仅针对信誉分 <= 120 的非管理员/群主成员）
	if group.DailyMsgLimit > 0 && role < data.GroupRoleAdmin && claims.Subject != group.OwnerID && claims.Subject != "SYSTEM" {
		if sender.ReputationScore <= 120 {
			dailySent, err := a.groupMsgs.CountDailyUserMessages(ctx, groupID, claims.Subject)
			if err == nil && dailySent >= group.DailyMsgLimit {
				writeError(w, http.StatusForbidden, "daily_message_limit_reached",
					fmt.Sprintf("您今日在%s的发言次数已达上限（%d条/天，信誉分高于120分可解除限制）", group.Name, group.DailyMsgLimit))
				return
			}
		}
	}

	// 归一化文本消息体：权威校验并回填 quote/mention 的不可变 NCUID，拒绝废弃格式。
	if msgType == "text" {
		sanitized, ok := a.sanitizeMessageBodyForSend(ctx, claims.Subject, groupID, msgType, body)
		if !ok {
			writeError(w, http.StatusBadRequest, "invalid_body", "invalid message")
			return
		}
		body = sanitized
	}

	msgID := nanoid.New()

	msg := &data.GroupMessage{
		ID:               msgID,
		GroupID:          groupID,
		SenderID:         claims.Subject,
		Body:             body,
		MsgType:          msgType,
		MediaURL:         mediaURL,
		ThumbURL:         thumbURL,
		OriginalURL:      originalURL,
		DurationMS:       req.DurationMS,
		BurnAfterSeconds: req.BurnAfterSeconds,
	}
	if err := a.groupMsgs.Create(ctx, msg); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := groupMessageResponse{
		SortSeq:          msg.SortSeq,
		GroupSeq:         msg.GroupSeq,
		ID:               msgID,
		GroupID:          groupID,
		FromUID:          sender.UID,
		FromNcuid:        sender.Ncuid,
		FromName:         resolveSenderDisplayName(sender),
		Body:             body,
		MsgType:          msgType,
		MediaURL:         mediaURL,
		ThumbURL:         thumbURL,
		OriginalURL:      originalURL,
		DurationMS:       req.DurationMS,
		BurnAfterSeconds: req.BurnAfterSeconds,
		CreatedAt:        time.Now().Unix(),
	}
	writeJSON(w, http.StatusCreated, resp)
	chatLogf("%s GRP %s | %s: %s", time.Now().Format("15:04:05"), groupID, sender.UID, formatChatPreview(msgType, body))

	env := wsEnvelope{
		Type: "group_message",
		Data: resp,
	}
	payload, err := json.Marshal(env)
	if err != nil {
		return
	}
	broadcastCtx, broadcastCancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer broadcastCancel()
	memberIDs, err := a.groups.ListMemberIDs(broadcastCtx, groupID)
	if err != nil {
		return
	}
	recipients := make([]string, 0, len(memberIDs))
	for _, memberID := range memberIDs {
		if memberID != claims.Subject {
			recipients = append(recipients, memberID)
		}
	}
	a.wsHub.BroadcastToUsers(recipients, payload)

	// 离线补偿只依赖每群唯一的 group_message_sequences.last_seq。
	// 不向 account_events 写成员级 pointer；客户端重连或进群时比较 group_seq 后增量拉取。
}

func (a *API) handleGroupMessages(w http.ResponseWriter, r *http.Request) {
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

	limit := parseLimit(r.URL.Query().Get("limit"))
	before := parseBefore(r.URL.Query().Get("before"))

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.groups.GetByID(ctx, groupID); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	msgs, err := a.groupMsgs.ListByGroup(ctx, groupID, limit, before)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := make([]groupMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		fromUID := msg.SenderUID
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		resp = append(resp, groupMessageResponse{
			SortSeq:          msg.SortSeq,
			GroupSeq:         msg.GroupSeq,
			ID:               msg.ID,
			GroupID:          msg.GroupID,
			FromUID:          fromUID,
			FromNcuid:        msg.SenderNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
		})
	}

	writeJSON(w, http.StatusOK, groupMessagesResponse{Messages: resp})
	if before.IsZero() {
		_ = a.groups.MarkRead(ctx, groupID, claims.Subject)
	}
}

// handleGroupMessagesAfterSeq 为新客户端提供正向增量同步；旧历史接口保持不变。
func (a *API) handleGroupMessagesAfterSeq(w http.ResponseWriter, r *http.Request) {
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
	afterSeq, err := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("after_seq")), 10, 64)
	if err != nil || afterSeq < 0 {
		writeError(w, http.StatusBadRequest, "invalid_after_seq", "invalid after seq")
		return
	}
	limit := parseLimit(r.URL.Query().Get("limit"))
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err = a.groups.GetByID(ctx, groupID); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
		} else {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		}
		return
	}
	if _, err = a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
		} else {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		}
		return
	}
	serverSeq, err := a.groupMsgs.CurrentGroupSeq(ctx, groupID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	msgs, err := a.groupMsgs.ListByGroupAfterSeq(ctx, groupID, afterSeq, limit+1)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	hasMore := len(msgs) > limit
	if hasMore {
		msgs = msgs[:limit]
	}
	nextSeq := afterSeq
	resp := make([]groupMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		if msg.GroupSeq > nextSeq {
			nextSeq = msg.GroupSeq
		}
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		resp = append(resp, groupMessageResponse{
			SortSeq: msg.SortSeq, GroupSeq: msg.GroupSeq, ID: msg.ID, GroupID: msg.GroupID,
			FromUID: msg.SenderUID, FromNcuid: msg.SenderNcuid, Body: msg.Body, MsgType: msgType,
			MediaURL: msg.MediaURL, ThumbURL: msg.ThumbURL, DurationMS: msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds, BurnStartAt: burnStartAt, CreatedAt: msg.Created.Unix(),
		})
	}
	// 被旧版本遗留的孤立发送者消息在历史接口中本就不可见，视作 tombstone；
	// 最后一页将水位推进到服务器当前值，避免反复扫描这些不可见序号。
	if !hasMore && serverSeq > nextSeq {
		nextSeq = serverSeq
	}
	writeJSON(w, http.StatusOK, groupMessagesResponse{
		Messages: resp, HasMore: hasMore, NextGroupSeq: nextSeq, ServerGroupSeq: serverSeq,
	})
}

// resolveSenderDisplayName 从用户对象解析显示名（回退 username）。
func resolveSenderDisplayName(u *data.User) string {
	if u == nil {
		return ""
	}
	if u.DisplayName != "" {
		return u.DisplayName
	}
	return u.Username
}

// fillGroupMessageFromNames 批量填充消息的 from_name（按 from_uid 查显示名）。
// 客户端收到消息后据此缓存 sender 名字，避免 pending 时显示 UID/NCUID。
func (a *API) fillGroupMessageFromNames(ctx context.Context, resp []groupMessageResponse) {
	if len(resp) == 0 {
		return
	}
	// 收集唯一 UID，批量查
	seen := make(map[string]bool, len(resp))
	uids := make([]string, 0, len(resp))
	for i := range resp {
		uid := resp[i].FromUID
		if uid == "" || seen[uid] {
			continue
		}
		seen[uid] = true
		uids = append(uids, uid)
	}
	if len(uids) == 0 {
		return
	}
	for _, uid := range uids {
		name, err := a.users.GetDisplayNameByUID(ctx, uid)
		if err != nil || name == "" {
			continue
		}
		for i := range resp {
			if resp[i].FromUID == uid {
				resp[i].FromName = name
			}
		}
	}
}

func (a *API) handleGroupMessagesV2(w http.ResponseWriter, r *http.Request) {
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

	limit := parseLimit(r.URL.Query().Get("limit"))
	offset := parseOffset(r.URL.Query().Get("offset"))
	anchorMessageID := strings.TrimSpace(r.URL.Query().Get("anchor_message_id"))
	beforeID := strings.TrimSpace(r.URL.Query().Get("before_id"))
	beforeUnix, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("before_created_at")), 10, 64)
	beforeSeq, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("before_seq")), 10, 64)

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.groups.GetByID(ctx, groupID); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if anchorMessageID != "" {
		anchorOffset, err := a.groupMsgs.GetMessageOffset(ctx, groupID, anchorMessageID)
		if err != nil {
			if err != data.ErrNotFound {
				writeError(w, http.StatusInternalServerError, "db_error", "internal error")
				return
			}
		} else {
			offset = anchorOffset - (limit / 2)
			if offset < 0 {
				offset = 0
			}
		}
	}

	// 恒多取 1 条用于探测是否还有更多：limit >= 100 时同样需要 +1，
	// 否则 hasMore 判断（len(msgs) > limit）在满页时永远为 false，翻页丢数据。
	fetchLimit := limit + 1
	var msgs []data.GroupMessage
	var err error
	switch {
	case beforeSeq > 0:
		// Keyset：按 group_seq 倒序翻页（持续写入数据流上稳定）。
		msgs, err = a.groupMsgs.ListByGroupBeforeSeq(ctx, groupID, fetchLimit, beforeSeq)
	case anchorMessageID == "" && beforeUnix > 0 && beforeID != "":
		msgs, err = a.groupMsgs.ListByGroupWithCursor(ctx, groupID, fetchLimit, beforeUnix, beforeID)
	default:
		msgs, err = a.groupMsgs.ListByGroupWithOffset(ctx, groupID, fetchLimit, offset)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	hasMore := len(msgs) > limit
	if hasMore {
		msgs = msgs[:limit]
	}
	var nextCreatedAt int64
	var nextID string
	if len(msgs) > 0 {
		oldest := msgs[len(msgs)-1]
		nextCreatedAt = oldest.Created.Unix()
		nextID = oldest.ID
	}

	resp := make([]groupMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		fromUID := msg.SenderUID
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		resp = append(resp, groupMessageResponse{
			SortSeq:          msg.SortSeq,
			GroupSeq:         msg.GroupSeq,
			ID:               msg.ID,
			GroupID:          msg.GroupID,
			FromUID:          fromUID,
			FromNcuid:        msg.SenderNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
		})
	}

	// 批量填充 from_name（客户端据此缓存 sender 名字）
	a.fillGroupMessageFromNames(ctx, resp)

	writeJSON(w, http.StatusOK, groupMessagesResponse{
		Messages: resp, EffectiveOffset: offset, HasMore: hasMore,
		NextCreatedAt: nextCreatedAt, NextID: nextID,
	})
	// 默认保留旧客户端“首屏 GET 即已读”行为；新客户端显式 mark_read=0 后调用 /groups/read。
	if offset == 0 && strings.TrimSpace(r.URL.Query().Get("mark_read")) != "0" {
		_ = a.groups.MarkRead(ctx, groupID, claims.Subject)
	}
}

func (a *API) handleGroupMessagesSearch(w http.ResponseWriter, r *http.Request) {
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
	keyword := strings.TrimSpace(r.URL.Query().Get("q"))
	if keyword == "" {
		writeJSON(w, http.StatusOK, groupMessagesResponse{Messages: []groupMessageResponse{}})
		return
	}
	kind := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("kind")))
	if kind != "" && kind != "all" && kind != "text" && kind != "media" {
		kind = "all"
	}

	limit := parseLimit(r.URL.Query().Get("limit"))
	offset := parseOffset(r.URL.Query().Get("offset"))

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.groups.GetByID(ctx, groupID); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "group_not_found", "group not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	msgs, err := a.groupMsgs.SearchByGroupWithOffset(ctx, groupID, keyword, kind, limit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := make([]groupMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		fromUID := msg.SenderUID
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		resp = append(resp, groupMessageResponse{
			SortSeq:          msg.SortSeq,
			GroupSeq:         msg.GroupSeq,
			ID:               msg.ID,
			GroupID:          msg.GroupID,
			FromUID:          fromUID,
			FromNcuid:        msg.SenderNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
		})
	}

	writeJSON(w, http.StatusOK, groupMessagesResponse{Messages: resp})
}

func (a *API) handleGroupUnread(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupUnreadRequest
	_ = decodeJSON(w, r, &req)
	limit := req.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}

	if strings.Contains(r.UserAgent(), "Python") || strings.Contains(clientIP(r), "154.37.214.90") {
		log.Printf("[UNREAD_BOT_TRACE] user_id=%s ip=%s ua=%s", claims.Subject, clientIP(r), r.UserAgent())
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	offset := req.Offset
	if offset < 0 {
		offset = 0
	}
	if offset >= unreadBootstrapMaxMessages {
		writeJSON(w, http.StatusOK, groupUnreadResponse{
			Messages: []groupUnreadMessageResponse{}, HasMore: false})
		return
	}
	if remaining := unreadBootstrapMaxMessages - offset; limit > remaining {
		limit = remaining
	}
	fetchLimit := limit
	if limit < 200 {
		fetchLimit = limit + 1
	}
	unread, err := a.groupMsgs.ListUnreadByUserOffset(ctx, claims.Subject, fetchLimit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	hasMore := len(unread) > limit
	if hasMore {
		unread = unread[:limit]
	} else if limit == 200 && len(unread) == limit {
		hasMore = true
	}
	if offset+len(unread) >= unreadBootstrapMaxMessages {
		hasMore = false
	}
	resp := make([]groupUnreadMessageResponse, 0, len(unread))
	for _, msg := range unread {
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		resp = append(resp, groupUnreadMessageResponse{
			SortSeq:          msg.SortSeq,
			GroupSeq:         msg.GroupSeq,
			ID:               msg.ID,
			GroupID:          msg.GroupID,
			FromUID:          msg.SenderUID,
			FromNcuid:        msg.SenderNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			CreatedAt:        msg.Created.Unix(),
		})
	}

	writeJSON(w, http.StatusOK, groupUnreadResponse{Messages: resp, HasMore: hasMore})
}

func (a *API) handleGroupRead(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req groupReadRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	groupID := strings.ToUpper(strings.TrimSpace(req.GroupID))
	if !isValidGroupID(groupID) {
		writeError(w, http.StatusBadRequest, "invalid_group_id", "invalid group id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	if _, err := a.groups.GetRole(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusForbidden, "not_member", "not a member")
			return
		}
		log.Printf("[500 ERROR] handleGroupRead GetRole group=%s user=%s: %v", groupID, claims.Subject, err)
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if err := a.groups.MarkRead(ctx, groupID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			// 幂等放行：已标记或无需更新时直接返回成功
			writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
			return
		}
		log.Printf("[500 ERROR] handleGroupRead MarkRead group=%s user=%s: %v", groupID, claims.Subject, err)
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

func (a *API) handleGroupBurnOpen(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req struct {
		MessageID string `json:"message_id"`
	}
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	if err := a.groupMsgs.MarkBurnOpened(ctx, req.MessageID, claims.Subject); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}
