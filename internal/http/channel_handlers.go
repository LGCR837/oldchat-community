package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"metrochat/internal/data"
)

type channelIDRequest struct {
	ChannelID string `json:"channel_id"`
}

type channelReadRequest struct {
	ChannelID   string `json:"channel_id"`
	ReadPostSeq int64  `json:"read_post_seq"`
}

func (a *API) handleChannelPostSend(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req channelAPIPostRequest
	if decodeJSON(w, r, &req) != nil {
		return
	}
	canPost, err := a.channels.CanPost(r.Context(), strings.TrimSpace(req.ChannelID), claims.Subject)
	if err != nil || !canPost {
		writeError(w, http.StatusForbidden, "cannot_post", "no channel post permission")
		return
	}
	req.MsgType = strings.ToLower(strings.TrimSpace(req.MsgType))
	if req.MsgType == "" {
		req.MsgType = "text"
	}
	allowed := req.MsgType == "text" || req.MsgType == "image" || req.MsgType == "voice" || req.MsgType == "video" || req.MsgType == "resource"
	if req.MediaURL != "" && !a.validChannelMediaRef(req.MediaURL) {
		writeError(w, http.StatusBadRequest, "invalid_media", "channel media must be uploaded first")
		return
	}
	if req.MsgType == "resource" && req.MediaURL == "" {
		writeError(w, http.StatusBadRequest, "invalid_post", "file post requires media_url")
		return
	}
	if !allowed || (strings.TrimSpace(req.Body) == "" && strings.TrimSpace(req.MediaURL) == "") {
		writeError(w, http.StatusBadRequest, "invalid_post", "invalid post")
		return
	}
	postID, _ := newPublicID("CP-", 14)
	post := &data.ChannelPost{ID: postID, ChannelID: req.ChannelID, AuthorID: claims.Subject,
		ReplyToPostID: strings.TrimSpace(req.ReplyToPostID), Body: req.Body, MsgType: req.MsgType,
		MediaURL: req.MediaURL, ThumbURL: req.ThumbURL}
	postSeq, eventSeq, err := a.channels.CreatePost(r.Context(), post)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "post_failed", "post failed")
		return
	}
	a.broadcastChannelUpdate(req.ChannelID, postSeq, eventSeq, "post_new")
	writeChannelJSON(w, map[string]interface{}{"ok": true, "post_id": postID, "post_seq": postSeq, "event_seq": eventSeq})
}

func (a *API) handleChannelDiscover(w http.ResponseWriter, r *http.Request) {
	if _, ok := claimsFromContext(r.Context()); !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	query := strings.TrimSpace(r.URL.Query().Get("q"))
	limit := 50
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 100 {
			writeError(w, http.StatusBadRequest, "invalid_limit", "limit must be 1-100")
			return
		}
		limit = parsed
	}
	channels, err := a.channels.SearchPublic(r.Context(), query, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "discover_failed", "discover failed")
		return
	}
	for i := range channels {
		channels[i].AvatarURL = a.signedChannelMediaURL(channels[i].AvatarURL)
	}
	writeChannelJSON(w, map[string]interface{}{"channels": channels})
}

func (a *API) handleChannelSubscribe(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req channelIDRequest
	if decodeJSON(w, r, &req) != nil {
		return
	}
	req.ChannelID = strings.TrimSpace(req.ChannelID)
	channel, err := a.channels.Get(r.Context(), req.ChannelID)
	if err != nil {
		writeError(w, http.StatusNotFound, "channel_not_found", "channel not found")
		return
	}
	if channel.Visibility != "public" && channel.OwnerID != claims.Subject {
		writeError(w, http.StatusForbidden, "invite_required", "private channel requires invite")
		return
	}
	inserted, err := a.channels.Subscribe(r.Context(), req.ChannelID, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "subscribe_failed", "subscribe failed")
		return
	}
	state, err := a.channels.State(r.Context(), req.ChannelID, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "state_failed", "state failed")
		return
	}
	state.AvatarURL = a.signedChannelMediaURL(state.AvatarURL)
	writeChannelJSON(w, map[string]interface{}{"ok": true, "subscribed": inserted, "state": state})
}

