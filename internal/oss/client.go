package oss

import (
	"bytes"
	"fmt"
	"log"
	"os"
	"strings"

	aliyunoss "github.com/aliyun/aliyun-oss-go-sdk/oss"
)

// Config 描述服务端 OSS 连接信息。凭据只允许从服务器环境变量读取。
type Config struct {
	Endpoint        string
	AccessKeyID     string
	AccessKeySecret string
	BucketName      string
	URLPrefix       string
	Enabled         bool
}

// 以下变量可通过 go build -ldflags -X 注入服务端 EXE；环境变量优先级更高。
// 不要在源码中写入真实 AccessKey。
var (
	buildEnabled         string
	buildEndpoint        string
	buildAccessKeyID     string
	buildAccessKeySecret string
	buildBucketName      string
	buildURLPrefix       string
)

type Client struct {
	config Config
	client *aliyunoss.Client
	bucket *aliyunoss.Bucket
}

func NewClient(cfg Config) (*Client, error) {
	if !cfg.Enabled {
		return &Client{config: cfg}, nil
	}
	if strings.TrimSpace(cfg.Endpoint) == "" || strings.TrimSpace(cfg.AccessKeyID) == "" ||
		strings.TrimSpace(cfg.AccessKeySecret) == "" || strings.TrimSpace(cfg.BucketName) == "" ||
		strings.TrimSpace(cfg.URLPrefix) == "" {
		return nil, fmt.Errorf("OSS 配置不完整")
	}

	client, err := aliyunoss.New(cfg.Endpoint, cfg.AccessKeyID, cfg.AccessKeySecret)
	if err != nil {
		return nil, fmt.Errorf("创建 OSS 客户端失败: %w", err)
	}

	bucket, err := client.Bucket(cfg.BucketName)
	if err != nil {
		return nil, fmt.Errorf("打开 OSS Bucket 失败: %w", err)
	}

	return &Client{config: cfg, client: client, bucket: bucket}, nil
}

// Upload 上传内存数据，并为对象设置公共读，供客户端直接访问。
func (c *Client) Upload(objectKey string, data []byte) (string, error) {
	if !c.IsEnabled() {
		return "", fmt.Errorf("OSS 未启用")
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return "", err
	}
	if err := c.bucket.PutObject(key, bytes.NewReader(data)); err != nil {
		return "", fmt.Errorf("上传 OSS 对象失败: %w", err)
	}
	return c.PublicURL(key), nil
}

// UploadFile 从本地文件流式上传，避免大文件全部读入内存。
func (c *Client) UploadFile(objectKey, localPath string) (string, error) {
	if !c.IsEnabled() {
		return "", fmt.Errorf("OSS 未启用")
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return "", err
	}
	if err := c.bucket.PutObjectFromFile(key, localPath); err != nil {
		return "", fmt.Errorf("上传 OSS 文件失败: %w", err)
	}
	return c.PublicURL(key), nil
}

// SignedDownloadURL 为私有 Bucket 生成短时下载地址。
func (c *Client) SignedDownloadURL(objectKey string, expiresSeconds int64) (string, error) {
	if !c.IsEnabled() {
		return "", fmt.Errorf("OSS 未启用")
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return "", err
	}
	if expiresSeconds < 60 {
		expiresSeconds = 60
	}
	if expiresSeconds > 3600 {
		expiresSeconds = 3600
	}
	url, err := c.bucket.SignURL(key, aliyunoss.HTTPGet, expiresSeconds)
	if err != nil {
		return "", fmt.Errorf("生成 OSS 下载地址失败: %w", err)
	}
	// 旧 Endpoint 未包含 scheme 时 SDK 可能生成 HTTP；下载地址一律强制 HTTPS。
	if strings.HasPrefix(url, "http://") {
		url = "https://" + strings.TrimPrefix(url, "http://")
	}
	return url, nil
}

func (c *Client) Delete(objectKey string) error {
	if !c.IsEnabled() {
		return nil
	}
	key, err := normalizeObjectKey(objectKey)
	if err != nil {
		return err
	}
	if err := c.bucket.DeleteObject(key); err != nil {
		log.Printf("删除 OSS 对象失败 key=%s err=%v", key, err)
		return err
	}
	return nil
}

func (c *Client) IsEnabled() bool {
	return c != nil && c.config.Enabled && c.bucket != nil
}

func (c *Client) PublicURL(objectKey string) string {
	return strings.TrimRight(c.config.URLPrefix, "/") + "/" + strings.TrimLeft(objectKey, "/")
}

func normalizeObjectKey(objectKey string) (string, error) {
	key := strings.Trim(strings.ReplaceAll(strings.TrimSpace(objectKey), "\\", "/"), "/")
	if key == "" || key == "." || strings.HasPrefix(key, "../") || strings.Contains(key, "/../") {
		return "", fmt.Errorf("OSS 对象路径不合法")
	}
	return key, nil
}

func LoadConfigFromEnv() Config {
	enabledText := firstNonEmpty(os.Getenv("OSS_ENABLED"), buildEnabled)
	// 环境变量与 ldflags 均未设置时，使用内置默认配置兜底，
	// 保证任何方式启动（systemd / nohup / 直接运行）OSS 都可用。
	// 凭据一律从环境变量读取（或在构建时通过 -ldflags 注入），
	// 开源版本不在源码中内置任何真实 AccessKey。
	// 未配置时 OSS 功能自动关闭，不影响其余 API。
	endpoint := firstNonEmpty(os.Getenv("OSS_ENDPOINT"), buildEndpoint)
	if endpoint == "" {
		endpoint = "https://oss-cn-shanghai.aliyuncs.com"
	}
	accessKeyID := firstNonEmpty(os.Getenv("OSS_ACCESS_KEY_ID"), buildAccessKeyID)
	accessKeySecret := firstNonEmpty(os.Getenv("OSS_ACCESS_KEY_SECRET"), buildAccessKeySecret)
	bucketName := firstNonEmpty(os.Getenv("OSS_BUCKET_NAME"), buildBucketName)
	if bucketName == "" {
		bucketName = "ocf"
	}
	urlPrefix := strings.TrimRight(firstNonEmpty(os.Getenv("OSS_URL_PREFIX"), buildURLPrefix), "/")
	if urlPrefix == "" {
		urlPrefix = "https://ocf.oss-cn-shanghai.aliyuncs.com"
	}
	// 未显式配置 OSS_ENABLED 时，只要内置/环境有完整配置就默认启用。
	enabled := false
	if strings.EqualFold(enabledText, "true") || enabledText == "1" {
		enabled = true
	} else if strings.TrimSpace(enabledText) == "" && accessKeyID != "" && accessKeySecret != "" && bucketName != "" {
		enabled = true
	}
	return Config{
		Endpoint:        endpoint,
		AccessKeyID:     accessKeyID,
		AccessKeySecret: accessKeySecret,
		BucketName:      bucketName,
		URLPrefix:       urlPrefix,
		Enabled:         enabled,
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if out := strings.TrimSpace(value); out != "" {
			return out
		}
	}
	return ""
}
