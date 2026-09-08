package httpapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"

	"metrochat/internal/data"
)

const (
	maxFileUploadBytes     int64 = 500 << 20
	maxFileRequestOverhead       = 2 << 20
)

type fileUploadResponse struct {
	FileID       string `json:"file_id"`
	Name         string `json:"name"`
	SizeBytes    int64  `json:"size_bytes"`
	SHA256       string `json:"sha256"`
	URL          string `json:"url"`
	Deduplicated bool   `json:"deduplicated"`
}

type fileCheckRequest struct {
	SHA256    string `json:"sha256"`
	SizeBytes int64  `json:"size_bytes"`
}

type fileCheckResponse struct {
	Exists    bool   `json:"exists"`
	FileID    string `json:"file_id,omitempty"`
	SizeBytes int64  `json:"size_bytes,omitempty"`
	SHA256    string `json:"sha256,omitempty"`
	URL       string `json:"url,omitempty"`
}

func (a *API) handleFileCheckV2(w http.ResponseWriter, r *http.Request) {
	if _, ok := claimsFromContext(r.Context()); !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req fileCheckRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	hash := normalizeSHA256(req.SHA256)
	if hash == "" || req.SizeBytes < 0 || req.SizeBytes > maxFileUploadBytes {
		writeError(w, http.StatusBadRequest, "invalid_file", "invalid file")
		return
	}
	asset, ok := a.findUsableFileAsset(r.Context(), hash, req.SizeBytes)
	if !ok {
		writeJSON(w, http.StatusOK, fileCheckResponse{Exists: false})
		return
	}
	_ = a.fileAssets.MarkUsed(r.Context(), hash)
	writeJSON(w, http.StatusOK, a.fileCheckResponse(asset))
}

func (a *API) handleFileUploadV1(w http.ResponseWriter, r *http.Request) {
	a.handleFileUpload(w, r)
}

func (a *API) handleFileUploadV2(w http.ResponseWriter, r *http.Request) {
	a.handleFileUpload(w, r)
}

