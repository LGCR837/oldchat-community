package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

const maxVoiceDurationMS = 60000

type directSendRequest struct {
	ToUID            string `json:"to_uid"`
	ToNcuid          string `json:"to_ncuid"`
	Body             string `json:"body"`
	MsgType          string `json:"msg_type"`
	MediaURL         string `json:"media_url"`
	ThumbURL         string `json:"thumb_url"`
	OriginalURL      string `json:"original_url,omitempty"`
	DurationMS       int    `json:"duration_ms"`
	BurnAfterSeconds int    `json:"burn_after_seconds"`
}

type directMessageResponse struct {
	SortSeq          int64  `json:"sort_seq,omitempty"`
	ID               string `json:"id"`
	ThreadID         string `json:"thread_id"`
	FromUID          string `json:"from_uid"`
	FromNcuid        string `json:"from_ncuid,omitempty"`
	Body             string `json:"body"`
	MsgType          string `json:"msg_type"`
	MediaURL         string `json:"media_url,omitempty"`
	ThumbURL         string `json:"thumb_url,omitempty"`
	OriginalURL      string `json:"original_url,omitempty"`
	DurationMS       int    `json:"duration_ms,omitempty"`
	BurnAfterSeconds int    `json:"burn_after_seconds,omitempty"`
	BurnStartAt      *int64 `json:"burn_start_at,omitempty"`
	CreatedAt        int64  `json:"created_at"`
	DeliveredAt      *int64 `json:"delivered_at,omitempty"`
	ReadAt           *int64 `json:"read_at,omitempty"`
}

type directMessagesResponse struct {
	Messages        []directMessageResponse `json:"messages"`
	EffectiveOffset int                     `json:"effective_offset,omitempty"`
	HasMore         bool                    `json:"has_more"`
	NextCreatedAt   int64                   `json:"next_before_created_at,omitempty"`
	NextID          string                  `json:"next_before_id,omitempty"`
}

type directUnreadRequest struct {
	Limit  int `json:"limit"`
	Offset int `json:"offset"`
}

type directUnreadMessageResponse struct {
	SortSeq          int64  `json:"sort_seq,omitempty"`
	ID               string `json:"id"`
	ThreadID         string `json:"thread_id"`
	FromUID          string `json:"from_uid"`
	FromNcuid        string `json:"from_ncuid,omitempty"`
	PeerUID          string `json:"peer_uid"`
	PeerNcuid        string `json:"peer_ncuid"`
	Body             string `json:"body"`
	MsgType          string `json:"msg_type"`
	MediaURL         string `json:"media_url,omitempty"`
	ThumbURL         string `json:"thumb_url,omitempty"`
	OriginalURL      string `json:"original_url,omitempty"`
	DurationMS       int    `json:"duration_ms,omitempty"`
	BurnAfterSeconds int    `json:"burn_after_seconds,omitempty"`
	BurnStartAt      *int64 `json:"burn_start_at,omitempty"`
	CreatedAt        int64  `json:"created_at"`
	DeliveredAt      *int64 `json:"delivered_at,omitempty"`
	ReadAt           *int64 `json:"read_at,omitempty"`
}

type directUnreadResponse struct {
	Messages []directUnreadMessageResponse `json:"messages"`
	HasMore  bool                          `json:"has_more"`
}

type directReadRequest struct {
	WithUID   string `json:"with_uid"`
	WithNcuid string `json:"with_ncuid"`
}

