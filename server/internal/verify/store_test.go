package verify

import (
	"testing"
	"time"
)

func TestEmailCodePurposeIsolationAndSingleUse(t *testing.T) {
	store := NewEmailCodeStore()
	store.SetForPurpose("User@QQ.COM", "register", "123456", time.Minute)
	if store.VerifyForPurpose("user@qq.com", "reset", "123456") {
		t.Fatal("注册验证码不能用于重置密码")
	}
	if !store.VerifyForPurpose("user@qq.com", "register", "123456") {
		t.Fatal("正确用途的验证码应验证成功")
	}
	if store.VerifyForPurpose("user@qq.com", "register", "123456") {
		t.Fatal("验证码只能使用一次")
	}
}

func TestRandomDigits(t *testing.T) {
	code := RandomDigits(32)
	if len(code) != 32 {
		t.Fatalf("验证码长度错误: %d", len(code))
	}
	for i := 0; i < len(code); i++ {
		if code[i] < '0' || code[i] > '9' {
			t.Fatalf("验证码包含非数字字符: %q", code[i])
		}
	}
}

func TestEmailCodeFailedAttemptsLimit(t *testing.T) {
	store := NewEmailCodeStore()
	store.SetForPurpose("victim@qq.com", "register", "654321", time.Minute)

	// 连续错误 5 次后验证码作废。
	for i := 0; i < emailCodeMaxFailedAttempts; i++ {
		if store.VerifyForPurpose("victim@qq.com", "register", "000000") {
			t.Fatalf("错误验证码不应通过（第 %d 次）", i+1)
		}
	}
	// 作废后即使填对也无法通过（防暴力猜测）。
	if store.VerifyForPurpose("victim@qq.com", "register", "654321") {
		t.Fatal("验证码在连续错误后应被作废")
	}
}

func TestEmailCodeFailedAttemptsResetAfterSuccess(t *testing.T) {
	store := NewEmailCodeStore()
	store.SetForPurpose("ok@qq.com", "register", "111111", time.Minute)
	// 1 次错误后填对仍能通过。
	store.VerifyForPurpose("ok@qq.com", "register", "000000")
	if !store.VerifyForPurpose("ok@qq.com", "register", "111111") {
		t.Fatal("少量错误后正确验证码仍应通过")
	}
}
