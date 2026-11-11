package httpapi

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type RouteSwitchManager struct {
	mu       sync.RWMutex
	filePath string
	disabled map[string]bool
}

var globalRouteSwitch = &RouteSwitchManager{
	disabled: make(map[string]bool),
}

func initRouteSwitch(dataDir string) {
	if dataDir == "" {
		dataDir = "data"
	}
	globalRouteSwitch.filePath = filepath.Join(dataDir, "disabled_routes.json")
	globalRouteSwitch.load()
}

func (m *RouteSwitchManager) load() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.filePath == "" {
		return
	}
	data, err := os.ReadFile(m.filePath)
	if err != nil {
		return
	}
	var list []string
	if err := json.Unmarshal(data, &list); err == nil {
		m.disabled = make(map[string]bool)
		for _, k := range list {
			if k != "" {
				m.disabled[k] = true
			}
		}
	}
}

func (m *RouteSwitchManager) save() {
	if m.filePath == "" {
		return
	}
	var list []string
	for k, dis := range m.disabled {
		if dis {
			list = append(list, k)
		}
	}
	data, _ := json.MarshalIndent(list, "", "  ")
	_ = os.MkdirAll(filepath.Dir(m.filePath), 0755)
	_ = os.WriteFile(m.filePath, data, 0644)
}

func (m *RouteSwitchManager) IsDisabled(method, path string) bool {
	// 管理后台与控制台 API 受绝对白名单保护
	if path == "/admins" || path == "/admin" || strings.HasPrefix(path, "/admin/") || strings.HasPrefix(path, "/v1/admin/") {
		return false
	}
	m.mu.RLock()
	defer m.mu.RUnlock()

	methodKey := method + " " + path
	if m.disabled[methodKey] || m.disabled[path] {
		return true
	}
	for disKey := range m.disabled {
		if strings.HasPrefix(disKey, "ALL ") {
			prefix := strings.TrimPrefix(disKey, "ALL ")
			if strings.HasPrefix(path, prefix) {
				return true
			}
		} else if strings.HasPrefix(disKey, method+" ") {
			prefix := strings.TrimPrefix(disKey, method+" ")
			if strings.HasPrefix(path, prefix) {
				return true
			}
		} else if strings.HasPrefix(path, disKey) {
			return true
		}
	}
	return false
}

func (a *API) routeSwitchMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if globalRouteSwitch.IsDisabled(r.Method, r.URL.Path) {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.WriteHeader(http.StatusServiceUnavailable)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"code":    503,
				"error":   "route_disabled",
				"message": "该接口目前处于维护或关闭状态，请稍后再试",
			})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *API) handleAdminAPIRoutesGet(w http.ResponseWriter, r *http.Request) {
	globalRouteSwitch.mu.RLock()
	defer globalRouteSwitch.mu.RUnlock()

	routes := make([]SystemRouteItem, len(allSystemRoutes))
	copy(routes, allSystemRoutes)

	for i := range routes {
		k := routes[i].Method + " " + routes[i].Path
		if globalRouteSwitch.disabled[routes[i].Key] || globalRouteSwitch.disabled[k] || globalRouteSwitch.disabled[routes[i].Path] {
			routes[i].Enabled = false
		} else {
			routes[i].Enabled = true
		}
	}

	var customList []string
	for k := range globalRouteSwitch.disabled {
		isPre := false
		for _, pr := range allSystemRoutes {
			if pr.Key == k || (pr.Method+" "+pr.Path) == k || pr.Path == k {
				isPre = true
				break
			}
		}
		if !isPre {
			customList = append(customList, k)
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code": 200,
		"data": map[string]any{
			"routes":        routes,
			"custom_routes": customList,
		},
	})
}

type routeToggleReq struct {
	Key     string `json:"key"`
	Enabled bool   `json:"enabled"`
	Method  string `json:"method"`
	Path    string `json:"path"`
}

func (a *API) handleAdminAPIRoutesToggle(w http.ResponseWriter, r *http.Request) {
	var req routeToggleReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || (req.Key == "" && req.Path == "") {
		writeError(w, http.StatusBadRequest, "invalid_req", "key or path required")
		return
	}

	targetKey := req.Key
	if targetKey == "" {
		m := strings.ToUpper(strings.TrimSpace(req.Method))
		if m == "" {
			m = "ALL"
		}
		targetKey = m + " " + strings.TrimSpace(req.Path)
	}

	if strings.Contains(targetKey, "/admin") {
		writeError(w, http.StatusForbidden, "forbidden", "管理后台接口受保护，不可关闭")
		return
	}

	globalRouteSwitch.mu.Lock()
	if req.Enabled {
		delete(globalRouteSwitch.disabled, targetKey)
		for _, pr := range allSystemRoutes {
			if pr.Key == targetKey {
				delete(globalRouteSwitch.disabled, pr.Method+" "+pr.Path)
				delete(globalRouteSwitch.disabled, pr.Path)
				delete(globalRouteSwitch.disabled, pr.Key)
			}
		}
	} else {
		globalRouteSwitch.disabled[targetKey] = true
		for _, pr := range allSystemRoutes {
			if pr.Key == targetKey {
				globalRouteSwitch.disabled[pr.Method+" "+pr.Path] = true
				if pr.Method == "ALL" {
					globalRouteSwitch.disabled[pr.Path] = true
				}
			}
		}
	}
	globalRouteSwitch.save()
	globalRouteSwitch.mu.Unlock()

	writeJSON(w, http.StatusOK, map[string]any{
		"code":    200,
		"message": "success",
		"enabled": req.Enabled,
	})
}
