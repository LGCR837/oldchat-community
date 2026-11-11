package verify

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Turnstile 人机验证（Cloudflare Turnstile）服务端校验。
// 文档：https://developers.cloudflare.com/turnstile/get-started/server-side-validation/
//
// Secret 只从服务端环境变量 TURNSTILE_SECRET 注入（config 层读取），源码不写死任何字面值。
// 校验失败一律拒绝（fail-closed）。

const (
	turnstileSiteVerifyURL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
	turnstileHTTPTimeout   = 8 * time.Second
)

type turnstileVerifyResponse struct {
	Success    bool     `json:"success"`
	ErrorCodes []string `json:"error-codes"`
}

// VerifyTurnstile 校验前端提交的 Turnstile token。
// 规范调用：POST challenges.cloudflare.com/turnstile/v0/siteverify
// body: { secret: <TURNSTILE_SECRET>, response: <token>, remoteip: <client ip> }
// 只有解析出的 success === true 才放行。
func VerifyTurnstile(secret, token, remoteIP string) error {
	token = strings.TrimSpace(token)
	if token == "" {
		return errors.New("turnstile token missing")
	}
	if strings.TrimSpace(secret) == "" {
		return errors.New("turnstile secret not configured")
	}

	form := url.Values{}
	form.Set("secret", secret)
	form.Set("response", token)
	if remoteIP != "" {
		form.Set("remoteip", remoteIP)
	}

	client := &http.Client{Timeout: turnstileHTTPTimeout}
	resp, err := client.PostForm(turnstileSiteVerifyURL, form)
	if err != nil {
		return fmt.Errorf("turnstile siteverify request failed: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return fmt.Errorf("turnstile siteverify read failed: %w", err)
	}

	var result turnstileVerifyResponse
	if err := json.Unmarshal(body, &result); err != nil {
		return fmt.Errorf("turnstile siteverify invalid response: %w", err)
	}
	if result.Success {
		return nil
	}
	if len(result.ErrorCodes) > 0 {
		return fmt.Errorf("turnstile verification failed: %s", strings.Join(result.ErrorCodes, ","))
	}
	return errors.New("turnstile verification failed")
}
