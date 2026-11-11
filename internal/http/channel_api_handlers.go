package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"time"

	"metrochat/internal/data"

	"github.com/aidarkhanov/nanoid"
)

type channelAPIPermit struct {
	ID        string     `db:"id"`
	UserID    string     `db:"user_id"`
	Scopes    string     `db:"scopes"`
	ExpiresAt *time.Time `db:"expires_at"`
}

type channelAPICreateRequest struct {
	Name        string `json:"name"`
	Handle      string `json:"handle"`
	AvatarURL   string `json:"avatar_url"`
	Description string `json:"description"`
	Visibility  string `json:"visibility"`
}

type channelAPIPostRequest struct {
	ChannelID     string `json:"channel_id"`
	Body          string `json:"body"`
	MsgType       string `json:"msg_type"`
	MediaURL      string `json:"media_url"`
	ThumbURL      string `json:"thumb_url"`
	ReplyToPostID string `json:"reply_to_post_id"`
}

var channelHandlePattern = regexp.MustCompile(`^[a-z0-9_]{5,32}$`)

const channelTokenTTL = 365 * 24 * time.Hour

func (a *API) handleChannelAPICreate(w http.ResponseWriter, r *http.Request) {
	permit, ok := a.requireChannelAPIPermit(w, r, "channel:create")
	if !ok {
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req channelAPICreateRequest
	if decodeJSON(w, r, &req) != nil {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	req.Handle = data.NormalizeChannelHandle(req.Handle)
	req.Visibility = strings.ToLower(strings.TrimSpace(req.Visibility))
	if req.Name == "" || len([]rune(req.Name)) > 64 {
		writeError(w, http.StatusBadRequest, "invalid_name", "channel name required, max 64")
		return
	}
	if req.Visibility != "public" && req.Visibility != "private" {
		writeError(w, http.StatusBadRequest, "invalid_visibility", "visibility must be public or private")
		return
	}
	if req.Visibility == "public" && !channelHandlePattern.MatchString(req.Handle) {
		writeError(w, http.StatusBadRequest, "invalid_handle", "public channel handle must be 5-32 lowercase characters")
		return
	}
	if req.Handle != "" && !channelHandlePattern.MatchString(req.Handle) {
		writeError(w, http.StatusBadRequest, "invalid_handle", "invalid channel handle")
		return
	}
	channelID, err := newPublicID("CH-", 10)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "id_failed", "failed to create channel id")
		return
	}
	channel := &data.Channel{
		ID: channelID, OwnerID: permit.UserID, Name: req.Name, Handle: req.Handle,
		AvatarURL: strings.TrimSpace(req.AvatarURL), Description: strings.TrimSpace(req.Description),
		Visibility: req.Visibility,
	}
	if err = a.channels.Create(r.Context(), channel); err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			writeError(w, http.StatusConflict, "handle_taken", "channel handle already taken")
			return
		}
		writeError(w, http.StatusInternalServerError, "create_failed", "channel create failed")
		return
	}
	_, _ = a.db.ExecContext(r.Context(), `UPDATE channel_api_permits SET last_used_at=CURRENT_TIMESTAMP WHERE id=$1`, permit.ID)
	created, _ := a.channels.Get(r.Context(), channelID)
	writeChannelJSON(w, map[string]interface{}{"ok": true, "channel": created})
}

func (a *API) handleChannelAPIPost(w http.ResponseWriter, r *http.Request) {
	permit, ok := a.requireChannelAPIPermit(w, r, "channel:post")
	if !ok {
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req channelAPIPostRequest
	if decodeJSON(w, r, &req) != nil {
		return
	}
	req.ChannelID = strings.TrimSpace(req.ChannelID)
	req.MsgType = strings.ToLower(strings.TrimSpace(req.MsgType))
	if req.MsgType == "" {
		req.MsgType = "text"
	}
	if req.MsgType != "text" && req.MsgType != "image" && req.MsgType != "video" && req.MsgType != "resource" {
		writeError(w, http.StatusBadRequest, "invalid_msg_type", "unsupported post type")
		return
	}
	if len([]byte(req.Body)) > 128*1024 {
		writeError(w, http.StatusBadRequest, "body_too_large", "post body too large")
		return
	}
	if strings.TrimSpace(req.Body) == "" && strings.TrimSpace(req.MediaURL) == "" {
		writeError(w, http.StatusBadRequest, "empty_post", "post is empty")
		return
	}
	canPost, err := a.channels.CanPost(r.Context(), req.ChannelID, permit.UserID)
	if err != nil || !canPost {
		writeError(w, http.StatusForbidden, "cannot_post", "publisher cannot post to this channel")
		return
	}
	postID, err := newPublicID("CP-", 14)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "id_failed", "failed to create post id")
		return
	}
	post := &data.ChannelPost{
		ID: postID, ChannelID: req.ChannelID, AuthorID: permit.UserID, Body: req.Body,
		MsgType: req.MsgType, MediaURL: strings.TrimSpace(req.MediaURL), ThumbURL: strings.TrimSpace(req.ThumbURL),
		ReplyToPostID: strings.TrimSpace(req.ReplyToPostID),
	}
	postSeq, eventSeq, err := a.channels.CreatePost(r.Context(), post)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "post_failed", "channel post failed")
		return
	}
	_, _ = a.db.ExecContext(r.Context(), `UPDATE channel_api_permits SET last_used_at=CURRENT_TIMESTAMP WHERE id=$1`, permit.ID)
	a.broadcastChannelUpdate(req.ChannelID, postSeq, eventSeq, "post_new")
	writeChannelJSON(w, map[string]interface{}{
		"ok": true, "post_id": postID, "post_seq": postSeq, "event_seq": eventSeq,
	})
}

