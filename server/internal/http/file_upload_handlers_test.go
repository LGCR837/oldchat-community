package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"
	_ "modernc.org/sqlite"

	"metrochat/internal/auth"
	"metrochat/internal/config"
	"metrochat/internal/data"
)

func TestHandleFileUploadV2StoresFileAndReturnsFilesDomain(t *testing.T) {
	uploadDir := t.TempDir()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	store := data.NewFileAssetStore(db)
	if err := store.EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	api := &API{cfg: config.Config{
		UploadDir:    uploadDir,
		FilesBaseURL: "https://files.mcl0.dpdns.org",
	}, fileAssets: store}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", "hello.txt")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v2/files/upload", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	claims := &auth.AccessClaims{RegisteredClaims: jwt.RegisteredClaims{Subject: "user-1"}}
	req = req.WithContext(context.WithValue(req.Context(), claimsKey, claims))
	recorder := httptest.NewRecorder()

	api.handleFileUploadV2(recorder, req)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	var response fileUploadResponse
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SizeBytes != 5 || response.Name != "hello.txt" {
		t.Fatalf("unexpected response: %+v", response)
	}
	if !strings.HasPrefix(response.URL, "https://files.mcl0.dpdns.org/v2/files/download/") {
		t.Fatalf("unexpected url: %s", response.URL)
	}
	asset, err := store.GetBySHA256(context.Background(), response.SHA256)
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(uploadDir, filepath.FromSlash(asset.RelativePath)))
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != "hello" {
		t.Fatalf("stored data=%q", data)
	}
	const helloSHA = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
	if response.SHA256 != helloSHA || response.Deduplicated {
		t.Fatalf("unexpected hash/dedup response: %+v", response)
	}

	checkReq := httptest.NewRequest(http.MethodPost, "/v2/files/check",
		strings.NewReader(`{"sha256":"`+helloSHA+`","size_bytes":5}`))
	checkReq.Header.Set("Content-Type", "application/json")
	checkReq = checkReq.WithContext(context.WithValue(checkReq.Context(), claimsKey, claims))
	checkRecorder := httptest.NewRecorder()
	api.handleFileCheckV2(checkRecorder, checkReq)
	if checkRecorder.Code != http.StatusOK || !strings.Contains(checkRecorder.Body.String(), `"exists":true`) {
		t.Fatalf("check status=%d body=%s", checkRecorder.Code, checkRecorder.Body.String())
	}

	var duplicateBody bytes.Buffer
	duplicateWriter := multipart.NewWriter(&duplicateBody)
	duplicatePart, err := duplicateWriter.CreateFormFile("file", "copy.txt")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = duplicatePart.Write([]byte("hello"))
	_ = duplicateWriter.Close()
	duplicateReq := httptest.NewRequest(http.MethodPost, "/v1/files/upload", &duplicateBody)
	duplicateReq.Header.Set("Content-Type", duplicateWriter.FormDataContentType())
	duplicateReq = duplicateReq.WithContext(context.WithValue(duplicateReq.Context(), claimsKey, claims))
	duplicateRecorder := httptest.NewRecorder()
	api.handleFileUploadV1(duplicateRecorder, duplicateReq)
	if duplicateRecorder.Code != http.StatusOK {
		t.Fatalf("duplicate status=%d body=%s", duplicateRecorder.Code, duplicateRecorder.Body.String())
	}
	var duplicateResponse fileUploadResponse
	if err := json.Unmarshal(duplicateRecorder.Body.Bytes(), &duplicateResponse); err != nil {
		t.Fatal(err)
	}
	if !duplicateResponse.Deduplicated || duplicateResponse.URL != response.URL {
		t.Fatalf("duplicate response=%+v original=%+v", duplicateResponse, response)
	}
	entries, err := os.ReadDir(filepath.Join(uploadDir, "files"))
	if err != nil || len(entries) != 1 {
		t.Fatalf("file entries=%d err=%v", len(entries), err)
	}
}

func TestHandleFileUploadV2RejectsOversizeHint(t *testing.T) {
	api := &API{cfg: config.Config{UploadDir: t.TempDir()}}
	req := httptest.NewRequest(http.MethodPost, "/v2/files/upload", strings.NewReader(""))
	req.Header.Set("X-File-Size", "524288001")
	claims := &auth.AccessClaims{RegisteredClaims: jwt.RegisteredClaims{Subject: "user-1"}}
	req = req.WithContext(context.WithValue(req.Context(), claimsKey, claims))
	recorder := httptest.NewRecorder()

	api.handleFileUploadV2(recorder, req)
	if recorder.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}
