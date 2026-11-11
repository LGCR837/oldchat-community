package main

import (
	"fmt"
	"log"

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
	key := "public-read-check.txt"
	publicURL, err := client.Upload(key, []byte("ocf public read check"))
	if err != nil {
		log.Fatalf("上传失败: %v", err)
	}
	fmt.Println(publicURL)
}