// handleFileUpload supports both clients that send a precomputed hash and legacy
// clients that only upload bytes. The server always computes SHA-256 itself.
func (a *API) handleFileUpload(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	sizeHint := parsePositiveInt64(r.Header.Get("X-File-Size"))
	if sizeHint > maxFileUploadBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "file_too_large", "file too large")
		return
	}
	clientHash := strings.TrimSpace(r.Header.Get("X-File-SHA256"))
	if clientHash != "" {
		clientHash = normalizeSHA256(clientHash)
		if clientHash == "" {
			writeError(w, http.StatusBadRequest, "invalid_sha256", "invalid sha256")
			return
		}
		if existing, found := a.findUsableFileAsset(r.Context(), clientHash, sizeHint); found {
			_ = a.fileAssets.MarkUsed(r.Context(), clientHash)
			a.writeFileAssetResponse(w, http.StatusOK, existing, existing.OriginalName, true)
			return
		}
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxFileUploadBytes+maxFileRequestOverhead)
	limitUploadBody(r)
	reader, err := r.MultipartReader()
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_upload", "invalid upload")
		return
	}

	for {
		part, partErr := reader.NextPart()
		if partErr == io.EOF {
			break
		}
		if partErr != nil {
			writeError(w, http.StatusBadRequest, "invalid_upload", "invalid upload")
			return
		}
		if part.FormName() != "file" || part.FileName() == "" {
			_, _ = io.Copy(io.Discard, io.LimitReader(part, 64<<10))
			_ = part.Close()
			continue
		}

		originalName := sanitizeResourceName(part.FileName())
		if originalName == "" {
			originalName = "file"
		}
		ext := sanitizeResourceExt(originalName)
		contentType := strings.TrimSpace(part.Header.Get("Content-Type"))
		if contentType == "" {
			contentType = mime.TypeByExtension(ext)
		}
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		fileID := nanoid.New()
		filename := fileID + ext
		relativePath := "files/" + filename
		dir := filepath.Join(a.cfg.UploadDir, "files")
		if err := os.MkdirAll(dir, 0o755); err != nil {
			_ = part.Close()
			writeError(w, http.StatusInternalServerError, "upload_failed", "upload failed")
			return
		}
		tmp, err := os.CreateTemp(dir, ".upload-*.part")
		if err != nil {
			_ = part.Close()
			writeError(w, http.StatusInternalServerError, "upload_failed", "upload failed")
			return
		}
		tmpPath := tmp.Name()
		cleanup := func() {
			_ = tmp.Close()
			_ = os.Remove(tmpPath)
		}

		hasher := sha256.New()
		release := acquireUploadSlot(false)
		written, copyErr := io.Copy(io.MultiWriter(tmp, hasher), io.LimitReader(part, maxFileUploadBytes+1))
		release()
		_ = part.Close()
		if copyErr != nil {
			cleanup()
			writeError(w, http.StatusInternalServerError, "upload_failed", "upload failed")
			return
		}
		if written <= 0 {
			cleanup()
			writeError(w, http.StatusBadRequest, "invalid_size", "invalid size")
			return
		}
		if written > maxFileUploadBytes {
			cleanup()
			writeError(w, http.StatusRequestEntityTooLarge, "file_too_large", "file too large")
			return
		}
		serverHash := hex.EncodeToString(hasher.Sum(nil))
		if clientHash != "" && clientHash != serverHash {
			cleanup()
			writeError(w, http.StatusBadRequest, "sha256_mismatch", "sha256 mismatch")
			return
		}
		if existing, found := a.findUsableFileAsset(r.Context(), serverHash, written); found {
			cleanup()
			_ = a.fileAssets.MarkUsed(r.Context(), serverHash)
			a.writeFileAssetResponse(w, http.StatusOK, existing, originalName, true)
			return
		}
		if err := tmp.Sync(); err != nil {
			cleanup()
			writeError(w, http.StatusInternalServerError, "upload_failed", "upload failed")
			return
		}
		if err := tmp.Close(); err != nil {
			_ = os.Remove(tmpPath)
			writeError(w, http.StatusInternalServerError, "upload_failed", "upload failed")
			return
		}
		finalPath := filepath.Join(dir, filename)
		if err := os.Rename(tmpPath, finalPath); err != nil {
			_ = os.Remove(tmpPath)
			writeError(w, http.StatusInternalServerError, "upload_failed", "upload failed")
			return
		}

		asset := &data.FileAsset{
			ID: fileID, SHA256: serverHash, RelativePath: relativePath, SizeBytes: written,
			OriginalName: originalName, ContentType: contentType, CreatedBy: claims.Subject,
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		stored, created, storeErr := a.fileAssets.PutIfAbsent(ctx, asset)
		cancel()
		if storeErr != nil {
			_ = os.Remove(finalPath)
			writeError(w, http.StatusInternalServerError, "index_failed", "file index failed")
			return
		}
		if !created {
			_ = os.Remove(finalPath)
			a.writeFileAssetResponse(w, http.StatusOK, stored, originalName, true)
			return
		}
		a.writeFileAssetResponse(w, http.StatusCreated, stored, originalName, false)
		return
	}

	writeError(w, http.StatusBadRequest, "missing_file", "missing file")
}

func (a *API) findUsableFileAsset(ctx context.Context, hash string, sizeHint int64) (*data.FileAsset, bool) {
	if a == nil || a.fileAssets == nil || hash == "" {
		return nil, false
	}
	asset, err := a.fileAssets.GetBySHA256(ctx, hash)
	if err != nil || asset == nil {
		return nil, false
	}
	if sizeHint > 0 && asset.SizeBytes != sizeHint {
		return nil, false
	}
	if _, err := os.Stat(filepath.Join(a.cfg.UploadDir, filepath.FromSlash(asset.RelativePath))); err != nil {
		return nil, false
	}
	return asset, true
}

func (a *API) fileCheckResponse(asset *data.FileAsset) fileCheckResponse {
	if asset == nil {
		return fileCheckResponse{Exists: false}
	}
	return fileCheckResponse{Exists: true, FileID: asset.ID, SizeBytes: asset.SizeBytes,
		SHA256: asset.SHA256, URL: a.fileAssetURL(asset.ID)}
}