func (a *API) handleChannelUnsubscribe(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req channelIDRequest
	if decodeJSON(w, r, &req) != nil {
		return
	}
	removed, err := a.channels.Unsubscribe(r.Context(), strings.TrimSpace(req.ChannelID), claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "unsubscribe_failed", "unsubscribe failed")
		return
	}
	if !removed {
		writeError(w, http.StatusForbidden, "owner_cannot_leave", "owner cannot leave channel")
		return
	}
	writeChannelJSON(w, map[string]interface{}{"ok": true})
}

func (a *API) handleChannelState(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	channelID := strings.TrimSpace(r.URL.Query().Get("channel_id"))
	state, err := a.channels.State(r.Context(), channelID, claims.Subject)
	if err != nil {
		if data.IsNotFound(err) {
			writeError(w, http.StatusForbidden, "not_subscribed", "not subscribed")
			return
		}
		writeError(w, http.StatusInternalServerError, "state_failed", "state failed")
		return
	}
	state.AvatarURL = a.signedChannelMediaURL(state.AvatarURL)
	writeChannelJSON(w, state)
}

func (a *API) handleChannelStates(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	states, err := a.channels.ListStates(r.Context(), claims.Subject, 500)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "states_failed", "states failed")
		return
	}
	for i := range states {
		states[i].AvatarURL = a.signedChannelMediaURL(states[i].AvatarURL)
	}
	writeChannelJSON(w, map[string]interface{}{"channels": states})
}

var allowedChannelReactions = map[string]bool{
	"👍": true, "❤️": true, "🔥": true, "😂": true, "😢": true, "👎": true,
	"👏": true, "🎉": true,
}

func (a *API) handleChannelReactionToggle(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req struct {
		ChannelID string `json:"channel_id"`
		PostID    string `json:"post_id"`
		Emoji     string `json:"emoji"`
	}
	if decodeJSON(w, r, &req) != nil {
		return
	}
	if !allowedChannelReactions[req.Emoji] {
		writeError(w, http.StatusBadRequest, "emoji_not_allowed", "emoji not allowed")
		return
	}
	counts, eventSeq, err := a.channels.ToggleReaction(r.Context(), strings.TrimSpace(req.ChannelID), strings.TrimSpace(req.PostID), claims.Subject, req.Emoji)
	if err != nil {
		writeError(w, http.StatusForbidden, "reaction_denied", "reaction denied")
		return
	}
	a.broadcastChannelUpdate(req.ChannelID, 0, eventSeq, "reaction_changed")
	writeChannelJSON(w, map[string]interface{}{"ok": true, "reactions": counts, "event_seq": eventSeq})
}

func (a *API) handleChannelEventsAfter(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	channelID := strings.TrimSpace(r.URL.Query().Get("channel_id"))
	if _, err := a.channels.State(r.Context(), channelID, claims.Subject); err != nil {
		channel, getErr := a.channels.Get(r.Context(), channelID)
		if getErr != nil || channel.Visibility != "public" {
			writeError(w, http.StatusForbidden, "not_subscribed", "not subscribed")
			return
		}
	}
	after, _ := strconv.ParseInt(r.URL.Query().Get("after_event_seq"), 10, 64)
	limit := 200
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 500 {
			writeError(w, http.StatusBadRequest, "invalid_limit", "limit must be 1-500")
			return
		}
		limit = parsed
	}
	events, serverSeq, err := a.channels.ListEventsAfter(r.Context(), channelID, after, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "events_failed", "events failed")
		return
	}
	next := after
	if len(events) > 0 {
		next = events[len(events)-1].EventSeq
	}
	writeChannelJSON(w, map[string]interface{}{
		"events": events, "next_event_seq": next, "server_event_seq": serverSeq, "has_more": next < serverSeq,
	})
}

