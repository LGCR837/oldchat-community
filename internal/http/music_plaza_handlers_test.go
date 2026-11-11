package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"path/filepath"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"

	"metrochat/internal/auth"
	"metrochat/internal/config"
	"metrochat/internal/data"
)

func newMusicPlazaHandlerTestAPI(t *testing.T) (*API, *sqlx.DB, string) {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	for _, stmt := range []string{
		`CREATE TABLE users (
			id TEXT PRIMARY KEY, uid TEXT NOT NULL, display_name TEXT NOT NULL DEFAULT '',
			user_title TEXT NOT NULL DEFAULT '', avatar_url TEXT NOT NULL DEFAULT '')`,
		`CREATE TABLE music_plaza_items (
			id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, name TEXT NOT NULL,
			song_url TEXT NOT NULL, cover_url TEXT NOT NULL DEFAULT '', lyrics_url TEXT NOT NULL DEFAULT '',
			size_bytes INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0,
			play_count INTEGER NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`,
		`INSERT INTO users(id, uid, display_name) VALUES ('u1', '10001', '测试用户')`,
	} {
		if _, err = db.Exec(stmt); err != nil {
			t.Fatal(err)
		}
	}
	uploadDir := t.TempDir()
	t.Cleanup(func() { _ = db.Close() })
	return &API{
		cfg:        config.Config{UploadDir: uploadDir},
		musicPlaza: data.NewMusicPlazaStore(db),
	}, db, uploadDir
}

func TestHandleMusicPlazaUpdateNameAndCover(t *testing.T) {
	api, db, uploadDir := newMusicPlazaHandlerTestAPI(t)
	oldDir := filepath.Join(uploadDir, "media")
	if err := os.MkdirAll(oldDir, 0o755); err != nil {
		t.Fatal(err)
	}
	oldPath := filepath.Join(oldDir, "old.jpg")
	if err := os.WriteFile(oldPath, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO music_plaza_items(id, owner_id, name, song_url, cover_url)
		VALUES ('m1', 'u1', '旧名称', '/v1/uploads/music/a.mp3', '/v1/uploads/media/old.jpg')`); err != nil {
		t.Fatal(err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	filePart, err := writer.CreateFormFile("file", "meta.txt")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = filePart.Write(nil)
	header := make(textproto.MIMEHeader)
	header.Set("Content-Disposition", `form-data; name="thumb"; filename="cover.jpg"`)
	header.Set("Content-Type", "image/jpeg")
	coverPart, err := writer.CreatePart(header)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = coverPart.Write([]byte("new-cover"))
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/music/plaza/update?item_id=m1&name=%E6%96%B0%E5%90%8D%E7%A7%B0", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	claims := &auth.AccessClaims{UID: "10001", RegisteredClaims: jwt.RegisteredClaims{Subject: "u1"}}
	req = req.WithContext(context.WithValue(req.Context(), claimsKey, claims))
	rec := httptest.NewRecorder()

	api.handleMusicPlazaUpdate(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("预期 200，实际 %d：%s", rec.Code, rec.Body.String())
	}
	var response musicPlazaUpdateResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.ID != "m1" || response.Name != "新名称" || response.CoverURL == "" {
		t.Fatalf("响应异常：%+v", response)
	}
	var name, cover string
	if err := db.QueryRow(`SELECT name, cover_url FROM music_plaza_items WHERE id = 'm1'`).Scan(&name, &cover); err != nil {
		t.Fatal(err)
	}
	if name != "新名称" || cover == "" || cover == "/v1/uploads/media/old.jpg" {
		t.Fatalf("数据库未正确更新：name=%q cover=%q", name, cover)
	}
	if _, err := os.Stat(oldPath); !os.IsNotExist(err) {
		t.Fatalf("旧封面未删除：%v", err)
	}
	if _, err := os.Stat(musicUploadedFilePath(uploadDir, cover)); err != nil {
		t.Fatalf("新封面未保存：%v", err)
	}
}