func (a *API) handleDirectSend(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req directSendRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

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
	currentUser, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	targetUser, err := a.resolveUserIdentity(ctx, req.ToUID, req.ToNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}
	if currentUser.ID == targetUser.ID {
		writeError(w, http.StatusBadRequest, "invalid_identity", "cannot message yourself")
		return
	}

	areFriends, err := a.friends.AreFriends(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if !areFriends {
		writeError(w, http.StatusForbidden, "not_friends", "not friends")
		return
	}

	threadID, err := a.direct.GetThreadID(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		if err == data.ErrNotFound {
			newID := nanoid.New()
			threadID, err = a.direct.GetOrCreateThread(ctx, currentUser.ID, targetUser.ID, newID)
			if err != nil {
				writeError(w, http.StatusInternalServerError, "db_error", "internal error")
				return
			}
		} else {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
	}

	// 归一化文本消息体：权威校验并回填 quote/mention 的不可变 NCUID，拒绝废弃格式。
	if msgType == "text" {
		sanitized, ok := a.sanitizeMessageBodyForSend(ctx, claims.Subject, "", msgType, body)
		if !ok {
			writeError(w, http.StatusBadRequest, "invalid_body", "invalid message")
			return
		}
		body = sanitized
	}

	msgID := nanoid.New()

	msg := &data.DirectMessage{
		ID:               msgID,
		ThreadID:         threadID,
		SenderID:         currentUser.ID,
		Body:             body,
		MsgType:          msgType,
		MediaURL:         mediaURL,
		ThumbURL:         thumbURL,
		OriginalURL:      originalURL,
		DurationMS:       req.DurationMS,
		BurnAfterSeconds: req.BurnAfterSeconds,
	}
	if err := a.direct.CreateMessage(ctx, msg); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := directMessageResponse{
		SortSeq:          msg.SortSeq,
		ID:               msgID,
		ThreadID:         threadID,
		FromUID:          currentUser.UID,
		FromNcuid:        currentUser.Ncuid,
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
	chatLogf("%s DM %s -> %s | %s", time.Now().Format("15:04:05"), currentUser.UID, targetUser.UID, formatChatPreview(msgType, body))

	// 旧版兼容：仍推旧格式 direct_message 帧（老客户端依赖）。
	env := wsEnvelope{
		Type: "direct_message",
		Data: resp,
	}
	payload, err := json.Marshal(env)
	if err == nil {
		a.wsHub.BroadcastToUser(targetUser.ID, payload)
	}
	// 账号级事件流：接收方写入 DIRECT_MESSAGE_NEW（新版客户端消费）。
	payloadForPts := map[string]interface{}{
		"message": resp,
	}
	if a.events != nil {
		if _, err := a.events.Emit(context.Background(), targetUser.ID, "DIRECT_MESSAGE_NEW", msgID, payloadForPts); err != nil {
			chatLogf("emit direct_message_new failed: %v", err)
		}
	}
}

func (a *API) handleDirectMessages(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	withUID := r.URL.Query().Get("with_uid")
	withNcuid := r.URL.Query().Get("with_ncuid")

	limit := parseLimit(r.URL.Query().Get("limit"))
	before := parseBefore(r.URL.Query().Get("before"))

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	currentUser, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	targetUser, err := a.resolveUserIdentity(ctx, withUID, withNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}

	areFriends, err := a.friends.AreFriends(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if !areFriends {
		writeError(w, http.StatusForbidden, "not_friends", "not friends")
		return
	}

	threadID, err := a.direct.GetThreadID(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		if err == data.ErrNotFound {
			writeJSON(w, http.StatusOK, directMessagesResponse{Messages: []directMessageResponse{}})
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	msgs, err := a.direct.ListMessages(ctx, threadID, limit, before)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	_ = a.direct.MarkDelivered(ctx, threadID, currentUser.ID)

	resp := make([]directMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		fromUID := targetUser.UID
		fromNcuid := targetUser.Ncuid
		if msg.SenderID == currentUser.ID {
			fromUID = currentUser.UID
			fromNcuid = currentUser.Ncuid
		}
		var deliveredAt *int64
		if msg.DeliveredAt != nil {
			ts := msg.DeliveredAt.Unix()
			deliveredAt = &ts
		}
		var readAt *int64
		if msg.ReadAt != nil {
			ts := msg.ReadAt.Unix()
			readAt = &ts
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		resp = append(resp, directMessageResponse{
			SortSeq:          msg.SortSeq,
			ID:               msg.ID,
			ThreadID:         msg.ThreadID,
			FromUID:          fromUID,
			FromNcuid:        fromNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
			DeliveredAt:      deliveredAt,
			ReadAt:           readAt,
		})
	}

	writeJSON(w, http.StatusOK, directMessagesResponse{Messages: resp})
}

func (a *API) handleDirectMessagesV2(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	withUID := r.URL.Query().Get("with_uid")
	withNcuid := r.URL.Query().Get("with_ncuid")

	limit := parseLimit(r.URL.Query().Get("limit"))
	offset := parseOffset(r.URL.Query().Get("offset"))
	anchorMessageID := strings.TrimSpace(r.URL.Query().Get("anchor_message_id"))
	beforeID := strings.TrimSpace(r.URL.Query().Get("before_id"))
	beforeUnix, _ := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("before_created_at")), 10, 64)

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	currentUser, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	targetUser, err := a.resolveUserIdentity(ctx, withUID, withNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}

	areFriends, err := a.friends.AreFriends(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if !areFriends {
		writeError(w, http.StatusForbidden, "not_friends", "not friends")
		return
	}

	threadID, err := a.direct.GetThreadID(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		if err == data.ErrNotFound {
			writeJSON(w, http.StatusOK, directMessagesResponse{Messages: []directMessageResponse{}, EffectiveOffset: offset})
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if anchorMessageID != "" {
		anchorOffset, err := a.direct.GetMessageOffset(ctx, threadID, anchorMessageID)
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

	fetchLimit := limit
	if limit < 100 {
		fetchLimit = limit + 1
	}
	var msgs []data.DirectMessage
	if anchorMessageID == "" && beforeUnix > 0 && beforeID != "" {
		msgs, err = a.direct.ListMessagesWithCursor(ctx, threadID, fetchLimit, beforeUnix, beforeID)
	} else {
		msgs, err = a.direct.ListMessagesWithOffset(ctx, threadID, fetchLimit, offset)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	_ = a.direct.MarkDelivered(ctx, threadID, currentUser.ID)

	hasMore := len(msgs) > limit
	if hasMore {
		msgs = msgs[:limit]
	} else if limit == 100 && len(msgs) == limit {
		hasMore = true
	}
	var nextCreatedAt int64
	var nextID string
	if len(msgs) > 0 {
		oldest := msgs[len(msgs)-1]
		nextCreatedAt = oldest.Created.Unix()
		nextID = oldest.ID
	}
	resp := make([]directMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		fromUID := targetUser.UID
		fromNcuid := targetUser.Ncuid
		if msg.SenderID == currentUser.ID {
			fromUID = currentUser.UID
			fromNcuid = currentUser.Ncuid
		}
		var deliveredAt *int64
		if msg.DeliveredAt != nil {
			ts := msg.DeliveredAt.Unix()
			deliveredAt = &ts
		}
		var readAt *int64
		if msg.ReadAt != nil {
			ts := msg.ReadAt.Unix()
			readAt = &ts
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		resp = append(resp, directMessageResponse{
			SortSeq:          msg.SortSeq,
			ID:               msg.ID,
			ThreadID:         msg.ThreadID,
			FromUID:          fromUID,
			FromNcuid:        fromNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
			DeliveredAt:      deliveredAt,
			ReadAt:           readAt,
		})
	}

	writeJSON(w, http.StatusOK, directMessagesResponse{
		Messages: resp, EffectiveOffset: offset, HasMore: hasMore,
		NextCreatedAt: nextCreatedAt, NextID: nextID,
	})
}

func (a *API) handleDirectMessagesSearch(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	withUID := r.URL.Query().Get("with_uid")
	withNcuid := r.URL.Query().Get("with_ncuid")
	keyword := strings.TrimSpace(r.URL.Query().Get("q"))
	if keyword == "" {
		writeJSON(w, http.StatusOK, directMessagesResponse{Messages: []directMessageResponse{}})
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
	currentUser, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	targetUser, err := a.resolveUserIdentity(ctx, withUID, withNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}

	areFriends, err := a.friends.AreFriends(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if !areFriends {
		writeError(w, http.StatusForbidden, "not_friends", "not friends")
		return
	}

	threadID, err := a.direct.GetThreadID(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		if err == data.ErrNotFound {
			writeJSON(w, http.StatusOK, directMessagesResponse{Messages: []directMessageResponse{}})
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	msgs, err := a.direct.SearchMessagesWithOffset(ctx, threadID, keyword, kind, limit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	resp := make([]directMessageResponse, 0, len(msgs))
	for _, msg := range msgs {
		fromUID := targetUser.UID
		fromNcuid := targetUser.Ncuid
		if msg.SenderID == currentUser.ID {
			fromUID = currentUser.UID
			fromNcuid = currentUser.Ncuid
		}
		var deliveredAt *int64
		if msg.DeliveredAt != nil {
			ts := msg.DeliveredAt.Unix()
			deliveredAt = &ts
		}
		var readAt *int64
		if msg.ReadAt != nil {
			ts := msg.ReadAt.Unix()
			readAt = &ts
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		resp = append(resp, directMessageResponse{
			SortSeq:          msg.SortSeq,
			ID:               msg.ID,
			ThreadID:         msg.ThreadID,
			FromUID:          fromUID,
			FromNcuid:        fromNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
			DeliveredAt:      deliveredAt,
			ReadAt:           readAt,
		})
	}

	writeJSON(w, http.StatusOK, directMessagesResponse{Messages: resp})
}

func (a *API) handleDirectUnread(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req directUnreadRequest
	_ = decodeJSON(w, r, &req)
	limit := req.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	offset := req.Offset
	if offset < 0 {
		offset = 0
	}
	if offset >= unreadBootstrapMaxMessages {
		writeJSON(w, http.StatusOK, directUnreadResponse{
			Messages: []directUnreadMessageResponse{}, HasMore: false})
		return
	}
	if remaining := unreadBootstrapMaxMessages - offset; limit > remaining {
		limit = remaining
	}
	fetchLimit := limit
	if limit < 200 {
		fetchLimit = limit + 1
	}
	unread, err := a.direct.ListUnreadByUserOffset(ctx, claims.Subject, fetchLimit, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	_ = a.direct.MarkDeliveredByUser(ctx, claims.Subject)

	hasMore := len(unread) > limit
	if hasMore {
		unread = unread[:limit]
	} else if limit == 200 && len(unread) == limit {
		hasMore = true
	}
	if offset+len(unread) >= unreadBootstrapMaxMessages {
		hasMore = false
	}
	resp := make([]directUnreadMessageResponse, 0, len(unread))
	for _, msg := range unread {
		var deliveredAt *int64
		if msg.DeliveredAt != nil {
			ts := msg.DeliveredAt.Unix()
			deliveredAt = &ts
		}
		var readAt *int64
		if msg.ReadAt != nil {
			ts := msg.ReadAt.Unix()
			readAt = &ts
		}
		var burnStartAt *int64
		if msg.BurnStartAt != nil {
			ts := msg.BurnStartAt.Unix()
			burnStartAt = &ts
		}
		msgType := msg.MsgType
		if msgType == "" {
			msgType = "text"
		}
		resp = append(resp, directUnreadMessageResponse{
			SortSeq:          msg.SortSeq,
			ID:               msg.ID,
			ThreadID:         msg.ThreadID,
			FromUID:          msg.SenderUID,
			FromNcuid:        msg.SenderNcuid,
			PeerUID:          msg.PeerUID,
			PeerNcuid:        msg.PeerNcuid,
			Body:             msg.Body,
			MsgType:          msgType,
			MediaURL:         msg.MediaURL,
			ThumbURL:         msg.ThumbURL,
			OriginalURL:      msg.OriginalURL,
			DurationMS:       msg.DurationMS,
			BurnAfterSeconds: msg.BurnAfterSeconds,
			BurnStartAt:      burnStartAt,
			CreatedAt:        msg.Created.Unix(),
			DeliveredAt:      deliveredAt,
			ReadAt:           readAt,
		})
	}

	writeJSON(w, http.StatusOK, directUnreadResponse{Messages: resp, HasMore: hasMore})
}

func (a *API) handleDirectRead(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}

	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	var req directReadRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	currentUser, err := a.users.GetByID(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	targetUser, err := a.resolveUserIdentity(ctx, req.WithUID, req.WithNcuid)
	if err != nil {
		writeUserIdentityError(w, err)
		return
	}

	threadID, err := a.direct.GetThreadID(ctx, currentUser.ID, targetUser.ID)
	if err != nil {
		if err == data.ErrNotFound {
			writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	if err := a.direct.MarkRead(ctx, threadID, currentUser.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})

	env := wsEnvelope{
		Type: "direct_read",
		Data: map[string]interface{}{
			"thread_id":    threadID,
			"reader_uid":   currentUser.UID,
			"reader_ncuid": currentUser.Ncuid,
			"read_at":      time.Now().Unix(),
		},
	}
	if payload, err := json.Marshal(env); err == nil {
		a.wsHub.BroadcastToUser(targetUser.ID, payload)
	}

	// 账号级事件流：DIRECT_READ（已读游标，2 秒节流合并，避免高频写事件表）。
	if a.events != nil {
		now := time.Now().Unix()
		throttleKey := "read:" + currentUser.ID + ":" + targetUser.ID
		last, ok := a.readThrottle[throttleKey]
		if !ok || now-last >= 2 {
			a.readThrottle[throttleKey] = now
			readPayload := map[string]interface{}{
				"peer_uid":              currentUser.UID,
				"read_up_to_message_id": "",
				"read_at":               now,
			}
			if _, err := a.events.Emit(context.Background(), targetUser.ID, "DIRECT_READ", threadID, readPayload); err != nil {
				chatLogf("emit direct_read failed: %v", err)
			}
		}
	}
}

func (a *API) handleDirectMessageDelete(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	// 从URL路径中提取消息ID
	messageID := strings.TrimPrefix(r.URL.Path, "/v1/direct/messages/")
	if messageID == "" || messageID == r.URL.Path {
		writeError(w, http.StatusBadRequest, "invalid_message_id", "invalid message id")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	msg, err := a.direct.GetMessageByID(ctx, messageID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	if msg.SenderID != claims.Subject {
		writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
		return
	}
	thread, err := a.direct.GetThreadByID(ctx, msg.ThreadID)
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "thread_not_found", "thread not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	sender, err := a.users.GetByID(ctx, msg.SenderID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	// 删除消息（只能删除自己发送的消息）
	if err := a.direct.DeleteMessage(ctx, messageID, claims.Subject); err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "message_not_found", "message not found or not yours")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})

	peerID := thread.UserAID
	if peerID == msg.SenderID {
		peerID = thread.UserBID
	}
	env := wsEnvelope{
		Type: "direct_recall",
		Data: map[string]interface{}{
			"message_id": msg.ID,
			"thread_id":  msg.ThreadID,
			"from_uid":   sender.UID,
		},
	}
	if payload, err := json.Marshal(env); err == nil {
		a.wsHub.BroadcastToUser(msg.SenderID, payload)
		a.wsHub.BroadcastToUser(peerID, payload)
	}
	// 账号级事件流：对端写入 DIRECT_MESSAGE_RECALL。
	if a.events != nil {
		recallPayload := map[string]interface{}{
			"message_id":       msg.ID,
			"thread_peer_uid":  peerID,
			"recall_edit_type": "recall",
			"recalled_at":      time.Now().Unix(),
		}
		if _, err := a.events.Emit(context.Background(), peerID, "DIRECT_MESSAGE_RECALL", msg.ID, recallPayload); err != nil {
			chatLogf("emit direct_message_recall failed: %v", err)
		}
	}
}

func isValidMessage(body string) bool {
	// 按字符数（rune）统计：中文等多字节字符不应被字节数误伤。
	// 限制 2000 字符（与客户端输入框一致）。
	runes := utf8.RuneCountInString(body)
	if runes < 1 || runes > 2000 {
		return false
	}
	return true
}

func placeholderBody(msgType string) string {
	switch msgType {
	case "image":
		return "[图片]"
	case "voice":
		return "[语音]"
	case "video":
		return "[视频]"
	case "resource":
		return "[资源]"
	default:
		return ""
	}
}

func parseLimit(raw string) int {
	if raw == "" {
		return 50
	}
	val, err := strconv.Atoi(raw)
	if err != nil {
		return 50
	}
	if val < 1 {
		return 1
	}
	if val > 200 {
		return 200
	}
	return val
}

func parseOffset(raw string) int {
	if raw == "" {
		return 0
	}
	val, err := strconv.Atoi(raw)
	if err != nil {
		return 0
	}
	if val < 0 {
		return 0
	}
	return val
}

func parseBefore(raw string) time.Time {
	if raw == "" {
		return time.Time{}
	}
	val, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return time.Time{}
	}
	return time.Unix(val, 0)
}

func (a *API) handleDirectBurnOpen(w http.ResponseWriter, r *http.Request) {
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

	if err := a.direct.MarkBurnOpened(ctx, req.MessageID, claims.Subject); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

// wsEnvelope keeps the WebSocket payload format consistent.
type wsEnvelope struct {
	Type string      `json:"type"`
	Data interface{} `json:"data"`
}