func (a *API) handleChannelPostsAfter(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	channelID := strings.TrimSpace(r.URL.Query().Get("channel_id"))
	if _, err := a.channels.State(r.Context(), channelID, claims.Subject); err != nil {
		channel, getErr := a.channels.Get(r.Context(), channelID)
		if getErr != nil || channel.Visibility != "public" {
			writeError(w, http.StatusForbidden, "not_subscribed", "not subscribed")
			return
		}
	}
	after, _ := strconv.ParseInt(r.URL.Query().Get("after_seq"), 10, 64)
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 200 {
			writeError(w, http.StatusBadRequest, "invalid_limit", "limit must be 1-200")
			return
		}
		limit = parsed
	}
	posts, serverSeq, err := a.channels.ListPostsAfter(r.Context(), channelID, after, limit)
	for i := range posts {
		posts[i].MediaURL = a.signedChannelMediaURL(posts[i].MediaURL)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "posts_failed", "posts failed")
		return
	}
	next := after
	if len(posts) > 0 {
		next = posts[len(posts)-1].PostSeq
	}
	if len(posts) == 0 && serverSeq > after {
		next = serverSeq
	}
	reactions, _ := a.channels.ReactionSnapshots(r.Context(), channelID, claims.Subject)
	mediaRefs, _ := a.channels.RecentMediaRefs(r.Context(), channelID)
	mediaURLs := make(map[string]string, len(mediaRefs))
	for postID, ref := range mediaRefs {
		mediaURLs[postID] = a.signedChannelMediaURL(ref)
	}
	writeChannelJSON(w, map[string]interface{}{
		"posts": posts, "reactions": reactions, "media_urls": mediaURLs,
		"next_post_seq": next, "server_post_seq": serverSeq, "has_more": next < serverSeq,
	})
}

func (a *API) handleChannelNotifications(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req struct {
		ChannelID         string `json:"channel_id"`
		NotificationLevel string `json:"notification_level"`
	}
	if decodeJSON(w, r, &req) != nil {
		return
	}
	if req.NotificationLevel != "all" && req.NotificationLevel != "mentions" && req.NotificationLevel != "none" {
		writeError(w, http.StatusBadRequest, "invalid_level", "invalid notification level")
		return
	}
	if err := a.channels.SetNotificationLevel(r.Context(), strings.TrimSpace(req.ChannelID), claims.Subject, req.NotificationLevel); err != nil {
		writeError(w, http.StatusForbidden, "not_subscribed", "not subscribed")
		return
	}
	writeChannelJSON(w, map[string]interface{}{"ok": true, "notification_level": req.NotificationLevel})
}

func (a *API) handleChannelRead(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req channelReadRequest
	if decodeJSON(w, r, &req) != nil {
		return
	}
	if req.ReadPostSeq < 0 {
		writeError(w, http.StatusBadRequest, "invalid_seq", "invalid read seq")
		return
	}
	err := a.channels.MarkRead(r.Context(), strings.TrimSpace(req.ChannelID), claims.Subject, req.ReadPostSeq)
	if errors.Is(err, data.ErrChannelReadSeqAhead) {
		writeError(w, http.StatusBadRequest, "read_seq_ahead", "read seq ahead")
		return
	}
	if errors.Is(err, data.ErrChannelNotSubscribed) {
		writeError(w, http.StatusForbidden, "not_subscribed", "not subscribed")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "read_failed", "read failed")
		return
	}
	// 观看计数：用户首次读到的帖子递增观看次数（水位去重，失败不阻塞已读流程）。
	_ = a.channels.IncrementViewsOnRead(r.Context(), strings.TrimSpace(req.ChannelID), claims.Subject, req.ReadPostSeq)
	state, _ := a.channels.State(r.Context(), req.ChannelID, claims.Subject)
	writeChannelJSON(w, map[string]interface{}{"ok": true, "state": state})
}

func writeChannelJSON(w http.ResponseWriter, value interface{}) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(value)
}
