package verify

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// GeeTest（极验）4.0 人机验证服务端校验。
// 文档：https://docs.geetest.com/gt4/apirefer/api/server
//
// 前端通过 initGeetest4 拿到 { lot_number, captcha_output, pass_token, gen_time }，
// 服务端用 captcha_key 对 lot_number 生成 sign_token 后请求官方 /validate 接口。
// captcha_id / captcha_key 按项目约定直接写死（注册页只允许官方页面使用）。
// 校验失败一律拒绝（fail-closed）。

const (
	geetestHTTPTimeout  = 8 * time.Second
	geetestMaxBodyBytes = 64 << 10
)

// 变量用于在单元测试中替换为本地校验服务。
var geetestValidateURL = "https://gcaptcha4.geetest.com/validate"

type geetestValidateResponse struct {
	Result      string                 `json:"result"`
	Reason      string                 `json:"reason"`
	CaptchaArgs map[string]interface{} `json:"captcha_args"`
}

// VerifyGeeTest 校验 GeeTest 4.0 验证结果。只有 result === "success" 才放行。
// captchaID / captchaKey 由部署方自己的极验账号提供，未配置时一律拒绝。
func VerifyGeeTest(captchaID, captchaKey, lotNumber, captchaOutput, passToken, genTime, remoteIP string) error {
	captchaID = strings.TrimSpace(captchaID)
	captchaKey = strings.TrimSpace(captchaKey)
	if captchaID == "" || captchaKey == "" {
		return errors.New("geetest not configured")
	}
	lotNumber = strings.TrimSpace(lotNumber)
	captchaOutput = strings.TrimSpace(captchaOutput)
	passToken = strings.TrimSpace(passToken)
	genTime = strings.TrimSpace(genTime)
	parsedGenTime, parseErr := strconv.ParseInt(genTime, 10, 64)
	if lotNumber == "" || captchaOutput == "" || passToken == "" || parseErr != nil || parsedGenTime <= 0 {
		return errors.New("geetest params missing")
	}

	signToken := geetestSignToken(captchaKey, lotNumber)
	form := url.Values{
		"lot_number":     {lotNumber},
		"captcha_output": {captchaOutput},
		"pass_token":     {passToken},
		"gen_time":       {genTime},
		"sign_token":     {signToken},
	}
	if remoteIP = strings.TrimSpace(remoteIP); remoteIP != "" {
		form.Set("ip_address", remoteIP)
	}

	validateURL := geetestValidateURL + "?captcha_id=" + url.QueryEscape(captchaID)
	client := &http.Client{Timeout: geetestHTTPTimeout}
	resp, err := client.Post(validateURL, "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("geetest validate request failed: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, geetestMaxBodyBytes))
	if err != nil {
		return fmt.Errorf("geetest validate read failed: %w", err)
	}

	var result geetestValidateResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return fmt.Errorf("geetest validate invalid response: %w", err)
	}
	if strings.EqualFold(result.Result, "success") {
		return nil
	}
	if result.Reason != "" {
		return fmt.Errorf("geetest verification failed: %s", result.Reason)
	}
	return errors.New("geetest verification failed")
}

// geetestSignToken 按官方要求生成 sign_token = HMAC-SHA256(captcha_key, lot_number)（hex）。
func geetestSignToken(captchaKey, lotNumber string) string {
	mac := hmac.New(sha256.New, []byte(captchaKey))
	_, _ = mac.Write([]byte(lotNumber))
	return hex.EncodeToString(mac.Sum(nil))
}
