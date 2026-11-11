package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	signTsHeader    = "X-Ts"
	signNonceHeader = "X-Nonce"
	signHeader      = "X-Sign"
	deviceIDHeader  = "X-Device-Id"

	// 签名时间窗（秒）：容忍客户端/服务端时钟偏差。
	signTimestampWindowSec = 300
	// nonce 防重放窗口（秒）。
	nonceWindowSec = 300
	// nonce 表上限，防止内存无限增长。
	nonceMapMax = 10000
)

// v2SignMiddleware 为 /v2 路由提供三层防护：
//  1. 强制会话：无有效 X-Session 一律拒绝（挡 curl 裸调）。
//  2. 签名校验：HMAC-SHA256(session.MacKey, METHOD\nPATH\nTS\nNONCE)。
//     - cfg.V2RequireSignature=true：缺失/错误签名都拒绝；
//     - false（灰度）：有签名则校验（失败拒绝），无签名放行（兼容旧客户端）。
//  3. 设备绑定（灰度）：X-Device-Id 必须属于当前用户。
//
// 顺序：secureMiddleware（解密 body）→ authMiddleware（注入 claims）→ 本中间件。
// 需挂在 authMiddleware 之后以读取 claims。
func (a *API) v2SignMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// multipart 大文件不能经过 secureMiddleware 的整包解密，也不应在内存中签正文。
		// 这两个端点仍已由外层 authMiddleware 完成 Bearer JWT 鉴权。
		if isStreamingFileRequest(r) {
			next.ServeHTTP(w, r)
			return
		}
		// 1. 强制会话：无有效 X-Session 一律拒绝。
		// 所有客户端（含 Android 2.3，通过 SpongyCastle ECDH）都能建立加密会话，
		// 不再需要 UA 降级兜底。删除降级后 curl 伪造任意 UA 都无法绕过。
		sessionID := r.Header.Get(sessionHeader)
		if sessionID == "" {
			writeError(w, http.StatusUnauthorized, "missing_session", "missing session")
			return
		}
		keys, ok := a.sessions.Get(sessionID)
		if !ok {
			writeError(w, http.StatusUnauthorized, "invalid_session", "invalid session")
			return
		}

		// 2. 签名校验
		tsStr := r.Header.Get(signTsHeader)
		nonce := r.Header.Get(signNonceHeader)
		sign := r.Header.Get(signHeader)
		if a.cfg.V2RequireSignature {
			if tsStr == "" || nonce == "" || sign == "" {
				writeError(w, http.StatusUnauthorized, "missing_signature", "missing signature")
				return
			}
			if !verifyV2Signature(keys.MacKey, r, sessionID, tsStr, nonce, sign) {
				writeError(w, http.StatusUnauthorized, "bad_signature", "invalid signature")
				return
			}
		} else if sign != "" {
			// 灰度：有签名则校验，失败拒绝；无签名放行（旧客户端）。
			if !verifyV2Signature(keys.MacKey, r, sessionID, tsStr, nonce, sign) {
				writeError(w, http.StatusUnauthorized, "bad_signature", "invalid signature")
				return
			}
		}

		// 3. 设备绑定（灰度）
		deviceID := r.Header.Get(deviceIDHeader)
		if deviceID != "" {
			claims, ok := claimsFromContext(r.Context())
			if ok && !a.deviceBelongsTo(claims.Subject, deviceID) {
				writeError(w, http.StatusForbidden, "device_mismatch", "device not bound to account")
				return
			}
		} else if a.cfg.V2RequireDevice {
			writeError(w, http.StatusUnauthorized, "missing_device", "missing device id")
			return
		}

		next.ServeHTTP(w, r)
	})
}

// verifyV2Signature 计算 HMAC 并恒定时间比较。
func isStreamingFileRequest(r *http.Request) bool {
	if r == nil {
		return false
	}
	if r.Method == http.MethodPost && (r.URL.Path == "/v1/files/upload" || r.URL.Path == "/v2/files/upload" || r.URL.Path == "/v2/resources/upload") {
		return true
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	return len(parts) == 4 && parts[0] == "v2" && (parts[1] == "files" || parts[1] == "resources") &&
		parts[2] == "download" && parts[3] != ""
}

func verifyV2Signature(macKey []byte, r *http.Request, sessionID, tsStr, nonce, sign string) bool {
	ts, err := strconv.ParseInt(tsStr, 10, 64)
	if err != nil {
		return false
	}
	now := time.Now().Unix()
	if ts < now-signTimestampWindowSec || ts > now+signTimestampWindowSec {
		return false
	}
	if nonce == "" || len(nonce) > 128 {
		return false
	}
	// nonce 防重放：同一会话同一 nonce 在窗口内只能出现一次。
	if !claimV2Nonce(sessionID, nonce, ts) {
		return false
	}

	signingString := strings.Join([]string{
		r.Method,
		r.URL.Path,
		tsStr,
		nonce,
	}, "\n")
	mac := hmac.New(sha256.New, macKey)
	mac.Write([]byte(signingString))
	expected := base64.RawStdEncoding.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(expected), []byte(sign))
}

var (
	v2NonceMu  sync.Mutex
	v2NonceMap = make(map[string]int64) // "sessionID:nonce" -> ts
)

// claimV2Nonce 记录 nonce；已存在且未过期则返回 false（重放）。
func claimV2Nonce(sessionID, nonce string, ts int64) bool {
	key := sessionID + ":" + nonce
	now := time.Now().Unix()
	v2NonceMu.Lock()
	defer v2NonceMu.Unlock()
	if prev, seen := v2NonceMap[key]; seen && prev >= now-nonceWindowSec {
		return false
	}
	v2NonceMap[key] = ts
	if len(v2NonceMap) > nonceMapMax {
		// 简单清理：移除窗口外的条目。
		for k, t := range v2NonceMap {
			if t < now-nonceWindowSec {
				delete(v2NonceMap, k)
			}
		}
	}
	return true
}

// deviceBelongsTo 校验设备属于该用户（user_devices 表）。
func (a *API) deviceBelongsTo(userID, deviceID string) bool {
	if userID == "" || deviceID == "" || a.devices == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	return a.devices.IsDeviceOfUser(ctx, userID, deviceID)
}
