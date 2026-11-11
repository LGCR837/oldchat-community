package httpapi

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestAPIStandardDocument(t *testing.T) {
	docPath := filepath.Join(t.TempDir(), "api.md")
	content := "# Oldchat API\n\n`/v1`\n"
	if err := os.WriteFile(docPath, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("API_STANDARD_DOC", docPath)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api.md", nil)
	handleAPIStandardDocument(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("API 文档状态码异常：%d", rec.Code)
	}
	if rec.Body.String() != content {
		t.Fatalf("API 文档内容异常：%q", rec.Body.String())
	}
	if got := rec.Header().Get("Content-Type"); got != "text/markdown; charset=utf-8" {
		t.Fatalf("Content-Type 异常：%q", got)
	}
}
