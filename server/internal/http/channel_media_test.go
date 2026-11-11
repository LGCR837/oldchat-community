package httpapi

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"metrochat/internal/config"

	"github.com/go-chi/chi/v5"
)

func TestChannelMediaRequiresValidSignedURL(t *testing.T) {
	uploadDir := filepath.Join(t.TempDir(), "uploads")
	api := &API{cfg: config.Config{UploadDir: uploadDir}, channelMediaKey: []byte("01234567890123456789012345678901")}
	if err := os.MkdirAll(api.channelMediaDir(), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(api.channelMediaDir(), "test.jpg"), []byte("private-channel-media"), 0o640); err != nil {
		t.Fatal(err)
	}
	router := chi.NewRouter()
	router.Get("/channel-media/{filename}", api.handleChannelMediaDownload)

	unsigned := httptest.NewRecorder()
	router.ServeHTTP(unsigned, httptest.NewRequest(http.MethodGet, "/channel-media/test.jpg", nil))
	if unsigned.Code == http.StatusOK {
		t.Fatal("unsigned media URL was accepted")
	}

	signedPath := api.signedChannelMediaURL(channelMediaRefPrefix + "test.jpg")
	signed := httptest.NewRecorder()
	router.ServeHTTP(signed, httptest.NewRequest(http.MethodGet, signedPath, nil))
	if signed.Code != http.StatusOK || signed.Body.String() != "private-channel-media" {
		t.Fatalf("signed media response code=%d body=%q", signed.Code, signed.Body.String())
	}
}
