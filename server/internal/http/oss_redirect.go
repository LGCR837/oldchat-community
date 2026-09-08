package httpapi

import (
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"metrochat/internal/oss"
)

var ossBackfillInFlight sync.Map

// handleOSSDownload 为私有 OSS 对象签发短时 URL，并让客户端直接从 OSS 下载。
func (a *API) handleOSSDownload(w http.ResponseWriter, r *http.Request) {
	if a == nil || a.ossClient == nil || !a.ossClient.IsEnabled() {
		http.NotFound(w, r)
		return
	}
	relPath := strings.TrimPrefix(r.URL.Path, "/oss/")
	relPath = strings.TrimSpace(relPath)
	if relPath == "" || strings.HasPrefix(relPath, "../") || strings.Contains(relPath, "/../") {
		http.NotFound(w, r)
		return
	}
	signedURL, err := a.ossClient.SignedDownloadURL(relPath, 10*60)
	if err != nil {
		log.Printf("[OSS] 生成下载地址失败 path=%s err=%v", relPath, err)
		http.Redirect(w, r, "/v1/uploads/"+relPath, http.StatusTemporaryRedirect)
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=300")
	http.Redirect(w, r, signedURL, http.StatusTemporaryRedirect)
}

// ossRedirectHandler 保留服务器本地文件响应，并在旧资源被访问时后台补传 OSS。
// 它不做 HTTP 重定向，因此旧客户端和 OSS 故障时仍可从主服务器回源。
func ossRedirectHandler(uploadDir string, ossClient *oss.Client, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if (r.Method == http.MethodGet || r.Method == http.MethodHead) && ossClient != nil && ossClient.IsEnabled() {
			if relPath, ok := uploadURLToRelativePath(r.URL.Path); ok {
				filePath := filepath.Join(uploadDir, filepath.FromSlash(relPath))
				if needsOSSUpload(filePath+".oss", ossClient.PublicURL(relPath)) {
					if _, loaded := ossBackfillInFlight.LoadOrStore(relPath, struct{}{}); !loaded {
						go func() {
							defer ossBackfillInFlight.Delete(relPath)
							if err := uploadLocalFileToOSS(ossClient, filePath, relPath); err != nil {
								log.Printf("[OSS] 旧资源补传失败 path=%s err=%v", relPath, err)
							}
						}()
					}
				}
			}
		}
		next.ServeHTTP(w, r)
	})
}

// pushUploadToOSS 将新上传文件同步到 OSS。数据库继续保存相对 URL，以兼容旧客户端。
func (a *API) pushUploadToOSS(uploadURL string) error {
	if a == nil || a.ossClient == nil || !a.ossClient.IsEnabled() {
		return nil
	}
	relPath, ok := uploadURLToRelativePath(uploadURL)
	if !ok {
		return nil
	}
	filePath := filepath.Join(a.cfg.UploadDir, filepath.FromSlash(relPath))
	return uploadLocalFileToOSS(a.ossClient, filePath, relPath)
}

func uploadLocalFileToOSS(client *oss.Client, filePath, relPath string) error {
	info, err := os.Stat(filePath)
	if err != nil {
		return err
	}
	if info.IsDir() {
		return nil
	}
	publicURL, err := client.UploadFile(relPath, filePath)
	if err != nil {
		return err
	}
	if err := writeOSSMarker(filePath+".oss", "oss-v2:"+publicURL); err != nil {
		return err
	}
	log.Printf("[OSS] 上传成功 path=%s bytes=%d", relPath, info.Size())
	return nil
}

func needsOSSUpload(markerPath, expectedPublicURL string) bool {
	data, err := os.ReadFile(markerPath)
	if err != nil {
		return true
	}
	// 标记包含目标 Bucket URL；切换 Bucket 后会自动重新补传，而不是误用旧标记。
	return strings.TrimSpace(string(data)) != "oss-v2:"+strings.TrimSpace(expectedPublicURL)
}

func writeOSSMarker(markerPath, publicURL string) error {
	dir := filepath.Dir(markerPath)
	tmp, err := os.CreateTemp(dir, ".oss-marker-*")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if _, err := tmp.WriteString(strings.TrimSpace(publicURL)); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o644); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpPath, markerPath)
}
