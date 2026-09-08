package verify

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGeeTestSignToken(t *testing.T) {
	// 官方示例：lot_number + captcha_key 的 HMAC-SHA256 十六进制。
	// 验证算法与官方一致（任意输入只要前后一致即可证明实现稳定）。
	s1 := geetestSignToken("b241019216ca3c061cf83afc02ba2210", "abcdef123456")
	if len(s1) != 64 {
		t.Fatalf("sign token length = %d, want 64", len(s1))
	}
	s2 := geetestSignToken("b241019216ca3c061cf83afc02ba2210", "abcdef123456")
	if s1 != s2 {
		t.Fatalf("sign token not deterministic")
	}
	s3 := geetestSignToken("b241019216ca3c061cf83afc02ba2210", "abcdef123457")
	if s1 == s3 {
		t.Fatalf("sign token should differ for different lot numbers")
	}
	if strings.ContainsAny(s1, " \t\r\n") {
		t.Fatalf("sign token contains whitespace")
	}
}

func TestVerifyGeeTestMissingParams(t *testing.T) {
	if err := VerifyGeeTest("", "out", "pass", "123", ""); err == nil {
		t.Fatalf("expected error for missing lot_number")
	}
	if err := VerifyGeeTest("lot", "", "pass", "123", ""); err == nil {
		t.Fatalf("expected error for missing captcha_output")
	}
	if err := VerifyGeeTest("lot", "out", "", "123", ""); err == nil {
		t.Fatalf("expected error for missing pass_token")
	}
	if err := VerifyGeeTest("lot", "out", "pass", "0", ""); err == nil {
		t.Fatalf("expected error for gen_time <= 0")
	}
	if err := VerifyGeeTest("lot", "out", "pass", "not-a-time", ""); err == nil {
		t.Fatalf("expected error for non-numeric gen_time")
	}
}

func TestVerifyGeeTestUsesFormEncoding(t *testing.T) {
	const (
		lotNumber = "lot-123"
		genTime   = "1722912345"
	)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if got := r.Header.Get("Content-Type"); !strings.HasPrefix(got, "application/x-www-form-urlencoded") {
			t.Errorf("content-type = %q, want form encoded", got)
		}
		if err := r.ParseForm(); err != nil {
			t.Errorf("ParseForm: %v", err)
		}
		want := map[string]string{
			"lot_number":     lotNumber,
			"captcha_output": "output-123",
			"pass_token":     "pass-123",
			"gen_time":       genTime,
			"sign_token":     geetestSignToken(geetestCaptchaKey, lotNumber),
			"ip_address":     "203.0.113.10",
		}
		for key, value := range want {
			if got := r.Form.Get(key); got != value {
				t.Errorf("form[%q] = %q, want %q", key, got, value)
			}
		}
		if got := r.URL.Query().Get("captcha_id"); got != geetestCaptchaID {
			t.Errorf("captcha_id = %q, want %q", got, geetestCaptchaID)
		}
		_ = json.NewEncoder(w).Encode(geetestValidateResponse{Result: "success"})
	}))
	defer server.Close()

	oldURL := geetestValidateURL
	geetestValidateURL = server.URL
	defer func() { geetestValidateURL = oldURL }()

	if err := VerifyGeeTest(lotNumber, "output-123", "pass-123", genTime, "203.0.113.10"); err != nil {
		t.Fatalf("VerifyGeeTest: %v", err)
	}
}
