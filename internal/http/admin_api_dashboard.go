package httpapi

import (
	"context"
	"net/http"
	"runtime"
	"time"
)

type adminDashboardStats struct {
	TotalUsers        int    `json:"total_users"`
	OnlineUsers       int    `json:"online_users"`
	OnlineConnections int    `json:"online_connections"`
	BannedUsers       int    `json:"banned_users"`
	PendingReports    int    `json:"pending_reports"`
	ServerVersion     string `json:"server_version"`
	Uptime            string `json:"uptime"`
	Goroutines        int    `json:"goroutines"`
}

var serverStartTime = time.Now()

func (a *API) handleAdminAPIStats(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()

	totalUsers, _ := a.users.Count(ctx)
	bannedUsers, _ := a.devices.CountBannedUsers(ctx)
	userReports, _ := a.reportStore.CountUserReports(ctx)
	pendingAppeals, _ := a.banAppeals.CountPending(ctx)

	onlineUsers := 0
	onlineConns := 0
	if a.wsHub != nil {
		onlineUsers, onlineConns = a.wsHub.OnlineCounts()
	}

	uptimeDur := time.Since(serverStartTime).Round(time.Second)

	res := adminDashboardStats{
		TotalUsers:        totalUsers,
		OnlineUsers:       onlineUsers,
		OnlineConnections: onlineConns,
		BannedUsers:       bannedUsers,
		PendingReports:    userReports + pendingAppeals,
		ServerVersion:     "OldChat Pro v2.0",
		Uptime:            uptimeDur.String(),
		Goroutines:        runtime.NumGoroutine(),
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": res,
	})
}
