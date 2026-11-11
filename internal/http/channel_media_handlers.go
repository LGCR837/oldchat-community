package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"mime"
	"net/http"
	neturl "net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"
	"github.com/go-chi/chi/v5"
)

const channelMediaRefPrefix = "channel-private:"
const channelMediaMaxBytes = 50 << 20

var errUnsupportedMediaType = errors.New("unsupported channel media type")
var errMediaTooLarge = errors.New("channel media exceeds 50MB")

func loadOrCreateChannelMediaKey(uploadDir string) []byte {
	keyDir := filepath.Join(uploadDir, "keys")
	_ = os.MkdirAll(keyDir, 0o700)
	keyPath := filepath.Join(keyDir, "channel_media_hmac.key")
	if existing, err := os.ReadFile(keyPath); err == nil && len(existing) >= 32 {
		return existing[:32]
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return []byte("oldchat-channel-media-runtime-fallback")
	}
	_ = os.WriteFile(keyPath, key, 0o600)
	return key
}

func (a *API) channelMediaDir() string {
	return filepath.Clean(filepath.Join(a.cfg.UploadDir, "..", "channel-media-private"))
}

func (a *API) handleChannelMediaUpload(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, channelMediaMaxBytes+(2<<20))
	if err := r.ParseMultipartForm(channelMediaMaxBytes + (2 << 20)); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_upload", "invalid upload")
		return
	}
	channelID := strings.TrimSpace(r.FormValue("channel_id"))
	canPost, err := a.channels.CanPost(r.Context(), channelID, claims.Subject)
	if err != nil || !canPost {
		writeError(w, http.StatusForbidden, "cannot_post", "no channel post permission")
		return
	}
	ref, msgType, size, err := a.saveChannelMediaFile(r)
	if err != nil {
		writeError(w, http.StatusBadRequest, "upload_failed", err.Error())
		return
	}
	writeChannelJSON(w, map[string]interface{}{"media_ref": ref, "msg_type": msgType, "size": size})
}

func (a *API) saveChannelMediaFile(r *http.Request) (string, string, int64, error) {
	file, header, err := r.FormFile("media")
	if err != nil {
		file, header, err = r.FormFile("file")
	}
	if err != nil {
		return "", "", 0, err
	}
	defer file.Close()
	contentType := strings.ToLower(strings.TrimSpace(header.Header.Get("Content-Type")))
	if contentType == "" {
		contentType = mime.TypeByExtension(filepath.Ext(header.Filename))
	}
	ext, err := mediaExtFromType(contentType)
	if err != nil {
		return "", "", 0, err
	}
	msgType := ""
	if strings.HasPrefix(contentType, "image/") {
		msgType = "image"
	} else if strings.HasPrefix(contentType, "audio/") {
		msgType = "voice"
	} else if strings.HasPrefix(contentType, "video/") {
		msgType = "video"
	} else {
		return "", "", 0, errUnsupportedMediaType
	}
	if err = os.MkdirAll(a.channelMediaDir(), 0o750); err != nil {
		return "", "", 0, err
	}
	filename := "cm-" + nanoid.New() + ext
	path := filepath.Join(a.channelMediaDir(), filename)
	dst, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		return "", "", 0, err
	}
	written, copyErr := io.Copy(dst, io.LimitReader(file, channelMediaMaxBytes+1))
	closeErr := dst.Close()
	if copyErr != nil || closeErr != nil || written <= 0 || written > channelMediaMaxBytes {
		_ = os.Remove(path)
		if copyErr != nil {
			return "", "", 0, copyErr
		}
		return "", "", 0, errMediaTooLarge
	}
	return channelMediaRefPrefix + filename, msgType, written, nil
}

func (a *API) validChannelMediaRef(ref string) bool {
	if ref == "" {
		return true
	}
	if strings.HasPrefix(ref, channelMediaRefPrefix) {
		filename := strings.TrimPrefix(ref, channelMediaRefPrefix)
		if filepath.Base(filename) != filename {
			return false
		}
		_, err := os.Stat(filepath.Join(a.channelMediaDir(), filename))
		return err == nil
	}
	return a.validChannelFileRef(ref)
}

// validChannelFileRef accepts an uploaded shared file URL (/v2/files/download/{id})
// and verifies the underlying asset still exists, so channel posts can reference chat files.
// 不绑定具体域名：只要路径落在文件下载接口上就接受，社区版换域名后依旧生效。
func (a *API) validChannelFileRef(ref string) bool {
	const filePathPrefix = "/v2/files/download/"
	path := strings.TrimSpace(ref)
	if u, err := neturl.Parse(path); err == nil && u.Path != "" {
		path = u.Path
	}
	if !strings.HasPrefix(path, filePathPrefix) {
		return false
	}
	id := strings.TrimPrefix(path, filePathPrefix)
	id = strings.TrimSpace(id)
	if id == "" || strings.ContainsAny(id, "/?&#") {
		return false
	}
	asset, err := a.fileAssets.GetByID(context.Background(), id)
	return err == nil && asset != nil
}

func (a *API) signedChannelMediaURL(ref string) string {
	if !strings.HasPrefix(ref, channelMediaRefPrefix) {
		return ref
	}
	filename := strings.TrimPrefix(ref, channelMediaRefPrefix)
	if filepath.Base(filename) != filename {
		return ""
	}
	expDuration := 24 * time.Hour
	if strings.HasPrefix(filename, "ca-") {
		expDuration = 30 * 24 * time.Hour
	}
	exp := time.Now().Add(expDuration).Unix()
	payload := filename + "|" + strconv.FormatInt(exp, 10)
	mac := hmac.New(sha256.New, a.channelMediaKey)
	_, _ = mac.Write([]byte(payload))
	sig := hex.EncodeToString(mac.Sum(nil))
	return "/channel-media/" + filename + "?exp=" + strconv.FormatInt(exp, 10) + "&sig=" + sig
}

func (a *API) handleChannelMediaDownload(w http.ResponseWriter, r *http.Request) {
	filename := chi.URLParam(r, "filename")
	if filename == "" || filepath.Base(filename) != filename {
		http.NotFound(w, r)
		return
	}
	exp, err := strconv.ParseInt(r.URL.Query().Get("exp"), 10, 64)
	if err != nil || time.Now().Unix() > exp {
		writeError(w, http.StatusUnauthorized, "media_url_expired", "media URL expired")
		return
	}
	payload := filename + "|" + strconv.FormatInt(exp, 10)
	mac := hmac.New(sha256.New, a.channelMediaKey)
	_, _ = mac.Write([]byte(payload))
	expected := mac.Sum(nil)
	provided, err := hex.DecodeString(r.URL.Query().Get("sig"))
	if err != nil || !hmac.Equal(expected, provided) {
		writeError(w, http.StatusForbidden, "invalid_media_signature", "invalid media signature")
		return
	}
	path := filepath.Join(a.channelMediaDir(), filename)
	if _, err = os.Stat(path); err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeFile(w, r, path)
}
