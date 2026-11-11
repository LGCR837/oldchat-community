package oss

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"golang.org/x/time/rate"
)

// RainConfig 描述雨云 S3（Rains3）连接信息。凭据只允许从服务器环境变量读取。
type RainConfig struct {
	Endpoint        string
	AccessKeyID     string
	AccessKeySecret string
	BucketName      string
	URLPrefix       string
	Enabled         bool
}

// LoadRainConfigFromEnv 从 RAIN_OSS_* 环境变量读取雨云 S3 配置。
func LoadRainConfigFromEnv() RainConfig {
	cfg := RainConfig{
		Endpoint:        strings.TrimSpace(os.Getenv("RAIN_OSS_ENDPOINT")),
		AccessKeyID:     strings.TrimSpace(os.Getenv("RAIN_OSS_ACCESS_KEY")),
		AccessKeySecret: strings.TrimSpace(os.Getenv("RAIN_OSS_SECRET_KEY")),
		BucketName:      strings.TrimSpace(os.Getenv("RAIN_OSS_BUCKET")),
		URLPrefix:       strings.TrimRight(strings.TrimSpace(os.Getenv("RAIN_OSS_URL_PREFIX")), "/"),
	}
	enabledText := strings.TrimSpace(os.Getenv("RAIN_OSS_ENABLED"))
	if strings.EqualFold(enabledText, "true") || enabledText == "1" {
		cfg.Enabled = true
	} else if strings.TrimSpace(enabledText) == "" &&
		cfg.Endpoint != "" && cfg.AccessKeyID != "" && cfg.AccessKeySecret != "" && cfg.BucketName != "" {
		cfg.Enabled = true
	}
	if cfg.Endpoint == "" {
		cfg.Endpoint = "https://cn-sy1.rains3.com"
	}
	if cfg.BucketName == "" {
		cfg.BucketName = "oldchat"
	}
	if cfg.URLPrefix == "" {
		cfg.URLPrefix = "https://cn-sy1.rains3.com/oldchat"
	}
	return cfg
}

// RainClient 是雨云 S3 的轻量封装。同一个实例全局共享，内部连接池复用。
type RainClient struct {
	config RainConfig
	client *minio.Client
}

func NewRainClient(cfg RainConfig) (*RainClient, error) {
	if !cfg.Enabled {
		return &RainClient{config: cfg}, nil
	}
	if strings.TrimSpace(cfg.Endpoint) == "" || strings.TrimSpace(cfg.AccessKeyID) == "" ||
		strings.TrimSpace(cfg.AccessKeySecret) == "" || strings.TrimSpace(cfg.BucketName) == "" {
		return nil, errors.New("雨云 S3 配置不完整")
	}
	endpoint := strings.TrimPrefix(strings.TrimPrefix(cfg.Endpoint, "https://"), "http://")
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKeyID, cfg.AccessKeySecret, ""),
		Secure: strings.HasPrefix(cfg.Endpoint, "https://"),
	})
	if err != nil {
		return nil, fmt.Errorf("创建雨云 S3 客户端失败: %w", err)
	}
	return &RainClient{config: cfg, client: client}, nil
}

func (c *RainClient) IsEnabled() bool {
	return c != nil && c.config.Enabled && c.client != nil
}

// RainSyncRateLimiter 是所有雨云同步共用的全局限速器：5MB/s。
var RainSyncRateLimiter = rate.NewLimiter(rate.Limit(5*1024*1024), 5*1024*1024)

// 限制 rain 上传并发数，避免多个大文件同时抢限速器导致排队爆炸。
var rainUploadSem = make(chan struct{}, 4)

// UploadFileStream 流式上传本地文件到雨云 S3，全程受全局 5MB/s 限速。
func (c *RainClient) UploadFileStream(objectKey, localPath string) (string, error) {
	if !c.IsEnabled() {
		return "", errors.New("雨云 S3 未启用")
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return "", err
	}
	file, err := os.Open(localPath)
	if err != nil {
		return "", err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return "", err
	}
	reader := &rainRateLimitedReader{reader: file, limiter: RainSyncRateLimiter, buf: make([]byte, 256*1024)}
	rainUploadSem <- struct{}{}
	defer func() { <-rainUploadSem }()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Hour)
	defer cancel()
	_, err = c.client.PutObject(ctx, c.config.BucketName, key, reader, info.Size(),
		minio.PutObjectOptions{ContentType: "application/octet-stream"})
	if err != nil {
		return "", fmt.Errorf("上传雨云 S3 失败: %w", err)
	}
	return c.PublicURL(key), nil
}

// Exists 检查对象是否存在（防重传）。
func (c *RainClient) Exists(objectKey string) (bool, error) {
	if !c.IsEnabled() {
		return false, nil
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return false, err
	}
	_, err = c.client.StatObject(context.Background(), c.config.BucketName, key, minio.StatObjectOptions{})
	if err != nil {
		if minio.ToErrorResponse(err).StatusCode == 404 {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

func (c *RainClient) Delete(objectKey string) error {
	if !c.IsEnabled() {
		return nil
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return c.client.RemoveObject(ctx, c.config.BucketName, key, minio.RemoveObjectOptions{})
}

func (c *RainClient) PublicURL(objectKey string) string {
	return strings.TrimRight(c.config.URLPrefix, "/") + "/" + strings.TrimLeft(objectKey, "/")
}

// rainRateLimitedReader 在 io.Reader 上叠加令牌桶限速。
type rainRateLimitedReader struct {
	reader  io.Reader
	limiter *rate.Limiter
	buf     []byte
	mu      sync.Mutex
}

func (r *rainRateLimitedReader) Read(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	chunk := r.buf
	if len(chunk) > len(p) {
		chunk = chunk[:len(p)]
	}
	n, err := r.reader.Read(chunk)
	if n <= 0 {
		return n, err
	}
	// 按实际读取字节数等待令牌，实现严格的全局限速。
	if err := r.limiter.WaitN(context.Background(), n); err != nil {
		return 0, err
	}
	copy(p, chunk[:n])
	return n, nil
}
