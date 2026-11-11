package httpapi

import (
	"context"
	"errors"
	"net/http"
	"time"

	"metrochat/internal/data"
)

type dailyCheckInWallMessageResponse struct {
	ID           string       `json:"id"`
	CheckinDate  string       `json:"checkin_date"`
	MessageType  string       `json:"message_type"`
	ContentText  string       `json:"content_text"`
	ImageURL     string       `json:"image_url"`
	ThumbURL     string       `json:"thumb_url"`
	CreatedAt    time.Time    `json:"created_at"`
	LikeCount    int          `json:"like_count"`
	CommentCount int          `json:"comment_count"`
	LikedByMe    bool         `json:"liked_by_me"`
	User         userResponse `json:"user"`
}

type dailyCheckInWallCommentResponse struct {
	ID        string    `json:"id"`
	PostID    string    `json:"post_id"`
	Body      string    `json:"body"`
	CreatedAt time.Time `json:"created_at"`
	User      struct {
		UID         string `json:"uid"`
		Username    string `json:"username"`
		DisplayName string `json:"display_name"`
		UserTitle   string `json:"user_title"`
		AvatarURL   string `json:"avatar_url"`
	} `json:"user"`
}

type dailyCheckInWallLikeUserResponse struct {
	UID         string    `json:"uid"`
	Username    string    `json:"username"`
	DisplayName string    `json:"display_name"`
	UserTitle   string    `json:"user_title"`
	AvatarURL   string    `json:"avatar_url"`
	CreatedAt   time.Time `json:"created_at"`
}

func (a *API) toDailyCheckInWallMessages(items []data.CheckInWallMessage) []dailyCheckInWallMessageResponse {
	resp := make([]dailyCheckInWallMessageResponse, 0, len(items))
	for _, item := range items {
		itemCopy := item
		resp = append(resp, a.toDailyCheckInWallMessageResponse(&itemCopy))
	}
	return resp
}

func (a *API) toDailyCheckInWallMessage(item *data.CheckInWallMessage) interface{} {
	if item == nil {
		return nil
	}
	return a.toDailyCheckInWallMessageResponse(item)
}

func (a *API) toDailyCheckInWallMessageResponse(item *data.CheckInWallMessage) dailyCheckInWallMessageResponse {
	user := &data.User{ID: item.UserID, UID: item.UID, Username: item.Username, DisplayName: item.DisplayName,
		UserTitle: item.UserTitle, AvatarURL: item.AvatarURL, Signature: item.Signature,
		CoverURL: item.CoverURL, PresenceStatus: item.PresenceStatus}
	return dailyCheckInWallMessageResponse{ID: item.ID, CheckinDate: item.CheckinDate,
		MessageType: dailyCheckInWallMessageType(item), ContentText: item.ContentText,
		ImageURL: item.ImageURL, ThumbURL: item.ThumbURL, CreatedAt: item.CreatedAt,
		LikeCount: item.LikeCount, CommentCount: item.CommentCount, LikedByMe: item.LikedByMe,
		User: a.toUserResponse(user)}
}

func (a *API) toDailyCheckInWallCommentResponse(comment data.CheckInWallComment) dailyCheckInWallCommentResponse {
	resp := dailyCheckInWallCommentResponse{ID: comment.ID, PostID: comment.PostID, Body: comment.Body, CreatedAt: comment.CreatedAt}
	resp.User.UID = comment.UID
	resp.User.Username = comment.Username
	resp.User.DisplayName = comment.DisplayName
	resp.User.UserTitle = comment.UserTitle
	resp.User.AvatarURL = comment.AvatarURL
	return resp
}

func (a *API) fillCheckInWallEngagement(ctx context.Context, posts []*data.CheckInWallMessage, userID string) error {
	postIDs := make([]string, 0, len(posts))
	unique := make(map[string]*data.CheckInWallMessage)
	for _, post := range posts {
		if post == nil || post.ID == "" {
			continue
		}
		if _, ok := unique[post.ID]; ok {
			continue
		}
		unique[post.ID] = post
		postIDs = append(postIDs, post.ID)
	}
	if len(postIDs) == 0 {
		return nil
	}
	likes, err := a.checkinInteract.CountLikes(ctx, postIDs)
	if err != nil {
		return err
	}
	comments, err := a.checkinInteract.CountComments(ctx, postIDs)
	if err != nil {
		return err
	}
	likedBy, err := a.checkinInteract.LikedBy(ctx, postIDs, userID)
	if err != nil {
		return err
	}
	for _, postID := range postIDs {
		post := unique[postID]
		post.LikeCount = likes[postID]
		post.CommentCount = comments[postID]
		post.LikedByMe = likedBy[postID]
	}
	return nil
}

func (a *API) writeCheckInWallLookupError(w http.ResponseWriter, err error) {
	if errors.Is(err, data.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "post not found")
		return
	}
	writeError(w, http.StatusInternalServerError, "db_error", "internal error")
}

func dailyCheckInWallMessageType(item *data.CheckInWallMessage) string {
	if item != nil && item.ImageURL != "" {
		return "image"
	}
	return "text"
}
