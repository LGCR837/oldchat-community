package httpapi

import (
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type RequestMetricLog struct {
	Time       string  `json:"time"`
	Method     string  `json:"method"`
	Path       string  `json:"path"`
	Status     int     `json:"status"`
	DurationMs float64 `json:"duration_ms"`
	IP         string  `json:"ip"`
	UserAgent  string  `json:"user_agent"`
}

type RouteStat struct {
	Count       int64   `json:"count"`
	ErrorCount  int64   `json:"error_count"`
	TotalTimeMs float64 `json:"total_time_ms"`
	AvgTimeMs   float64 `json:"avg_time_ms"`
}

type RouteStatItem struct {
	Path       string  `json:"path"`
	Method     string  `json:"method"`
	Count      int64   `json:"count"`
	ErrorCount int64   `json:"error_count"`
	AvgTimeMs  float64 `json:"avg_time_ms"`
	Percentage float64 `json:"percentage"`
}

type IPStatItem struct {
	IP       string `json:"ip"`
	Count    int64  `json:"count"`
	LastSeen string `json:"last_seen"`
}

type APIMetricsStore struct {
	mu             sync.RWMutex
	totalRequests  int64
	totalErrors    int64
	totalTimeMs    float64
	ringLogs       []RequestMetricLog
	ringCap        int
	ringIdx        int
	routes         map[string]*RouteStat
	ips            map[string]*IPStatItem
	recentReqTimes []time.Time
}

var globalMetrics = &APIMetricsStore{
	ringCap:  200,
	ringLogs: make([]RequestMetricLog, 0, 200),
	routes:   make(map[string]*RouteStat),
	ips:      make(map[string]*IPStatItem),
}

func (m *APIMetricsStore) Record(method, path string, status int, dur time.Duration, ip, ua string) {
	durMs := float64(dur.Nanoseconds()) / 1e6
	now := time.Now()
	nowStr := now.Format("15:04:05")
	key := method + " " + normalizeMetricPath(path)

	m.mu.Lock()
	defer m.mu.Unlock()

	m.totalRequests++
	m.totalTimeMs += durMs
	if status >= 400 {
		m.totalErrors++
	}

	entry := RequestMetricLog{
		Time:       nowStr,
		Method:     method,
		Path:       path,
		Status:     status,
		DurationMs: durMs,
		IP:         ip,
		UserAgent:  ua,
	}
	if len(m.ringLogs) < m.ringCap {
		m.ringLogs = append(m.ringLogs, entry)
	} else {
		m.ringLogs[m.ringIdx] = entry
		m.ringIdx = (m.ringIdx + 1) % m.ringCap
	}

	rs, ok := m.routes[key]
	if !ok {
		rs = &RouteStat{}
		m.routes[key] = rs
	}
	rs.Count++
	rs.TotalTimeMs += durMs
	if status >= 400 {
		rs.ErrorCount++
	}
	if rs.Count > 0 {
		rs.AvgTimeMs = rs.TotalTimeMs / float64(rs.Count)
	}

	if ip != "" && ip != "127.0.0.1" {
		ipItem, ok := m.ips[ip]
		if !ok {
			ipItem = &IPStatItem{IP: ip}
			m.ips[ip] = ipItem
		}
		ipItem.Count++
		ipItem.LastSeen = nowStr
	}

	m.recentReqTimes = append(m.recentReqTimes, now)
	cutoff := now.Add(-5 * time.Second)
	idx := 0
	for idx < len(m.recentReqTimes) && m.recentReqTimes[idx].Before(cutoff) {
		idx++
	}
	if idx > 0 {
		m.recentReqTimes = m.recentReqTimes[idx:]
	}
}

func normalizeMetricPath(p string) string {
	if strings.HasPrefix(p, "/v1/music/cover/") {
		return "/v1/music/cover/*"
	}
	if strings.HasPrefix(p, "/uploads/") {
		return "/uploads/*"
	}
	if strings.HasPrefix(p, "/oss/") {
		return "/oss/*"
	}
	if strings.HasPrefix(p, "/files/") {
		return "/files/*"
	}
	return p
}

func (a *API) metricsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/v1/admin/metrics") || strings.HasPrefix(r.URL.Path, "/admin/") {
			next.ServeHTTP(w, r)
			return
		}
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		dur := time.Since(start)

		ip := r.Header.Get("X-Forwarded-For")
		if ip == "" {
			ip = r.Header.Get("X-Real-IP")
		}
		if ip == "" {
			ip = r.RemoteAddr
		}
		if idx := strings.Index(ip, ","); idx != -1 {
			ip = strings.TrimSpace(ip[:idx])
		}
		if idx := strings.LastIndex(ip, ":"); idx != -1 {
			ip = ip[:idx]
		}

		globalMetrics.Record(r.Method, r.URL.Path, rec.status, dur, ip, r.UserAgent())
	})
}

func (a *API) handleAdminAPIMetricsOverview(w http.ResponseWriter, r *http.Request) {
	globalMetrics.mu.RLock()
	defer globalMetrics.mu.RUnlock()

	total := globalMetrics.totalRequests
	errors := globalMetrics.totalErrors
	errRate, avgMs := 0.0, 0.0
	if total > 0 {
		errRate = (float64(errors) / float64(total)) * 100
		avgMs = globalMetrics.totalTimeMs / float64(total)
	}
	qps := float64(len(globalMetrics.recentReqTimes)) / 5.0

	var routeList []RouteStatItem
	for k, stat := range globalMetrics.routes {
		parts := strings.SplitN(k, " ", 2)
		pct := 0.0
		if total > 0 {
			pct = (float64(stat.Count) / float64(total)) * 100
		}
		routeList = append(routeList, RouteStatItem{
			Method: parts[0], Path: parts[1], Count: stat.Count,
			ErrorCount: stat.ErrorCount, AvgTimeMs: stat.AvgTimeMs, Percentage: pct,
		})
	}
	sort.Slice(routeList, func(i, j int) bool { return routeList[i].Count > routeList[j].Count })
	if len(routeList) > 10 {
		routeList = routeList[:10]
	}

	var ipList []IPStatItem
	for _, v := range globalMetrics.ips {
		ipList = append(ipList, *v)
	}
	sort.Slice(ipList, func(i, j int) bool { return ipList[i].Count > ipList[j].Count })
	if len(ipList) > 10 {
		ipList = ipList[:10]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": map[string]any{
			"qps":            qps,
			"total_requests": total,
			"total_errors":   errors,
			"error_rate":     errRate,
			"avg_latency_ms": avgMs,
			"top_routes":     routeList,
			"top_ips":        ipList,
		},
	})
}

func (a *API) handleAdminAPIMetricsLogs(w http.ResponseWriter, r *http.Request) {
	statusFilter := r.URL.Query().Get("status")
	query := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))

	globalMetrics.mu.RLock()
	defer globalMetrics.mu.RUnlock()

	n := len(globalMetrics.ringLogs)
	out := make([]RequestMetricLog, 0, n)
	for i := 0; i < n; i++ {
		idx := (globalMetrics.ringIdx - 1 - i + n) % n
		item := globalMetrics.ringLogs[idx]
		if statusFilter == "errors" && item.Status < 400 {
			continue
		}
		if query != "" {
			if !strings.Contains(strings.ToLower(item.Path), query) &&
				!strings.Contains(strings.ToLower(item.IP), query) &&
				!strings.Contains(strings.ToLower(item.Method), query) {
				continue
			}
		}
		out = append(out, item)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": map[string]any{"logs": out},
	})
}
