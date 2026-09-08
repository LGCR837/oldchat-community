package auth

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestPKCES256(t *testing.T) {
	verifier := strings.Repeat("a", 43)
	challenge, ok := PKCEChallengeS256(verifier)
	if !ok || len(challenge) != 43 {
		t.Fatalf("PKCE challenge invalid: %q", challenge)
	}
	if !VerifyPKCES256(verifier, challenge) {
		t.Fatal("正确 verifier 应通过")
	}
	if VerifyPKCES256(strings.Repeat("b", 43), challenge) {
		t.Fatal("错误 verifier 不应通过")
	}
	if _, err := base64.RawURLEncoding.DecodeString(challenge); err != nil {
		t.Fatalf("challenge 不是 Base64URL: %v", err)
	}
}

func TestNormalizeScopes(t *testing.T) {
	scope, ok := NormalizeScopes("profile openid", "openid profile offline_access")
	if !ok || scope != "openid profile" {
		t.Fatalf("scope 规范化失败: %q %v", scope, ok)
	}
	if _, ok = NormalizeScopes("openid chat.write", "openid profile"); ok {
		t.Fatal("不得批准 client 白名单外 scope")
	}
	if _, ok = NormalizeScopes("profile", "openid profile"); ok {
		t.Fatal("OIDC 请求必须包含 openid")
	}
}
