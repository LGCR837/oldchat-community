package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	aliyunoss "github.com/aliyun/aliyun-oss-go-sdk/oss"
)

// 从 ocaht(香港) 迁移对象到 ocf(上海)，同名对象跳过。
// 用法：
//   go run ./cmd/oss_migrate -dry-run          # 只统计不实际复制
//   go run ./cmd/oss_migrate -prefix music/    # 只迁移指定前缀
//   go run ./cmd/oss_migrate                   # 全量迁移

var (
	srcEndpoint = "https://oss-cn-hongkong.aliyuncs.com"
	dstEndpoint = "https://oss-cn-shanghai.aliyuncs.com"
	srcBucket   = "ocaht"
	dstBucket   = "ocf"
)

func main() {
	dryRun := flag.Bool("dry-run", false, "只列出将迁移的对象，不实际复制")
	prefix := flag.String("prefix", "", "只迁移该前缀下的对象")
	concurrency := flag.Int("concurrency", 8, "并发数")
	flag.Parse()

	ak := os.Getenv("OSS_ACCESS_KEY_ID")
	sk := os.Getenv("OSS_ACCESS_KEY_SECRET")
	if ak == "" || sk == "" {
		log.Fatal("请先设置 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 环境变量")
	}

	srcClient, err := aliyunoss.New(srcEndpoint, ak, sk)
	if err != nil {
		log.Fatalf("创建源客户端失败: %v", err)
	}
	dstClient, err := aliyunoss.New(dstEndpoint, ak, sk)
	if err != nil {
		log.Fatalf("创建目标客户端失败: %v", err)
	}
	src, err := srcClient.Bucket(srcBucket)
	if err != nil {
		log.Fatalf("打开源 bucket 失败: %v", err)
	}
	dst, err := dstClient.Bucket(dstBucket)
	if err != nil {
		log.Fatalf("打开目标 bucket 失败: %v", err)
	}

	// 先列出目标 bucket 已有对象，用于跳过
	existing := make(map[string]bool)
	marker := ""
	for {
		lor, err := dst.ListObjects(aliyunoss.Marker(marker), aliyunoss.MaxKeys(1000))
		if err != nil {
			log.Fatalf("列出目标 bucket 失败: %v", err)
		}
		for _, o := range lor.Objects {
			existing[o.Key] = true
		}
		if !lor.IsTruncated {
			break
		}
		marker = lor.NextMarker
	}
	fmt.Printf("目标 bucket 已有对象: %d\n", len(existing))

	// 列出源 bucket
	var total, skipped int64
	var wg sync.WaitGroup
	sem := make(chan struct{}, *concurrency)
	start := time.Now()

	srcMarker := ""
	for {
		lor, err := src.ListObjects(aliyunoss.Marker(srcMarker), aliyunoss.MaxKeys(1000))
		if err != nil {
			log.Fatalf("列出源 bucket 失败: %v", err)
		}
		for _, o := range lor.Objects {
			if *prefix != "" && !strings.HasPrefix(o.Key, *prefix) {
				continue
			}
			if existing[o.Key] {
				atomic.AddInt64(&skipped, 1)
				continue
			}
			atomic.AddInt64(&total, 1)
			if *dryRun {
				continue
			}
			wg.Add(1)
			sem <- struct{}{}
			go func(key string, size int64) {
				defer wg.Done()
				defer func() { <-sem }()
				if err := copyObject(src, dst, key); err != nil {
					log.Printf("复制失败 %s: %v", key, err)
				} else {
					fmt.Printf("✓ %s (%d bytes)\n", key, size)
				}
			}(o.Key, o.Size)
		}
		if !lor.IsTruncated {
			break
		}
		srcMarker = lor.NextMarker
	}
	wg.Wait()

	if *dryRun {
		fmt.Printf("[dry-run] 待迁移对象: %d, 已存在跳过: %d\n", total, skipped)
	} else {
		fmt.Printf("完成: 迁移 %d 个对象, 跳过 %d 个, 耗时 %s\n", total, skipped, time.Since(start).Round(time.Second))
	}
}

func copyObject(src, dst *aliyunoss.Bucket, key string) error {
	// 流式复制，避免大文件占内存；同一账号跨 region 复制需逐个对象读取再写入。
	body, err := src.GetObject(key)
	if err != nil {
		return err
	}
	defer body.Close()
	err = dst.PutObject(key, body)
	if err != nil {
		return err
	}
	// 校验对象存在
	_, err = dst.GetObjectMeta(key)
	if err != nil {
		return fmt.Errorf("校验失败: %w", err)
	}
	return nil
}

var _ = io.Discard
