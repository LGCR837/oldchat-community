package httpapi

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/go-chi/chi/v5"
	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"

	"metrochat/internal/config"
	"metrochat/internal/data"
)

func createTestCIPZip(t *testing.T, id, name string, version int) []byte {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)

	manifestObj := map[string]interface{}{
		"id":          id,
		"name":        name,
		"description": "测试小程序描述",
		"version":     version,
		"permissions": []string{"storage"},
	}
	manifestBytes, _ := json.Marshal(manifestObj)
	mw, err := zw.Create("manifest.json")
	if err != nil {
		t.Fatalf("failed to create manifest in zip: %v", err)
	}
	_, _ = mw.Write(manifestBytes)

	mainLua := []byte(`return ui.page({ title = "Test", children = { ui.text({ text = "Hello" }) } })`)
	lw, err := zw.Create("main.lua")
	if err != nil {
		t.Fatalf("failed to create main.lua in zip: %v", err)
	}
	_, _ = lw.Write(mainLua)

	if err := zw.Close(); err != nil {
		t.Fatalf("failed to close zip: %v", err)
	}
	return buf.Bytes()
}

func setupTestAPIForCIP(t *testing.T) (*API, *sqlx.DB, string) {
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatalf("open sqlite: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	uploadDir := t.TempDir()
	luaDir := t.TempDir()

	cipStore := data.NewCipStore(db)
	if err := cipStore.EnsureTable(t.Context()); err != nil {
		t.Fatalf("ensure table: %v", err)
	}

	api := &API{
		cfg:       config.Config{UploadDir: uploadDir},
		db:        db,
		cipStore:  cipStore,
		luaAppDir: luaDir,
	}
	return api, db, uploadDir
}

func TestCipUploadAndStoreFlow(t *testing.T) {
	api, _, _ := setupTestAPIForCIP(t)

	// 1. GET /cip-upload
	req := httptest.NewRequest(http.MethodGet, "/cip-upload", nil)
	rec := httptest.NewRecorder()
	api.handleCipUploadPage(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("handleCipUploadPage status = %d, want 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "OldChat CIP 小程序") {
		t.Fatalf("handleCipUploadPage body unexpected: %s", rec.Body.String())
	}

	// 2. POST /cip-upload with valid .cip
	cipBytes := createTestCIPZip(t, "calculator_app", "简易计算器", 1)
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	_ = mw.WriteField("author", "测试作者小李")
	_ = mw.WriteField("notes", "提交测试")
	fw, err := mw.CreateFormFile("cip_file", "calculator_app.cip")
	if err != nil {
		t.Fatalf("create form file: %v", err)
	}
	_, _ = fw.Write(cipBytes)
	_ = mw.Close()

	req = httptest.NewRequest(http.MethodPost, "/cip-upload", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	rec = httptest.NewRecorder()
	api.handleCipUploadSubmit(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("handleCipUploadSubmit status = %d, want 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "上传成功") {
		t.Fatalf("handleCipUploadSubmit body unexpected: %s", rec.Body.String())
	}

	// 3. GET /v1/cip/store (should be empty initially because status is pending)
	req = httptest.NewRequest(http.MethodGet, "/v1/cip/store", nil)
	rec = httptest.NewRecorder()
	api.handleCipStoreList(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("handleCipStoreList status = %d, want 200", rec.Code)
	}
	var storeResp struct {
		Items []data.CipStoreItem `json:"items"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &storeResp); err != nil {
		t.Fatalf("unmarshal store list: %v", err)
	}
	if len(storeResp.Items) != 0 {
		t.Fatalf("expected 0 approved items, got %d", len(storeResp.Items))
	}

	// 4. Admin Review: Approve the app
	form := url.Values{
		"id":     {"calculator_app"},
		"action": {"approve"},
		"reason": {"审核通过"},
	}
	req = httptest.NewRequest(http.MethodPost, "/admins/moderation/cip/review", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec = httptest.NewRecorder()
	api.handleAdminModerationCipReview(rec, req)
	if rec.Code != http.StatusSeeOther {
		t.Fatalf("handleAdminModerationCipReview status = %d, want 303", rec.Code)
	}

	// 5. GET /v1/cip/store (now should contain 1 item)
	req = httptest.NewRequest(http.MethodGet, "/v1/cip/store", nil)
	rec = httptest.NewRecorder()
	api.handleCipStoreList(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("handleCipStoreList status = %d, want 200", rec.Code)
	}
	storeResp.Items = nil
	if err := json.Unmarshal(rec.Body.Bytes(), &storeResp); err != nil {
		t.Fatalf("unmarshal store list: %v", err)
	}
	if len(storeResp.Items) != 1 {
		t.Fatalf("expected 1 approved item, got %d", len(storeResp.Items))
	}
	item := storeResp.Items[0]
	if item.ID != "calculator_app" || item.Name != "简易计算器" || item.AuthorName != "测试作者小李" {
		t.Fatalf("item mismatch: %+v", item)
	}

	// 6. Download the CIP file: GET /v1/cip/store/download/calculator_app
	r := chiRouterForTest(api)
	req = httptest.NewRequest(http.MethodGet, "/v1/cip/store/download/calculator_app", nil)
	rec = httptest.NewRecorder()
	r.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("download status = %d, want 200", rec.Code)
	}
	downloaded, _ := io.ReadAll(rec.Body)
	if len(downloaded) != len(cipBytes) {
		t.Fatalf("downloaded len = %d, want %d", len(downloaded), len(cipBytes))
	}
}

func chiRouterForTest(api *API) http.Handler {
	r := chi.NewRouter()
	r.Get("/v1/cip/store/download/{id}", api.handleCipStoreDownload)
	return r
}