func (a *API) writeFileAssetResponse(w http.ResponseWriter, status int, asset *data.FileAsset, displayName string, deduplicated bool) {
	if asset == nil {
		writeError(w, http.StatusInternalServerError, "index_failed", "file index failed")
		return
	}
	if strings.TrimSpace(displayName) == "" {
		displayName = asset.OriginalName
	}
	writeJSON(w, status, fileUploadResponse{FileID: asset.ID, Name: displayName,
		SizeBytes: asset.SizeBytes, SHA256: asset.SHA256, URL: a.fileAssetURL(asset.ID), Deduplicated: deduplicated})
}

func normalizeSHA256(raw string) string {
	value := strings.ToLower(strings.TrimSpace(raw))
	if len(value) != sha256.Size*2 {
		return ""
	}
	if _, err := hex.DecodeString(value); err != nil {
		return ""
	}
	return value
}

func (a *API) fileAssetURL(fileID string) string {
	base := "http://files.mcl0.dpdns.org"
	if a != nil && strings.TrimSpace(a.cfg.FilesBaseURL) != "" {
		base = strings.TrimRight(strings.TrimSpace(a.cfg.FilesBaseURL), "/")
	}
	return base + "/v2/files/download/" + strings.TrimSpace(fileID)
}

// fileAssetURLWithRef 在下载地址后附加对象相对路径（ref），供客户端拼接对象存储直链。
func (a *API) fileAssetURLWithRef(fileID, relativePath string) string {
	url := a.fileAssetURL(fileID)
	rel := strings.TrimPrefix(strings.TrimSpace(relativePath), "/")
	if rel == "" {
		return url
	}
	return url + "?ref=" + urlQueryEscape(rel)
}

func (a *API) resourceItemURL(itemID string) string {
	base := "http://files.mcl0.dpdns.org"
	if a != nil && strings.TrimSpace(a.cfg.FilesBaseURL) != "" {
		base = strings.TrimRight(strings.TrimSpace(a.cfg.FilesBaseURL), "/")
	}
	return base + "/v2/resources/download/" + strings.TrimSpace(itemID)
}

// resourceItemURLWithRef 在下载地址后附加对象相对路径（ref），供客户端拼接对象存储直链。
func (a *API) resourceItemURLWithRef(itemID, relativePath string) string {
	url := a.resourceItemURL(itemID)
	rel := strings.TrimPrefix(strings.TrimSpace(relativePath), "/")
	if rel == "" {
		return url
	}
	return url + "?ref=" + urlQueryEscape(rel)
}

func (a *API) resourcePublicURL(raw string) string {
	if relativePath, ok := uploadURLToRelativePath(raw); ok {
		return a.filePublicURL(relativePath)
	}
	return strings.TrimSpace(raw)
}

func (a *API) filePublicURL(relativePath string) string {
	path := strings.TrimLeft(strings.TrimSpace(relativePath), "/")
	base := "http://files.mcl0.dpdns.org"
	if a != nil && strings.TrimSpace(a.cfg.FilesBaseURL) != "" {
		base = strings.TrimRight(strings.TrimSpace(a.cfg.FilesBaseURL), "/")
	}
	return base + "/" + path
}

func parsePositiveInt64(raw string) int64 {
	var n int64
	for _, c := range strings.TrimSpace(raw) {
		if c < '0' || c > '9' {
			return 0
		}
		if n > (1<<63-1-int64(c-'0'))/10 {
			return 0
		}
		n = n*10 + int64(c-'0')
	}
	return n
}

func withFileDownloadHeaders(next http.Handler) http.Handler {
	if next == nil {
		return http.NotFoundHandler()
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			w.Header().Set("Allow", "GET, HEAD")
			writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
			return
		}
		setPrivateFileHeaders(w)
		w.Header().Set("Content-Disposition", "attachment")
		next.ServeHTTP(w, r)
	})
}
