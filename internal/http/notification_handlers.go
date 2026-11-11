package httpapi

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"metrochat/internal/data"
)

func (a *API) sendSystemNotification(ctx context.Context, targetUserID, title, body string, important bool) error {
	if a == nil || a.notifications == nil {
		return nil
	}
	body = strings.TrimSpace(body)
	if body == "" {
		return nil
	}
	title = strings.TrimSpace(title)
	if title == "" {
		title = "系统通知"
	}
	targetUserID = strings.TrimSpace(targetUserID)

	n := &data.SystemNotification{
		ID:           data.NewID(),
		TargetUserID: targetUserID,
		Title:        title,
		Body:         body,
		Important:    important,
	}

	if err := a.notifications.Create(ctx, n); err != nil {
		log.Printf("system notification create failed: %v", err)
		return err
	}

	if a.wsHub == nil {
		return nil
	}

	env := wsEnvelope{
		Type: "system_notification",
		Data: map[string]interface{}{
			"id":             n.ID,
			"target_user_id": n.TargetUserID,
			"title":          n.Title,
			"body":           n.Body,
			"important":      n.Important,
			"created_at":     time.Now().UnixMilli(),
		},
	}
	if msg, err := json.Marshal(env); err == nil {
		if n.TargetUserID != "" {
			a.wsHub.BroadcastToUser(n.TargetUserID, msg)
		} else {
			a.wsHub.Broadcast(msg)
		}
	} else {
		log.Printf("system notification marshal failed: %v", err)
	}
	return nil
}

func (a *API) handleNotificationList(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= 100 {
			limit = n
		}
	}

	var before time.Time
	if v := r.URL.Query().Get("before"); v != "" {
		if ts, err := strconv.ParseInt(v, 10, 64); err == nil {
			before = time.UnixMilli(ts)
		}
	}

	var userID string
	if claims, ok := claimsFromContext(r.Context()); ok && claims != nil {
		userID = claims.Subject
	}

	notifications, err := a.notifications.ListForUser(ctx, userID, limit, before)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "database_error", "failed to list notifications")
		return
	}

	type item struct {
		ID           string `json:"id"`
		TargetUserID string `json:"target_user_id,omitempty"`
		Title        string `json:"title"`
		Body         string `json:"body"`
		Important    bool   `json:"important"`
		CreatedAt    int64  `json:"created_at"`
	}

	items := make([]item, len(notifications))
	for i, n := range notifications {
		items[i] = item{
			ID:           n.ID,
			TargetUserID: n.TargetUserID,
			Title:        n.Title,
			Body:         n.Body,
			Important:    n.Important,
			CreatedAt:    n.CreatedAt.UnixMilli(),
		}
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"notifications": items,
	})
}

func (a *API) handleAdminNotificationSend(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}

	targetUser := strings.TrimSpace(r.FormValue("target_user"))
	title := strings.TrimSpace(r.FormValue("title"))
	body := strings.TrimSpace(r.FormValue("body"))
	important := r.FormValue("important") == "1" || r.FormValue("important") == "true"

	if body == "" {
		http.Redirect(w, r, "/admins?section=notifications#notifications", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	var targetUserID string
	if targetUser != "" {
		// 优先查找 UID、用户名/邮箱、NCUID、内部ID
		if u, err := a.users.GetByUID(ctx, targetUser); err == nil && u != nil {
			targetUserID = u.ID
		} else if u, err := a.users.GetByEmailOrUsername(ctx, targetUser); err == nil && u != nil {
			targetUserID = u.ID
		} else if u, err := a.users.GetByNcuid(ctx, targetUser); err == nil && u != nil {
			targetUserID = u.ID
		} else if u, err := a.users.GetByID(ctx, targetUser); err == nil && u != nil {
			targetUserID = u.ID
		} else {
			log.Printf("admin notification send: target user not found: %s", targetUser)
			http.Redirect(w, r, "/admins?section=notifications#notifications", http.StatusSeeOther)
			return
		}
	}

	_ = a.sendSystemNotification(ctx, targetUserID, title, body, important)
	http.Redirect(w, r, "/admins?section=notifications#notifications", http.StatusSeeOther)
}

func (a *API) handleAdminNotificationDelete(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}

	id := strings.TrimSpace(r.FormValue("id"))
	if id == "" {
		http.Redirect(w, r, "/admins?section=notifications#notifications", http.StatusSeeOther)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()

	_ = a.notifications.Delete(ctx, id)
	http.Redirect(w, r, "/admins?section=notifications#notifications", http.StatusSeeOther)
}
