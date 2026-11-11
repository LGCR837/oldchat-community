package config

import (
	"os"
	"path/filepath"
	"testing"
)

// isolateSettings 把配置来源指向临时目录，避免读到仓库里的 settings.json。
func isolateSettings(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "settings.json")
	t.Setenv("SETTINGS_JSON", path)
	t.Setenv("JWT_SECRET", "test-secret-at-least-32-characters-long")
	return path
}

func TestOAuthDisabledSkipsIssuerValidation(t *testing.T) {
	isolateSettings(t)
	t.Setenv("OAUTH_ENABLED", "false")
	t.Setenv("OAUTH_ISSUER", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("OAuth 关闭时不应因缺少 OAUTH_ISSUER 启动失败: %v", err)
	}
	if cfg.OAuthEnabled {
		t.Fatal("OAuth 应处于关闭状态")
	}
}

func TestOAuthEnabledRequiresHTTPSIssuer(t *testing.T) {
	isolateSettings(t)
	t.Setenv("OAUTH_ENABLED", "true")

	t.Setenv("OAUTH_ISSUER", "")
	if _, err := Load(); err == nil {
		t.Fatal("启用 OAuth 但缺少 OAUTH_ISSUER 时应报错")
	}

	t.Setenv("OAUTH_ISSUER", "http://example.com")
	if _, err := Load(); err == nil {
		t.Fatal("OAUTH_ISSUER 非 https 时应报错")
	}

	t.Setenv("OAUTH_ISSUER", "https://example.com")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("合法的 https issuer 应通过: %v", err)
	}
	if !cfg.OAuthEnabled {
		t.Fatal("OAuth 应处于开启状态")
	}
}

func TestPortDefaultsAndSettingsOverride(t *testing.T) {
	path := isolateSettings(t)
	t.Setenv("PORT", "")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != "8082" {
		t.Fatalf("默认端口应为 8082，实际 %q", cfg.Port)
	}

	body := []byte("{\"port\":\"9000\"}\n")
	if err := os.WriteFile(path, body, 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != "9000" {
		t.Fatalf("settings.json 的端口应生效，实际 %q", cfg.Port)
	}
}