func (a *API) handleChannelAPIEditPost(w http.ResponseWriter, r *http.Request) {
	permit, ok := a.requireChannelAPIPermit(w, r, "channel:edit")
	if !ok {
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req struct {
		ChannelID string `json:"channel_id"`
		PostID    string `json:"post_id"`
		Body      string `json:"body"`
	}
	if decodeJSON(w, r, &req) != nil {
		return
	}
	canPost, err := a.channels.CanPost(r.Context(), strings.TrimSpace(req.ChannelID), permit.UserID)
	if err != nil || !canPost {
		writeError(w, http.StatusForbidden, "cannot_edit", "publisher cannot edit this channel")
		return
	}
	if len([]byte(req.Body)) > 128*1024 {
		writeError(w, http.StatusBadRequest, "body_too_large", "post body too large")
		return
	}
	eventSeq, err := a.channels.EditPost(r.Context(), req.ChannelID, strings.TrimSpace(req.PostID), req.Body)
	if err != nil {
		writeError(w, http.StatusNotFound, "post_not_found", "post not found")
		return
	}
	a.broadcastChannelUpdate(req.ChannelID, 0, eventSeq, "post_edit")
	writeChannelJSON(w, map[string]interface{}{"ok": true, "event_seq": eventSeq})
}

func (a *API) handleChannelAPIDeletePost(w http.ResponseWriter, r *http.Request) {
	permit, ok := a.requireChannelAPIPermit(w, r, "channel:delete")
	if !ok {
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req struct {
		ChannelID string `json:"channel_id"`
		PostID    string `json:"post_id"`
	}
	if decodeJSON(w, r, &req) != nil {
		return
	}
	canPost, err := a.channels.CanPost(r.Context(), strings.TrimSpace(req.ChannelID), permit.UserID)
	if err != nil || !canPost {
		writeError(w, http.StatusForbidden, "cannot_delete", "publisher cannot delete from this channel")
		return
	}
	eventSeq, err := a.channels.DeletePost(r.Context(), req.ChannelID, strings.TrimSpace(req.PostID))
	if err != nil {
		writeError(w, http.StatusNotFound, "post_not_found", "post not found")
		return
	}
	a.broadcastChannelUpdate(req.ChannelID, 0, eventSeq, "post_delete")
	writeChannelJSON(w, map[string]interface{}{"ok": true, "event_seq": eventSeq})
}

func (a *API) broadcastChannelUpdate(channelID string, postSeq, eventSeq int64, hint string) {
	payload, err := json.Marshal(map[string]interface{}{
		"type": "channel_update", "data": map[string]interface{}{
			"channel_id": channelID, "post_seq": postSeq, "event_seq": eventSeq, "hint_type": hint,
		},
	})
	if err == nil {
		a.wsHub.BroadcastToChannel(channelID, payload)
	}
}

func (a *API) handleChannelAPIApply(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req struct {
		Reason string   `json:"reason"`
		Scopes []string `json:"scopes"`
	}
	if decodeJSON(w, r, &req) != nil {
		return
	}
	req.Reason = strings.TrimSpace(req.Reason)
	if req.Reason == "" || len([]rune(req.Reason)) > 200 {
		writeError(w, http.StatusBadRequest, "invalid_reason", "reason required, max 200")
		return
	}
	allowed := map[string]bool{
		"channel:create": true, "channel:post": true,
		"channel:edit": true, "channel:delete": true, "channel:manage": true,
	}
	scopes := make([]string, 0, len(req.Scopes))
	for _, scope := range req.Scopes {
		scope = strings.TrimSpace(scope)
		if allowed[scope] {
			scopes = append(scopes, scope)
		}
	}
	if len(scopes) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_scopes", "at least one channel scope required")
		return
	}
	var pending int
	_ = a.db.GetContext(r.Context(), &pending, `SELECT COUNT(1) FROM channel_api_permits WHERE user_id=$1 AND status='applied'`, claims.Subject)
	if pending > 0 {
		writeError(w, http.StatusConflict, "already_applied", "application already pending")
		return
	}
	_, err := a.db.ExecContext(r.Context(), `
INSERT INTO channel_api_permits(id,user_id,status,scopes,reason) VALUES($1,$2,'applied',$3,$4)`,
		nanoid.New(), claims.Subject, strings.Join(scopes, ","), req.Reason)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "apply_failed", "application failed")
		return
	}
	writeChannelJSON(w, map[string]interface{}{"ok": true, "status": "applied"})
}

