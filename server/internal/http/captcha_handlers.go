package httpapi

import (
	"errors"
	"net/http"

	"metrochat/internal/config"
	"metrochat/internal/verify"
)

// captchaInput 汇总各前端提交的人机验证参数。
// 具体使用哪一组由 cfg.CaptchaMode 决定，其余字段忽略。
type captchaInput struct {
	PowToken string `json:"pow_token"`
	PowNonce string `json:"pow_nonce"`

	GeeTestLotNumber     string         `json:"geetest_lot_number"`
	GeeTestCaptchaOutput string         `json:"geetest_captcha_output"`
	GeeTestPassToken     string         `json:"geetest_pass_token"`
	GeeTestGenTime       geetestGenTime `json:"geetest_gen_time"`

	TurnstileToken string `json:"turnstile_token"`
}

type captchaChallengeResponse struct {
	Token      string `json:"token"`
	Challenge  string `json:"challenge"`
	Difficulty int    `json:"difficulty"`
	Algorithm  string `json:"algorithm"`
	ExpiresIn  int    `json:"expires_in"`
}

// verifyCaptcha 按配置的方式校验人机验证参数。
// off 直接放行；pow 校验工作量证明；geetest / turnstile 走各自的第三方校验。
func (a *API) verifyCaptcha(ip string, in captchaInput) error {
	switch a.cfg.CaptchaMode {
	case config.CaptchaModeOff:
		return nil
	case config.CaptchaModePow:
		if a.powGuard == nil {
			return errors.New("pow unavailable")
		}
		return a.powGuard.Verify(in.PowToken, in.PowNonce)
	case config.CaptchaModeGeetest:
		return verify.VerifyGeeTest(a.cfg.GeetestID, a.cfg.GeetestKey,
			in.GeeTestLotNumber, in.GeeTestCaptchaOutput, in.GeeTestPassToken,
			string(in.GeeTestGenTime), ip)
	case config.CaptchaModeTurnstile:
		return verify.VerifyTurnstile(a.cfg.TurnstileSecretKey, in.TurnstileToken, ip)
	}
	return errors.New("captcha mode invalid")
}

// handleCaptchaChallenge 签发自建 PoW 挑战（GET /v1/auth/captcha/challenge）。
// 仅 CaptchaMode=pow 时开放，其余模式返回 404，页面也不会请求。
func (a *API) handleCaptchaChallenge(w http.ResponseWriter, r *http.Request) {
	if a.powGuard == nil || a.cfg.CaptchaMode != config.CaptchaModePow {
		http.NotFound(w, r)
		return
	}
	token, challenge, err := a.powGuard.Challenge()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "captcha_failed", "生成挑战失败")
		return
	}
	writeJSON(w, http.StatusOK, captchaChallengeResponse{
		Token:      token,
		Challenge:  challenge,
		Difficulty: a.powGuard.Difficulty(),
		Algorithm:  "sha256",
		ExpiresIn:  int(a.powGuard.TTL().Seconds()),
	})
}
