package httpapi

import (
	"bytes"
	"compress/gzip"
	"io"
	"net/http"

	"metrochat/internal/secure"
)

const encHeader = "X-Enc"
const sessionHeader = "X-Session"
const encCompressionHeader = "X-Enc-Compression"
const xAuthHeader = "X-Auth"
const minEncryptedCompressionBytes = 512

type bufferWriter struct {
	header http.Header
	status int
	buf    bytes.Buffer
}

func newBufferWriter() *bufferWriter {
	return &bufferWriter{header: make(http.Header)}
}

func (w *bufferWriter) Header() http.Header {
	return w.header
}

func (w *bufferWriter) WriteHeader(statusCode int) {
	w.status = statusCode
}

func (w *bufferWriter) Write(p []byte) (int, error) {
	return w.buf.Write(p)
}

func (a *API) secureMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 大文件 multipart 必须保持流式，禁止进入下方 io.ReadAll 整包解密。
		// 两个端点仍由后续 authMiddleware 校验 Bearer JWT。
		if isStreamingFileRequest(r) {
			next.ServeHTTP(w, r)
			return
		}
		if r.Header.Get(encHeader) != "1" {
			next.ServeHTTP(w, r)
			return
		}

		sessionID := r.Header.Get(sessionHeader)
		if sessionID == "" {
			writeError(w, http.StatusBadRequest, "missing_session", "missing session")
			return
		}
		keys, ok := a.sessions.Get(sessionID)
		if !ok {
			writeError(w, http.StatusBadRequest, "invalid_session", "invalid session")
			return
		}

		if r.ContentLength > 0 {
			raw, err := io.ReadAll(r.Body)
			if err != nil {
				writeError(w, http.StatusBadRequest, "bad_body", "invalid body")
				return
			}
			_ = r.Body.Close()
			plain, err := secure.DecryptWithKeys(raw, keys.EncKey, keys.MacKey)
			if err != nil {
				writeError(w, http.StatusBadRequest, "bad_encryption", "invalid payload")
				return
			}
			r.Body = io.NopCloser(bytes.NewReader(plain))
			r.ContentLength = int64(len(plain))
			r.Header.Set("Content-Type", "application/json")
		}

		// X-Auth：加密的 access token（同一会话密钥）。
		// 新客户端不再发明文 Authorization，token 也进密文，抓包完全看不到。
		// 解密成功后覆盖 Authorization 头，供 authMiddleware 使用。
		if xAuth := r.Header.Get(xAuthHeader); xAuth != "" {
			plainAuth, err := secure.DecryptWithKeys([]byte(xAuth), keys.EncKey, keys.MacKey)
			if err != nil {
				writeError(w, http.StatusBadRequest, "bad_xauth", "invalid x-auth")
				return
			}
			r.Header.Set("Authorization", string(plainAuth))
		}

		rec := newBufferWriter()
		next.ServeHTTP(rec, r)

		status := rec.status
		if status == 0 {
			status = http.StatusOK
		}
		plainResponse := rec.buf.Bytes()
		compressed := false
		if r.Header.Get(encCompressionHeader) == "gzip" && len(plainResponse) >= minEncryptedCompressionBytes {
			var compressedBuffer bytes.Buffer
			zipWriter, zipErr := gzip.NewWriterLevel(&compressedBuffer, gzip.BestSpeed)
			if zipErr == nil {
				if _, zipErr = zipWriter.Write(plainResponse); zipErr == nil {
					zipErr = zipWriter.Close()
				} else {
					_ = zipWriter.Close()
				}
			}
			if zipErr == nil && compressedBuffer.Len() < len(plainResponse) {
				plainResponse = compressedBuffer.Bytes()
				compressed = true
			}
		}
		enc, err := secure.EncryptWithKeys(plainResponse, keys.EncKey, keys.MacKey)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "encrypt_failed", "internal error")
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set(encHeader, "1")
		w.Header().Set(sessionHeader, sessionID)
		if compressed {
			w.Header().Set(encCompressionHeader, "gzip")
		}
		w.WriteHeader(status)
		_, _ = w.Write(enc)
	})
}
