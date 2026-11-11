package httpapi

import (
	"log"
	"os"
	"path/filepath"
	"sync"

	"metrochat/internal/oss"
)

// 资源广场 -> 雨云 S3 同步。阿里云 OSS（a.ossClient）保留给其它媒体不动。
var rainSyncInFlight sync.Map

// pushResourceToRainOSS 将资源广场新上传的文件异步同步到雨云 S3。
// 全程受全局 5MB/s 限速（oss.RainSyncRateLimiter），失败静默重试由下次访问兜底。
func (a *API) pushResourceToRainOSS(relativeURL string) {
	if a == nil || a.rainOSSClient == nil || !a.rainOSSClient.IsEnabled() {
		return
	}
	relPath, ok := uploadURLToRelativePath(relativeURL)
	if !ok || relPath == "" {
		return
	}
	if _, loaded := rainSyncInFlight.LoadOrStore(relPath, struct{}{}); loaded {
		return
	}
	go func() {
		defer rainSyncInFlight.Delete(relPath)
		filePath := filepath.Join(a.cfg.UploadDir, filepath.FromSlash(relPath))
		if err := uploadLocalFileToRainOSS(a.rainOSSClient, filePath, relPath); err != nil {
			log.Printf("[RainOSS] 资源广场同步失败 path=%s err=%v", relPath, err)
		}
	}()
}

func uploadLocalFileToRainOSS(client *oss.RainClient, filePath, relPath string) error {
	info, err := os.Stat(filePath)
	if err != nil {
		return err
	}
	if info.IsDir() {
		return nil
	}
	if ok, err := client.Exists(relPath); err == nil && ok {
		return nil
	}
	publicURL, err := client.UploadFileStream(relPath, filePath)
	if err != nil {
		return err
	}
	log.Printf("[RainOSS] 上传成功 path=%s bytes=%d url=%s", relPath, info.Size(), publicURL)
	return nil
}
