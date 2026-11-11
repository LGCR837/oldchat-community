package verify

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strconv"
	"strings"
	"sync"
	"time"
)

// PowGuard 自建工作量证明（proof of work）人机验证，不依赖任何第三方服务。
//
// 流程：
//  1. 客户端 GET /v1/auth/captcha/challenge 拿到 {token, challenge, difficulty}
//  2. 客户端枚举 nonce，直到 sha256(challenge + nonce) 的前 difficulty 位全为 0
//  3. 客户端把 {pow_token, pow_nonce} 随请求提交，服务端复算一次校验
//
// token 自带过期时间并用 HMAC 签名，服务端无需存储挑战；已用过的挑战记在内存里防重放。
// 难度按位计算：18 位约 26 万次哈希，普通浏览器 1 秒内可解，脚本批量注册的成本则成倍上升。

var (
	// ErrPowMissing 参数缺失。
	ErrPowMissing = errors.New("pow params missing")
	// ErrPowInvalid token 结构或签名不正确。
	ErrPowInvalid = errors.New("pow token invalid")
	// ErrPowExpired 挑战已过期。
	ErrPowExpired = errors.New("pow token expired")
	// ErrPowReplay 同一挑战被重复使用。
	ErrPowReplay = errors.New("pow token already used")
	// ErrPowUnsolved 提交的 nonce 不满足难度要求。
	ErrPowUnsolved = errors.New("pow solution rejected")
)

const (
	// PowDefaultDifficulty 默认难度（前导零位数）。
	PowDefaultDifficulty = 18
	powMinDifficulty     = 8
	powMaxDifficulty     = 26
	powTokenTTL          = 5 * time.Minute
)

// PowGuard 是无状态的挑战签发与校验器，仅在内存里记录已使用的挑战。
type PowGuard struct {
	secret     []byte
	difficulty int
	ttl        time.Duration

	mu   sync.Mutex
	used map[string]time.Time
}

// NewPowGuard 构造校验器。difficulty 超出合理区间时回落到默认值。
func NewPowGuard(secret []byte, difficulty int) *PowGuard {
	if difficulty < powMinDifficulty || difficulty > powMaxDifficulty {
		difficulty = PowDefaultDifficulty
	}
	return &PowGuard{
		secret:     secret,
		difficulty: difficulty,
		ttl:        powTokenTTL,
		used:       make(map[string]time.Time),
	}
}

// Difficulty 返回当前难度。
func (g *PowGuard) Difficulty() int { return g.difficulty }

// TTL 返回挑战有效期。
func (g *PowGuard) TTL() time.Duration { return g.ttl }

// Challenge 签发一次挑战，返回签名后的 token、明文 challenge 与难度。
func (g *PowGuard) Challenge() (token, challenge string, err error) {
	raw := make([]byte, 16)
	if _, err = rand.Read(raw); err != nil {
		return "", "", err
	}
	challenge = hex.EncodeToString(raw)
	payload := "v1." + challenge + "." + strconv.FormatInt(time.Now().Add(g.ttl).Unix(), 10)
	return g.sign(payload), challenge, nil
}

func (g *PowGuard) sign(payload string) string {
	mac := hmac.New(sha256.New, g.secret)
	_, _ = mac.Write([]byte(payload))
	return base64.RawURLEncoding.EncodeToString([]byte(payload)) + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// Verify 校验客户端提交的挑战与解。
func (g *PowGuard) Verify(token, nonce string) error {
	token = strings.TrimSpace(token)
	nonce = strings.TrimSpace(nonce)
	if token == "" || nonce == "" {
		return ErrPowMissing
	}
	if len(nonce) > 64 {
		return ErrPowUnsolved
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return ErrPowInvalid
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return ErrPowInvalid
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ErrPowInvalid
	}
	mac := hmac.New(sha256.New, g.secret)
	_, _ = mac.Write(payload)
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return ErrPowInvalid
	}
	fields := strings.Split(string(payload), ".")
	if len(fields) != 3 || fields[0] != "v1" {
		return ErrPowInvalid
	}
	exp, err := strconv.ParseInt(fields[2], 10, 64)
	if err != nil {
		return ErrPowInvalid
	}
	if time.Now().Unix() >= exp {
		return ErrPowExpired
	}

	challenge := fields[1]
	g.mu.Lock()
	defer g.mu.Unlock()
	g.pruneLocked()
	if _, ok := g.used[challenge]; ok {
		return ErrPowReplay
	}
	if !powSolved(challenge, nonce, g.difficulty) {
		return ErrPowUnsolved
	}
	g.used[challenge] = time.Now()
	return nil
}

// pruneLocked 清理过期的已用记录，调用方需持锁。
func (g *PowGuard) pruneLocked() {
	cutoff := time.Now().Add(-g.ttl)
	for challenge, at := range g.used {
		if at.Before(cutoff) {
			delete(g.used, challenge)
		}
	}
}

func powSolved(challenge, nonce string, difficulty int) bool {
	sum := sha256.Sum256([]byte(challenge + nonce))
	return leadingZeroBits(sum[:]) >= difficulty
}

func leadingZeroBits(sum []byte) int {
	n := 0
	for _, b := range sum {
		if b == 0 {
			n += 8
			continue
		}
		for i := 7; i >= 0; i-- {
			if b&(1<<uint(i)) != 0 {
				return n
			}
			n++
		}
		return n
	}
	return n
}
