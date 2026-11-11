package httpapi

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestNonAdminRoutesDocument(t *testing.T) {
	docPath := filepath.Join(t.TempDir(), "routes.md")
	content := "# Routes\n\nGET /routes.md\n"
	if err := os.WriteFile(docPath, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("NON_ADMIN_ROUTES_DOC", docPath)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/routes.md", nil)

	handleNonAdminRoutesDocument(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("状态码异常：%d", rec.Code)
	}
	if rec.Body.String() != content {
		t.Fatalf("文档内容异常：%q", rec.Body.String())
	}
	if !strings.HasPrefix(rec.Header().Get("Content-Type"), "text/markdown") {
		t.Fatalf("Content-Type 异常：%q", rec.Header().Get("Content-Type"))
	}
}