func (a *API) handleChannelAPIStatus(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var row struct {
		ID        string     `db:"id" json:"id"`
		Status    string     `db:"status" json:"status"`
		Scopes    string     `db:"scopes" json:"scopes"`
		Reason    string     `db:"reason" json:"reason"`
		ExpiresAt *time.Time `db:"expires_at" json:"expires_at,omitempty"`
	}
	err := a.db.GetContext(r.Context(), &row, `SELECT id,status,scopes,reason,expires_at FROM channel_api_permits WHERE user_id=$1 ORDER BY created_at DESC LIMIT 1`, claims.Subject)
	if err != nil {
		writeChannelJSON(w, map[string]interface{}{"status": "none"})
		return
	}
	writeChannelJSON(w, row)
}

func (a *API) handleChannelAPIAdminList(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	rows := []struct {
		ID        string    `db:"id" json:"id"`
		UID       string    `db:"uid" json:"uid"`
		Username  string    `db:"username" json:"username"`
		Status    string    `db:"status" json:"status"`
		Scopes    string    `db:"scopes" json:"scopes"`
		Reason    string    `db:"reason" json:"reason"`
		CreatedAt time.Time `db:"created_at" json:"created_at"`
	}{}
	err := a.db.SelectContext(r.Context(), &rows, `
SELECT p.id,u.uid,u.username,p.status,p.scopes,p.reason,p.created_at
FROM channel_api_permits p JOIN users u ON u.id=p.user_id
ORDER BY p.created_at DESC LIMIT 100`)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "list_failed", "list failed")
		return
	}
	writeChannelJSON(w, map[string]interface{}{"applications": rows})
}

func (a *API) handleChannelAPIAdminReview(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req struct {
		ID           string `json:"id"`
		Action       string `json:"action"`
		ReviewReason string `json:"review_reason"`
	}
	if decodeJSON(w, r, &req) != nil {
		return
	}
	updated, err := a.reviewChannelAPIPermit(r.Context(), req.ID, req.Action, req.ReviewReason)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "review_failed", "review failed")
		return
	}
	if !updated {
		writeError(w, http.StatusConflict, "not_pending", "application is not pending")
		return
	}
	a.notifyPermitReviewEmail(r.Context(), "channel_api_permits", req.ID, req.Action, req.ReviewReason, "https://oc.mcl0.dpdns.org/channel-api/apply")
	writeChannelJSON(w, map[string]interface{}{"ok": true})
}

func (a *API) reviewChannelAPIPermit(ctx context.Context, id, action, reviewReason string) (bool, error) {
	action = strings.ToLower(strings.TrimSpace(action))
	if action != "approve" && action != "reject" {
		return false, nil
	}
	status := "approved"
	if action == "reject" {
		status = "rejected"
	}
	result, err := a.db.ExecContext(ctx, `UPDATE channel_api_permits SET status=$1,review_reason=$2,reviewed_at=CURRENT_TIMESTAMP WHERE id=$3 AND status='applied'`, status, strings.TrimSpace(reviewReason), strings.TrimSpace(id))
	if err != nil {
		return false, err
	}
	affected, _ := result.RowsAffected()
	return affected == 1, nil
}

func (a *API) requireChannelAPIPermit(w http.ResponseWriter, r *http.Request, scope string) (*channelAPIPermit, bool) {
	auth := strings.TrimSpace(r.Header.Get("Authorization"))
	if !strings.HasPrefix(auth, "Bearer ") {
		writeError(w, http.StatusUnauthorized, "missing_token", "missing publisher token")
		return nil, false
	}
	permit, err := a.authenticateChannelAPIToken(r.Context(), strings.TrimSpace(strings.TrimPrefix(auth, "Bearer ")))
	if err != nil || permit == nil {
		writeError(w, http.StatusUnauthorized, "invalid_token", "invalid or expired publisher token")
		return nil, false
	}
	if !hasChannelScope(permit.Scopes, scope) {
		writeError(w, http.StatusForbidden, "scope_denied", "publisher token scope denied")
		return nil, false
	}
	return permit, true
}

func (a *API) authenticateChannelAPIToken(ctx context.Context, raw string) (*channelAPIPermit, error) {
	sum := sha256.Sum256([]byte(raw))
	hash := hex.EncodeToString(sum[:])
	var permit channelAPIPermit
	err := a.db.GetContext(ctx, &permit, `
SELECT id,user_id,scopes,expires_at FROM channel_api_permits
WHERE token_hash=$1 AND status='approved'`, hash)
	if err != nil {
		return nil, err
	}
	if permit.ExpiresAt == nil || time.Now().After(*permit.ExpiresAt) {
		return nil, context.DeadlineExceeded
	}
	return &permit, nil
}

func hasChannelScope(scopes, required string) bool {
	for _, one := range strings.Split(scopes, ",") {
		if strings.TrimSpace(one) == required || strings.TrimSpace(one) == "channel:*" {
			return true
		}
	}
	return false
}
