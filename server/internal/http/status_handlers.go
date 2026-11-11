package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"runtime"
	"sort"
	"strings"
	"time"
)

type systemStatusJSON struct {
	UptimeStr   string          `json:"uptime_str"`
	UptimeSec   int64           `json:"uptime_sec"`
	Goroutines  int             `json:"goroutines"`
	HeapAllocMB float64         `json:"heap_alloc_mb"`
	HeapSysMB   float64         `json:"heap_sys_mb"`
	NumGC       uint32          `json:"num_gc"`
	TotalReqs   int64           `json:"total_reqs"`
	TotalErrors int64           `json:"total_errors"`
	AvgTimeMs   float64         `json:"avg_time_ms"`
	QPS         float64         `json:"qps"`
	Routes      []RouteStatItem `json:"routes"`
}

func (a *API) handleStatusPage(w http.ResponseWriter, r *http.Request) {
	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)

	globalMetrics.mu.RLock()
	total := globalMetrics.totalRequests
	errors := globalMetrics.totalErrors
	avgMs := 0.0
	if total > 0 {
		avgMs = globalMetrics.totalTimeMs / float64(total)
	}
	qps := float64(len(globalMetrics.recentReqTimes)) / 5.0
	globalMetrics.mu.RUnlock()

	uptime := time.Since(serverStartTime)
	uptimeStr := formatUptimeDuration(uptime)

	data := statusPageData{
		UptimeStr:   uptimeStr,
		Goroutines:  runtime.NumGoroutine(),
		HeapAllocMB: float64(mem.HeapAlloc) / 1024 / 1024,
		HeapSysMB:   float64(mem.HeapSys) / 1024 / 1024,
		NumGC:       mem.NumGC,
		TotalReqs:   total,
		TotalErrors: errors,
		AvgTimeMs:   avgMs,
		QPS:         qps,
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = statusTpl.Execute(w, data)
}

func (a *API) handleStatusDataAPI(w http.ResponseWriter, r *http.Request) {
	if a != nil && a.systemStatusLimiter != nil {
		ip := clientIP(r)
		if !a.systemStatusLimiter.Allow(ip) {
			writeError(w, http.StatusTooManyRequests, "too_many_requests", "rate limit exceeded: 1 request per 3 seconds")
			return
		}
	}

	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)

	globalMetrics.mu.RLock()
	total := globalMetrics.totalRequests
	errors := globalMetrics.totalErrors
	avgMs := 0.0
	if total > 0 {
		avgMs = globalMetrics.totalTimeMs / float64(total)
	}
	qps := float64(len(globalMetrics.recentReqTimes)) / 5.0

	var routeItems []RouteStatItem
	for k, stat := range globalMetrics.routes {
		parts := splitMethodAndPath(k)
		path := parts[1]
		if isAdminRoute(path) {
			continue
		}
		pct := 0.0
		if total > 0 {
			pct = float64(stat.Count) / float64(total) * 100
		}
		routeItems = append(routeItems, RouteStatItem{
			Method:     parts[0],
			Path:       path,
			Count:      stat.Count,
			ErrorCount: stat.ErrorCount,
			AvgTimeMs:  stat.AvgTimeMs,
			Percentage: pct,
		})
	}
	globalMetrics.mu.RUnlock()

	sort.Slice(routeItems, func(i, j int) bool {
		return routeItems[i].Count > routeItems[j].Count
	})

	uptime := time.Since(serverStartTime)

	resp := systemStatusJSON{
		UptimeStr:   formatUptimeDuration(uptime),
		UptimeSec:   int64(uptime.Seconds()),
		Goroutines:  runtime.NumGoroutine(),
		HeapAllocMB: float64(mem.HeapAlloc) / 1024 / 1024,
		HeapSysMB:   float64(mem.HeapSys) / 1024 / 1024,
		NumGC:       mem.NumGC,
		TotalReqs:   total,
		TotalErrors: errors,
		AvgTimeMs:   avgMs,
		QPS:         qps,
		Routes:      routeItems,
	}

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(resp)
}

func splitMethodAndPath(key string) [2]string {
	var res [2]string
	for i := 0; i < len(key); i++ {
		if key[i] == ' ' {
			res[0] = key[:i]
			res[1] = key[i+1:]
			return res
		}
	}
	res[0] = "ALL"
	res[1] = key
	return res
}

func formatUptimeDuration(d time.Duration) string {
	days := int(d.Hours()) / 24
	hours := int(d.Hours()) % 24
	mins := int(d.Minutes()) % 60
	secs := int(d.Seconds()) % 60

	if days > 0 {
		return fmt.Sprintf("%d天 %d小时 %d分", days, hours, mins)
	}
	if hours > 0 {
		return fmt.Sprintf("%d小时 %d分 %d秒", hours, mins, secs)
	}
	if mins > 0 {
		return fmt.Sprintf("%d分 %d秒", mins, secs)
	}
	return fmt.Sprintf("%d秒", secs)
}

func isAdminRoute(path string) bool {
	p := strings.ToLower(strings.TrimSpace(path))
	return strings.HasPrefix(p, "/admin") ||
		strings.HasPrefix(p, "/admins") ||
		strings.HasPrefix(p, "/v1/admin")
}
