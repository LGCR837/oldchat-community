package httpapi

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestOAuthLoginDocument(t *testing.T) {
	path := filepath.Join(t.TempDir(), "oauth.md")
	content := "# OAuth 2.1\n\n`/oauth/token`\n"
	if err := os.WriteFile(path, []byte(content), 0600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("OAUTH_LOGIN_DOC", path)
	rec := httptest.NewRecorder()
	handleOAuthLoginDocument(rec, httptest.NewRequest(http.MethodGet, "/oauth.md", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("状态码异常: %d", rec.Code)
	}
	if rec.Body.String() != content {
		t.Fatalf("内容异常: %q", rec.Body.String())
	}
	if rec.Header().Get("Content-Type") != "text/markdown; charset=utf-8" {
		t.Fatal("Content-Type 异常")
	}
}
