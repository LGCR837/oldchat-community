package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"time"

	"metrochat/internal/oss"
)

func main() {
	cfg := oss.LoadConfigFromEnv()
	client, err := oss.NewClient(cfg)
	if err != nil {
		log.Fatalf("创建 OSS 客户端失败: %v", err)
	}
	if !client.IsEnabled() {
		log.Fatal("OSS 未启用")
	}

	key := fmt.Sprintf("health/oldchat-%d.txt", time.Now().UnixNano())
	publicURL, err := client.Upload(key, []byte("Oldchat OSS health check"))
	if err != nil {
		log.Fatalf("测试上传失败: %v", err)
	}
	defer client.Delete(key)

	signedURL, err := client.SignedDownloadURL(key, 120)
	if err != nil {
		log.Fatalf("生成签名下载地址失败: %v", err)
	}
	httpClient := &http.Client{Timeout: 15 * time.Second}
	resp, err := httpClient.Get(signedURL)
	if err != nil {
		log.Fatalf("匿名读取测试失败: %v", err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode != http.StatusOK {
		log.Fatalf("匿名读取测试返回 HTTP %d", resp.StatusCode)
	}
	fmt.Printf("测试上传及签名读取成功：%s\n", publicURL)
}
