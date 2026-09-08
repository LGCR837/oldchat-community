package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/go-chi/chi/v5"
	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"

	"metrochat/internal/config"
	"metrochat/internal/data"
)

func TestFileAssetDownloadSupportsPrivateRange(t *testing.T) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	store := data.NewFileAssetStore(db)
	if err := store.EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "files"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "files", "asset.txt"), []byte("hello world"), 0o644); err != nil {
		t.Fatal(err)
	}
	asset := &data.FileAsset{ID: "asset-1", SHA256: "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
		RelativePath: "files/asset.txt", SizeBytes: 11, OriginalName: "hello.txt", ContentType: "text/plain", CreatedBy: "u1"}
	if _, _, err := store.PutIfAbsent(context.Background(), asset); err != nil {
		t.Fatal(err)
	}
	api := &API{cfg: config.Config{UploadDir: dir}, fileAssets: store}
	req := httptest.NewRequest(http.MethodGet, "/v2/files/download/asset-1", nil)
	req.Header.Set("Range", "bytes=1-3")
	rctx := chi.NewRouteContext()
	rctx.URLParams.Add("fileID", "asset-1")
	req = req.WithContext(context.WithValue(req.Context(), chi.RouteCtxKey, rctx))
	recorder := httptest.NewRecorder()
	api.handleFileAssetDownload(recorder, req)
	if recorder.Code != http.StatusPartialContent {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	if recorder.Body.String() != "ell" {
		t.Fatalf("body=%q", recorder.Body.String())
	}
	if recorder.Header().Get("Cache-Control") != "private, no-store" {
		t.Fatalf("cache=%q", recorder.Header().Get("Cache-Control"))
	}
	if recorder.Header().Get("Content-Range") != "bytes 1-3/11" {
		t.Fatalf("range=%q", recorder.Header().Get("Content-Range"))
	}
}

func TestSafeAssetPathRejectsTraversal(t *testing.T) {
	if _, ok := safeAssetPath(t.TempDir(), "files/../../secret"); ok {
		t.Fatal("traversal accepted")
	}
	if _, ok := safeAssetPath(t.TempDir(), "/etc/passwd"); ok {
		t.Fatal("absolute path accepted")
	}
}
