package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"time"
)

type adminDeviceControlReq struct {
	DeviceID string `json:"device_id"`
	Reason   string `json:"reason"`
}

// handleAdminAPIKickUser POST /v1/admin/users/kick — 强制下线踢除
func (a *API) handleAdminAPIKickUser(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UID string `json:"uid"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	user, err := a.users.GetByUID(ctx, req.UID)
	if err != nil || user == nil {
		writeError(w, http.StatusNotFound, "user_not_found", "user not found")
		return
	}

	_, _ = a.users.IncrementTokenVersion(ctx, user.ID)
	// WebSocket 断开踢线
	a.wsHub.BroadcastToUser(user.ID, []byte(`{"type":"kicked","reason":"被管理员强制下线"}`))

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "用户已强制下线"})
}

// handleAdminAPIBanDevice POST /v1/admin/devices/ban — 封禁设备
func (a *API) handleAdminAPIBanDevice(w http.ResponseWriter, r *http.Request) {
	var req adminDeviceControlReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "device_id required")
		return
	}
	reason := req.Reason
	if reason == "" {
		reason = "管理员封禁设备"
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.devices.BanDevice(ctx, req.DeviceID, reason, 0); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to ban device")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "设备封禁成功"})
}

// handleAdminAPIUnbanDevice POST /v1/admin/devices/unban — 解封设备
func (a *API) handleAdminAPIUnbanDevice(w http.ResponseWriter, r *http.Request) {
	var req adminDeviceControlReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.DeviceID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "device_id required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.devices.UnbanDevice(ctx, req.DeviceID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to unban device")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "设备解封成功"})
}

// handleAdminAPIBannedDevices GET /v1/admin/devices/banned — 获取已封禁设备列表
func (a *API) handleAdminAPIBannedDevices(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	banned, err := a.devices.ListBannedDevices(ctx, 100)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list banned devices")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "devices": banned})
}
