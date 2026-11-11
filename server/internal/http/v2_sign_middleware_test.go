package httpapi

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// 构造签名（与 verifyV2Signature 期望的格式一致，模拟客户端）。
func makeTestSign(macKey []byte, method, path, ts, nonce string) string {
	signingString := method + "\n" + path + "\n" + ts + "\n" + nonce
	mac := hmac.New(sha256.New, macKey)
	mac.Write([]byte(signingString))
	return base64.RawStdEncoding.EncodeToString(mac.Sum(nil))
}

func TestVerifyV2Signature(t *testing.T) {
	macKey := []byte("test-mac-key-1234567890")
	now := time.Now().Unix()
	tsStr := strconv.FormatInt(now, 10)
	nonce := "nonce-abc-123"

	req := httptest.NewRequest("GET", "/v2/groups/messages/v2?group_id=GRP-1", nil)
	goodSign := makeTestSign(macKey, "GET", "/v2/groups/messages/v2", tsStr, nonce)

	if !verifyV2Signature(macKey, req, "sess-1", tsStr, nonce, goodSign) {
		t.Fatal("valid signature should pass")
	}
	// 重放：同一 nonce 二次使用应失败。
	if verifyV2Signature(macKey, req, "sess-1", tsStr, nonce, goodSign) {
		t.Fatal("replayed nonce should fail")
	}

	// 错误签名
	bad := makeTestSign(macKey, "POST", "/v2/groups/messages/v2", tsStr, nonce)
	if verifyV2Signature(macKey, req, "sess-1", tsStr, "nonce-new", bad) {
		t.Fatal("tampered signature should fail")
	}

	// 时间窗过期
	oldTs := strconv.FormatInt(now-3600, 10)
	oldSign := makeTestSign(macKey, "GET", "/v2/groups/messages/v2", oldTs, "nonce-old")
	if verifyV2Signature(macKey, req, "sess-1", oldTs, "nonce-old", oldSign) {
		t.Fatal("expired timestamp should fail")
	}

	// 非法时间戳
	if verifyV2Signature(macKey, req, "sess-1", "not-a-number", "n1", "x") {
		t.Fatal("bad ts should fail")
	}

	// 不同会话的 nonce 互不影响
	req2 := httptest.NewRequest("GET", "/v2/updates/difference?pts=0", nil)
	nonce2 := "nonce-sess2"
	s2 := makeTestSign(macKey, "GET", "/v2/updates/difference", tsStr, nonce2)
	if !verifyV2Signature(macKey, req2, "sess-2", tsStr, nonce2, s2) {
		t.Fatal("different session nonce should pass")
	}
}
