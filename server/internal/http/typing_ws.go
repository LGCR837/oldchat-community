package httpapi

import (
	"context"
	"strings"
	"time"
)

func (a *API) resolveTypingUID(ctx context.Context, userID, fallbackUID string) string {
	uid := strings.ToUpper(strings.TrimSpace(fallbackUID))
	userID = strings.TrimSpace(userID)
	if userID == "" {
		return uid
	}
	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	user, err := a.users.GetByID(cctx, userID)
	if err != nil || user == nil {
		return uid
	}
	resolved := strings.ToUpper(strings.TrimSpace(user.UID))
	if resolved != "" {
		return resolved
	}
	return uid
}

func (a *API) resolveTypingAvatarURL(ctx context.Context, userID string) string {
	userID = strings.TrimSpace(userID)
	if userID == "" {
		return ""
	}
	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	user, err := a.users.GetByID(cctx, userID)
	if err != nil || user == nil {
		return ""
	}
	return strings.TrimSpace(user.AvatarURL)
}
