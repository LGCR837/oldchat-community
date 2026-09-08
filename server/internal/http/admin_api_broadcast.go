package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type adminSendNotificationReq struct {
	Title     string `json:"title"`
	Content   string `json:"content"`
	TargetUID string `json:"target_uid"` // 留空为全服广播，指定 UID 为定向私信系统通知
	Important bool   `json:"important"`  // 是否高亮为重要紧急公告
}

type adminDeleteNotificationReq struct {
	ID string `json:"id"`
}

// handleAdminAPINotificationsList GET /v1/admin/notifications
func (a *API) handleAdminAPINotificationsList(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize := 50
	offset := (page - 1) * pageSize

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	list, err := a.notifications.ListPage(ctx, pageSize, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list notifications")
		return
	}
	total, _ := a.notifications.Count(ctx)

	writeJSON(w, http.StatusOK, map[string]any{
		"code":          200,
		"notifications": list,
		"total":         total,
		"page":          page,
		"page_size":     pageSize,
	})
}

// handleAdminAPINotificationsSend POST /v1/admin/notifications/send — 发布公告/系统通知
func (a *API) handleAdminAPINotificationsSend(w http.ResponseWriter, r *http.Request) {
	var req adminSendNotificationReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_req", "json parse error")
		return
	}
	req.Title = strings.TrimSpace(req.Title)
	req.Content = strings.TrimSpace(req.Content)
	if req.Title == "" || req.Content == "" {
		writeError(w, http.StatusBadRequest, "missing_fields", "title and content required")
		return
	}

	targetUserID := ""
	targetUID := strings.TrimSpace(req.TargetUID)
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if targetUID != "" {
		user, err := a.users.GetByUID(ctx, targetUID)
		if err != nil || user == nil {
			writeError(w, http.StatusNotFound, "target_user_not_found", "target user not found by UID")
			return
		}
		targetUserID = user.ID
	}

	if err := a.sendSystemNotification(ctx, targetUserID, req.Title, req.Content, req.Important); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to publish notification: "+err.Error())
		return
	}

	msg := "全服广播公告已发布并实时推流至所有在线客户端"
	if targetUID != "" {
		msg = "定向系统通知已发送至用户 " + targetUID
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code":    200,
		"message": msg,
	})
}

// handleAdminAPINotificationsDelete POST /v1/admin/notifications/delete — 删除/下架广播
func (a *API) handleAdminAPINotificationsDelete(w http.ResponseWriter, r *http.Request) {
	var req adminDeleteNotificationReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.notifications.Delete(ctx, req.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to delete notification")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "通知已删除"})
}
