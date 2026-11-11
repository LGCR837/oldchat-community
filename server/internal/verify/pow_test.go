package verify

import (
	"crypto/sha256"
	"strconv"
	"testing"
)

// solvePow 用最低难度暴力求解，仅用于测试。
func solvePow(challenge string, difficulty int) string {
	for nonce := 0; nonce < 5_000_000; nonce++ {
		sum := sha256.Sum256([]byte(challenge + strconv.Itoa(nonce)))
		if leadingZeroBits(sum[:]) >= difficulty {
			return strconv.Itoa(nonce)
		}
	}
	return ""
}

func TestPowGuardRoundTrip(t *testing.T) {
	guard := NewPowGuard([]byte("test-secret"), powMinDifficulty)
	token, challenge, err := guard.Challenge()
	if err != nil {
		t.Fatalf("Challenge: %v", err)
	}
	if challenge == "" || token == "" {
		t.Fatalf("empty challenge or token")
	}
	nonce := solvePow(challenge, guard.Difficulty())
	if nonce == "" {
		t.Fatalf("无法求解挑战，难度 %d 可能过高", guard.Difficulty())
	}
	if err := guard.Verify(token, nonce); err != nil {
		t.Fatalf("Verify 应通过，实际: %v", err)
	}
}

func TestPowGuardRejectsReplay(t *testing.T) {
	guard := NewPowGuard([]byte("test-secret"), powMinDifficulty)
	token, challenge, _ := guard.Challenge()
	nonce := solvePow(challenge, guard.Difficulty())
	if err := guard.Verify(token, nonce); err != nil {
		t.Fatalf("首次校验应通过: %v", err)
	}
	if err := guard.Verify(token, nonce); err != ErrPowReplay {
		t.Fatalf("重复提交应返回 ErrPowReplay，实际: %v", err)
	}
}

func TestPowGuardRejectsBadSolution(t *testing.T) {
	guard := NewPowGuard([]byte("test-secret"), powMinDifficulty)
	token, challenge, _ := guard.Challenge()
	nonce := solvePow(challenge, guard.Difficulty())
	wrong := nonce + "0"
	if err := guard.Verify(token, wrong); err != ErrPowUnsolved {
		t.Fatalf("错误 nonce 应返回 ErrPowUnsolved，实际: %v", err)
	}
}

func TestPowGuardRejectsTamperedToken(t *testing.T) {
	guard := NewPowGuard([]byte("test-secret"), powMinDifficulty)
	token, challenge, _ := guard.Challenge()
	nonce := solvePow(challenge, guard.Difficulty())
	// 换一个 secret 相当于签名被篡改。
	other := NewPowGuard([]byte("another-secret"), powMinDifficulty)
	if err := other.Verify(token, nonce); err != ErrPowInvalid {
		t.Fatalf("篡改 token 应返回 ErrPowInvalid，实际: %v", err)
	}
}

func TestPowGuardRejectsExpiredToken(t *testing.T) {
	guard := NewPowGuard([]byte("test-secret"), powMinDifficulty)
	guard.ttl = 0
	token, challenge, _ := guard.Challenge()
	nonce := solvePow(challenge, guard.Difficulty())
	if err := guard.Verify(token, nonce); err != ErrPowExpired {
		t.Fatalf("过期 token 应返回 ErrPowExpired，实际: %v", err)
	}
}

func TestPowGuardRejectsMissingParams(t *testing.T) {
	guard := NewPowGuard([]byte("test-secret"), powMinDifficulty)
	if err := guard.Verify("", "1"); err != ErrPowMissing {
		t.Fatalf("缺 token 应返回 ErrPowMissing，实际: %v", err)
	}
	if err := guard.Verify("abc.def", ""); err != ErrPowMissing {
		t.Fatalf("缺 nonce 应返回 ErrPowMissing，实际: %v", err)
	}
}

func TestPowDifficultyFallback(t *testing.T) {
	if got := NewPowGuard(nil, 1).Difficulty(); got != PowDefaultDifficulty {
		t.Fatalf("过低难度应回落默认值，实际 %d", got)
	}
	if got := NewPowGuard(nil, 99).Difficulty(); got != PowDefaultDifficulty {
		t.Fatalf("过高难度应回落默认值，实际 %d", got)
	}
	if got := NewPowGuard(nil, 20).Difficulty(); got != 20 {
		t.Fatalf("合法难度应保留，实际 %d", got)
	}
}
