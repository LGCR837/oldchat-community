package httpapi

import (
	"crypto/sha256"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"metrochat/internal/config"
	"metrochat/internal/verify"
)

// powSolveForTest 用最低难度解一个挑战，仅用于测试。
func powSolveForTest(t *testing.T, challenge string, difficulty int) string {
	t.Helper()
	for nonce := 0; nonce < 2_000_000; nonce++ {
		sum := sha256.Sum256([]byte(challenge + strconv.Itoa(nonce)))
		zeros := 0
		done := false
		for _, b := range sum {
			for i := 7; i >= 0; i-- {
				if b&(1<<uint(i)) != 0 {
					done = true
					break
				}
				zeros++
			}
			if done {
				break
			}
		}
		if zeros >= difficulty {
			return strconv.Itoa(nonce)
		}
	}
	t.Fatalf("无法求解挑战")
	return ""
}

func TestCaptchaChallengeRequiresPowMode(t *testing.T) {
	api := &API{cfg: config.Config{CaptchaMode: config.CaptchaModeOff}}
	rec := httptest.NewRecorder()
	api.handleCaptchaChallenge(rec, httptest.NewRequest(http.MethodGet, "/v1/auth/captcha/challenge", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("非 PoW 模式应返回 404，实际 %d", rec.Code)
	}
}

func TestCaptchaChallengeIssuesToken(t *testing.T) {
	cfg := config.Config{CaptchaMode: config.CaptchaModePow}
	api := &API{cfg: cfg, powGuard: verify.NewPowGuard([]byte("secret"), 8)}
	rec := httptest.NewRecorder()
	api.handleCaptchaChallenge(rec, httptest.NewRequest(http.MethodGet, "/v1/auth/captcha/challenge", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("状态码 = %d, want 200", rec.Code)
	}
	var body captchaChallengeResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if body.Token == "" || body.Challenge == "" || body.Difficulty != 8 {
		t.Fatalf("响应字段不完整: %+v", body)
	}
	// 用挑战算出的解必须能通过校验
	nonce := powSolveForTest(t, body.Challenge, body.Difficulty)
	if err := api.powGuard.Verify(body.Token, nonce); err != nil {
		t.Fatalf("合法解应通过: %v", err)
	}
}

func TestVerifyCaptchaModes(t *testing.T) {
	in := captchaInput{}
	off := &API{cfg: config.Config{CaptchaMode: config.CaptchaModeOff}}
	if err := off.verifyCaptcha("1.2.3.4", in); err != nil {
		t.Fatalf("off 模式应直接放行: %v", err)
	}

	pow := &API{cfg: config.Config{CaptchaMode: config.CaptchaModePow}, powGuard: verify.NewPowGuard([]byte("s"), 8)}
	if err := pow.verifyCaptcha("1.2.3.4", in); err == nil {
		t.Fatalf("pow 模式缺参数应报错")
	}

	geetest := &API{cfg: config.Config{CaptchaMode: config.CaptchaModeGeetest}}
	if err := geetest.verifyCaptcha("1.2.3.4", in); err == nil {
		t.Fatalf("极验未配置应报错")
	}
}

func TestRegisterPageRendersPowConfig(t *testing.T) {
	page := renderRegisterPage(config.Config{CaptchaMode: config.CaptchaModePow, PowDifficulty: 18})
	if strings.Contains(page, "gt4.js") {
		t.Fatalf("PoW 模式不应加载极验脚本")
	}
	if !strings.Contains(page, `"captcha":"pow"`) {
		t.Fatalf("页面缺少人机验证配置")
	}
	if !strings.Contains(page, "solvePow") {
		t.Fatalf("页面缺少自建验证脚本")
	}
	if !strings.Contains(page, "email-code-row") {
		t.Fatalf("页面结构缺失邮箱验证码行")
	}
}

func TestRegisterPageRendersGeetestConfig(t *testing.T) {
	page := renderRegisterPage(config.Config{
		CaptchaMode: config.CaptchaModeGeetest,
		GeetestID:   "test-id",
		EmailVerify: true,
	})
	if !strings.Contains(page, "gt4.js") {
		t.Fatalf("极验模式应加载 gt4.js")
	}
	if !strings.Contains(page, `"geetestId":"test-id"`) {
		t.Fatalf("页面未注入极验 ID")
	}
}

func TestForgotPasswordPageHonorsEmailVerify(t *testing.T) {
	disabledAPI := &API{cfg: config.Config{CaptchaMode: config.CaptchaModePow, EmailVerify: false}}
	rec := httptest.NewRecorder()
	disabledAPI.handleForgotPasswordPage(rec, httptest.NewRequest(http.MethodGet, "/forgot-password", nil))
	if !strings.Contains(rec.Body.String(), "无法自助重置密码") {
		t.Fatalf("关闭邮箱验证时应展示提示页")
	}
	if strings.Contains(rec.Body.String(), "resetForm") {
		t.Fatalf("关闭邮箱验证时不应渲染重置表单")
	}

	enabledAPI := &API{cfg: config.Config{CaptchaMode: config.CaptchaModePow, EmailVerify: true}}
	rec2 := httptest.NewRecorder()
	enabledAPI.handleForgotPasswordPage(rec2, httptest.NewRequest(http.MethodGet, "/forgot-password", nil))
	body := rec2.Body.String()
	if !strings.Contains(body, "resetForm") || !strings.Contains(body, "solvePow") {
		t.Fatalf("开启邮箱验证时应渲染完整重置表单")
	}
}
