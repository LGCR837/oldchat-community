package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"metrochat/internal/data"
)

type dailyCheckInWallPostRequest struct {
	ContentText string `json:"content_text"`
	ImageURL    string `json:"image_url"`
	ThumbURL    string `json:"thumb_url"`
}

type dailyCheckInWallToggleLikeRequest struct {
	PostID string `json:"post_id"`
}

type dailyCheckInWallCommentRequest struct {
	PostID string `json:"post_id"`
	Body   string `json:"body"`
}

func (a *API) handleMeCheckInWall(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	state, err := a.checkinWall.GetTodayState(ctx, claims.Subject, 10)
	if err != nil {
		if errors.Is(err, data.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	posts := make([]*data.CheckInWallMessage, 0, len(state.FeaturedMessages)+1)
	if state.MyPost != nil {
		posts = append(posts, state.MyPost)
	}
	for i := range state.FeaturedMessages {
		posts = append(posts, &state.FeaturedMessages[i])
	}
	if err = a.fillCheckInWallEngagement(ctx, posts, claims.Subject); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"checkin_date":      state.CheckinDate,
		"checkin_count":     state.CheckInCount,
		"checked_in":        state.CheckedIn,
		"already_posted":    state.AlreadyPosted,
		"my_post":           a.toDailyCheckInWallMessage(state.MyPost),
		"featured_messages": a.toDailyCheckInWallMessages(state.FeaturedMessages),
	})
}

func (a *API) handleMeCheckInWallPost(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req dailyCheckInWallPostRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid request body")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	post, err := a.checkinWall.CreateTodayPost(ctx, claims.Subject, req.ContentText, req.ImageURL, req.ThumbURL)
	if err != nil {
		switch {
		case errors.Is(err, data.ErrNotFound):
			writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		case errors.Is(err, data.ErrDailyCheckInRequired):
			writeError(w, http.StatusForbidden, "checkin_required", "please check in first")
		case errors.Is(err, data.ErrDailyCheckInPostExists):
			writeError(w, http.StatusConflict, "already_posted", "already posted today")
		case errors.Is(err, data.ErrInvalidDailyCheckInPost):
			writeError(w, http.StatusBadRequest, "invalid_post", "post must contain either text or image")
		default:
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		}
		return
	}
	writeJSON(w, http.StatusCreated, map[string]interface{}{"post": a.toDailyCheckInWallMessage(post)})
}

func (a *API) handleMeCheckInWallLike(w http.ResponseWriter, r *http.Request) {
	a.handleCheckInWallLikeToggle(w, r, true)
}

func (a *API) handleMeCheckInWallUnlike(w http.ResponseWriter, r *http.Request) {
	a.handleCheckInWallLikeToggle(w, r, false)
}

func (a *API) handleCheckInWallLikeToggle(w http.ResponseWriter, r *http.Request, liked bool) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req dailyCheckInWallToggleLikeRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid request body")
		return
	}
	postID := strings.TrimSpace(req.PostID)
	if postID == "" {
		writeError(w, http.StatusBadRequest, "invalid_post", "invalid post")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.checkinWall.GetByID(ctx, postID); err != nil {
		a.writeCheckInWallLookupError(w, err)
		return
	}
	if liked {
		err := a.checkinInteract.Like(ctx, postID, claims.Subject)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
	} else {
		err := a.checkinInteract.Unlike(ctx, postID, claims.Subject)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
	}
	likes, err := a.checkinInteract.CountLikes(ctx, []string{postID})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"post_id":     postID,
		"liked_by_me": liked,
		"like_count":  likes[postID],
	})
}

func (a *API) handleMeCheckInWallComment(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	var req dailyCheckInWallCommentRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid request body")
		return
	}
	postID := strings.TrimSpace(req.PostID)
	if postID == "" {
		writeError(w, http.StatusBadRequest, "invalid_post", "invalid post")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.checkinWall.GetByID(ctx, postID); err != nil {
		a.writeCheckInWallLookupError(w, err)
		return
	}
	comment := &data.CheckInWallComment{ID: data.NewID(), PostID: postID, UserID: claims.Subject, Body: req.Body}
	if err := a.checkinInteract.AddComment(ctx, comment); err != nil {
		if errors.Is(err, data.ErrInvalidDailyCheckInComment) {
			writeError(w, http.StatusBadRequest, "invalid_comment", "invalid comment")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	comments, err := a.checkinInteract.ListComments(ctx, postID, 1, time.Now().Add(time.Second))
	if err != nil || len(comments) == 0 {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	counts, err := a.checkinInteract.CountComments(ctx, []string{postID})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"comment":       a.toDailyCheckInWallCommentResponse(comments[0]),
		"comment_count": counts[postID],
	})
}

func (a *API) handleMeCheckInWallComments(w http.ResponseWriter, r *http.Request) {
	_, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	postID := strings.TrimSpace(r.URL.Query().Get("post_id"))
	if postID == "" {
		writeError(w, http.StatusBadRequest, "invalid_post", "invalid post")
		return
	}
	limit := parseLimit(r.URL.Query().Get("limit"))
	before := parseBefore(r.URL.Query().Get("before"))
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if _, err := a.checkinWall.GetByID(ctx, postID); err != nil {
		a.writeCheckInWallLookupError(w, err)
		return
	}
	comments, err := a.checkinInteract.ListComments(ctx, postID, limit, before)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	resp := make([]dailyCheckInWallCommentResponse, 0, len(comments))
	for _, comment := range comments {
		resp = append(resp, a.toDailyCheckInWallCommentResponse(comment))
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"comments": resp})
}

func (a *API) handleMeCheckInWallLikes(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	postID := strings.TrimSpace(r.URL.Query().Get("post_id"))
	if postID == "" {
		writeError(w, http.StatusBadRequest, "invalid_post", "invalid post")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	post, err := a.checkinWall.GetByID(ctx, postID)
	if err != nil {
		a.writeCheckInWallLookupError(w, err)
		return
	}
	if post.UserID != claims.Subject {
		writeError(w, http.StatusForbidden, "forbidden", "only author can view likes")
		return
	}
	likes, err := a.checkinInteract.ListLikes(ctx, postID, parseLimit(r.URL.Query().Get("limit")))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	resp := make([]dailyCheckInWallLikeUserResponse, 0, len(likes))
	for _, like := range likes {
		resp = append(resp, dailyCheckInWallLikeUserResponse{
			UID:         like.UID,
			Username:    like.Username,
			DisplayName: like.DisplayName,
			UserTitle:   like.UserTitle,
			AvatarURL:   like.AvatarURL,
			CreatedAt:   like.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"likes": resp})
}
