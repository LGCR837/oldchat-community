package httpapi

import (
	"net/http"
	"strings"
)

// corsAllowMethods 是预检允许的方法集合。PATCH/PUT 目前没有路由使用，
// 但第三方网页版做资源管理时会用到，一并放行。
const corsAllowMethods = "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS"

// corsAllowHeaders 是没有携带 Access-Control-Request-Headers 时的兜底放行列表。
// 覆盖密文链路（X-Enc / X-Session / X-Enc-Compression / X-Auth）、
// v2 签名（X-Ts / X-Nonce / X-Sign / X-Device-Id）、
// 上传校验（X-File-Size / X-File-SHA256）、阅后即焚（X-Burn-Secure）。
var corsAllowHeaders = []string{
	"Authorization",
	"Content-Type",
	"Accept",
	"Accept-Encoding",
	"Range",
	"X-Enc",
	"X-Session",
	"X-Enc-Compression",
	"X-Auth",
	"X-Ts",
	"X-Nonce",
	"X-Sign",
	"X-Device-Id",
	"X-File-Size",
	"X-File-SHA256",
	"X-Burn-Secure",
}

// corsExposeHeaders 让浏览器端能读到这些响应头：
// 下载文件名、断点续传区间、解压后的真实长度。
const corsExposeHeaders = "Content-Length, Content-Range, Accept-Ranges, Content-Disposition, Content-Encoding"

// corsPathPrefixes 只对这些前缀开放跨域。
// admin / channel-manage / moderator / oauth / shop 等依赖 Cookie 会话的
// 网页路径不在其中，避免把 CSRF 面暴露给任意站点。
var corsPathPrefixes = []string{"/v1/", "/v2/", "/ws", "/download/"}

// corsPathAllowed 判断路径是否在跨域白名单内。
func corsPathAllowed(path string) bool {
	for _, prefix := range corsPathPrefixes {
		if strings.HasPrefix(path, prefix) {
			return true
		}
		// /v1 这类不带尾斜杠的精确路径也要放行。
		if strings.TrimSuffix(prefix, "/") == path {
			return true
		}
	}
	return false
}

// corsOriginAllowed 判断来源是否被允许。白名单为空表示不限制。
func (a *API) corsOriginAllowed(origin string) bool {
	if len(a.cfg.CorsAllowedOrigins) == 0 {
		return true
	}
	for _, allowed := range a.cfg.CorsAllowedOrigins {
		if allowed == "*" || strings.EqualFold(allowed, origin) {
			return true
		}
	}
	return false
}

// addVaryHeader 幂等地把字段追加到 Vary，避免覆盖 gzip 写入的 Accept-Encoding。
func addVaryHeader(header http.Header, field string) {
	vary := header.Get("Vary")
	if vary == "" {
		header.Set("Vary", field)
		return
	}
	for _, part := range strings.Split(vary, ",") {
		if strings.EqualFold(strings.TrimSpace(part), field) {
			return
		}
	}
	header.Set("Vary", vary+", "+field)
}

// corsMiddleware 为 API 路径提供跨域支持。
// 接口鉴权只读 Authorization，不依赖 Cookie，所以响应回显具体 Origin 而不是
// 通配符，也不需要 Access-Control-Allow-Credentials。
// 预检在这里直接短路返回 204，不进入 routeSwitch 与业务中间件：
// 否则接口被临时关闭时预检会拿到 503，浏览器会报成 CORS 失败，难以排查。
func (a *API) corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !a.cfg.CorsEnabled || !corsPathAllowed(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		origin := strings.TrimSpace(r.Header.Get("Origin"))
		if origin == "" {
			next.ServeHTTP(w, r)
			return
		}

		allowed := a.corsOriginAllowed(origin)
		if allowed {
			header := w.Header()
			header.Set("Access-Control-Allow-Origin", origin)
			header.Set("Access-Control-Expose-Headers", corsExposeHeaders)
			addVaryHeader(header, "Origin")
		}

		// 预检请求：无论来源是否允许都回 204，不给 405，方便定位是来源问题。
		if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
			header := w.Header()
			if allowed {
				header.Set("Access-Control-Allow-Methods", corsAllowMethods)
				requested := strings.TrimSpace(r.Header.Get("Access-Control-Request-Headers"))
				if requested != "" {
					header.Set("Access-Control-Allow-Headers", requested)
				} else {
					header.Set("Access-Control-Allow-Headers", strings.Join(corsAllowHeaders, ", "))
				}
				header.Set("Access-Control-Max-Age", "600")
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}
