// Package media 从旧服务器拉取媒体文件并落盘到本地 uploads 目录。
//
// 旧站的媒体地址通常有两种：指向阿里云 OSS 的绝对地址，或者 /oss/ 开头的
// 相对路径。OSS 停流之后这两类都不可用了，但源服务器上的 uploads 目录仍有
// 副本，所以这里统一把它们改写为源站的 /v1/uploads/ 路径重新拉取。
//
// 源站偶尔会把响应截断（尤其在图片较大时），因此下载按 Range 续传重试，
// 并以 Content-Length 校验完整性，而不是读到 EOF 就当成成功。
package media

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"github.com/aidarkhanov/nanoid"
)

// 单张图片的大小上限，超过即放弃。
const DefaultMaxBytes int64 = 32 << 20

// 同一地址的下载尝试次数，用于兜住源站截断。
const maxAttempts = 3

var imageExtByType = map[string]string{
	"image/png":  ".png",
	"image/jpeg": ".jpg",
	"image/gif":  ".gif",
	"image/webp": ".webp",
}

var imageExtBySuffix = map[string]string{
	".png":  ".png",
	".jpg":  ".jpg",
	".jpeg": ".jpg",
	".gif":  ".gif",
	".webp": ".webp",
}

// Result 是一次成功下载的结果。
type Result struct {
	// URL 是写进数据库的相对地址，例如 /v1/uploads/avatars/abc.png。
	URL string
	// Path 是本地绝对路径。
	Path string
	// Bytes 是文件大小。
	Bytes int64
	// Redirected 表示原始地址不可用，靠改写到源站才成功。
	Redirected bool
}

// Fetcher 负责把旧站媒体搬到本地。
type Fetcher struct {
	UploadDir  string
	SourceHost string
	MaxBytes   int64
	Client     *http.Client
}

// NewFetcher 创建下载器。sourceHost 为旧站 origin，例如 https://oc.mcl0.dpdns.org。
func NewFetcher(uploadDir, sourceHost string) *Fetcher {
	return &Fetcher{
		UploadDir:  uploadDir,
		SourceHost: strings.TrimRight(strings.TrimSpace(sourceHost), "/"),
		MaxBytes:   DefaultMaxBytes,
		Client:     newHTTPClient(),
	}
}

func newHTTPClient() *http.Client {
	tr := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		MaxIdleConnsPerHost:   2,
		DisableCompression:    true, // 关掉 gzip，否则长度校验失真
		ResponseHeaderTimeout: 25 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
	return &http.Client{
		Transport: tr,
		Timeout:   90 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 3 {
				return errors.New("重定向次数过多")
			}
			// 旧站 /oss/ 会跳到已停流的 OSS，跟过去只有超时，直接把 3xx 交回调用方。
			if len(via) > 0 && !strings.EqualFold(req.URL.Host, via[0].URL.Host) {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
}

// Candidates 返回按优先级排列的候选地址：先源站本地副本，最后才是原始地址。
func (f *Fetcher) Candidates(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	u, err := url.Parse(raw)
	if err != nil {
		return nil
	}
	if u.Host == "" {
		if strings.HasPrefix(u.Path, "/") {
			return f.localCandidates(u.Path)
		}
		return nil
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return nil
	}
	out := f.localCandidates(u.Path)
	if !sameHost(u.Host, f.SourceHost) {
		out = append(out, raw)
	}
	return dedupe(out)
}

// localCandidates 把旧站路径改写成源站上真实存在的 uploads 路径。
func (f *Fetcher) localCandidates(p string) []string {
	base := f.SourceHost
	if base == "" {
		return nil
	}
	if p == "" {
		return nil
	}
	clean := path.Clean(p)
	var out []string
	switch {
	case strings.HasPrefix(clean, "/v1/uploads/"):
		out = append(out, base+clean)
	case strings.HasPrefix(clean, "/uploads/"):
		out = append(out, base+"/v1"+clean, base+clean)
	case strings.HasPrefix(clean, "/oss/"):
		rest := strings.TrimPrefix(clean, "/oss/")
		out = append(out, base+"/v1/uploads/"+rest, base+clean)
	default:
		out = append(out, base+"/v1/uploads/"+strings.TrimPrefix(clean, "/"))
	}
	return out
}

// Download 尝试把 raw 指向的图片搬到 UploadDir/subdir 下。
// 失败不会破坏已有文件；返回的 URL 可直接写库。
func (f *Fetcher) Download(ctx context.Context, raw, subdir string) (Result, error) {
	candidates := f.Candidates(raw)
	if len(candidates) == 0 {
		return Result{}, fmt.Errorf("无法解析媒体地址: %s", raw)
	}
	dir := filepath.Join(f.UploadDir, subdir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return Result{}, err
	}
	var lastErr error
	for i, cand := range candidates {
		res, err := f.fetchOne(ctx, cand, dir, subdir)
		if err == nil {
			res.Redirected = i > 0
			return res, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			return Result{}, ctx.Err()
		}
	}
	if lastErr == nil {
		lastErr = errors.New("没有可用的下载地址")
	}
	return Result{}, lastErr
}

func (f *Fetcher) fetchOne(ctx context.Context, cand, dir, subdir string) (Result, error) {
	part := filepath.Join(dir, ".dl-"+nanoid.New()+".part")
	defer os.Remove(part)

	var written int64
	var ext string
	for attempt := 0; attempt < maxAttempts; attempt++ {
		if ctx.Err() != nil {
			return Result{}, ctx.Err()
		}
		n, e, err := f.fetchAttempt(ctx, cand, part, written)
		written = n
		if e != "" && ext == "" {
			ext = e
		}
		if err == nil {
			break
		}
		if attempt == maxAttempts-1 {
			return Result{}, err
		}
		time.Sleep(time.Duration(attempt+1) * 400 * time.Millisecond)
	}
	if written == 0 {
		return Result{}, errors.New("下载内容为空")
	}
	if ext == "" {
		ext = extFromPath(cand)
	}
	if ext == "" {
		return Result{}, errors.New("不支持的图片格式")
	}
	name := nanoid.New() + ext
	final := filepath.Join(dir, name)
	if err := os.Rename(part, final); err != nil {
		return Result{}, err
	}
	return Result{
		URL:   "/v1/uploads/" + subdir + "/" + name,
		Path:  final,
		Bytes: written,
	}, nil
}

// fetchAttempt 发一次请求，从已有字节处续传。返回累计字节数与内容类型推断的扩展名。
func (f *Fetcher) fetchAttempt(ctx context.Context, cand, part string, have int64) (int64, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, cand, nil)
	if err != nil {
		return have, "", err
	}
	req.Header.Set("Accept", "image/*,*/*;q=0.8")
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("User-Agent", "oldchat-community-migrate/1.0")
	if have > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", have))
	}
	resp, err := f.Client.Do(req)
	if err != nil {
		return have, "", err
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK, http.StatusPartialContent:
	case http.StatusRequestedRangeNotSatisfiable:
		// 本地已有完整文件，直接认为成功。
		return have, "", nil
	default:
		return have, "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	limit := f.MaxBytes
	if limit <= 0 {
		limit = DefaultMaxBytes
	}
	appendMode := resp.StatusCode == http.StatusPartialContent && have > 0
	flags := os.O_CREATE | os.O_WRONLY
	if appendMode {
		flags |= os.O_APPEND
	} else {
		flags |= os.O_TRUNC
		have = 0
	}
	out, err := os.OpenFile(part, flags, 0o644)
	if err != nil {
		return have, "", err
	}
	defer out.Close()

	expected := resp.ContentLength
	copied, copyErr := io.Copy(out, io.LimitReader(resp.Body, limit-have+1))
	total := have + copied
	if copyErr != nil {
		return total, "", copyErr
	}
	if total > limit {
		return 0, "", errors.New("文件超过大小上限")
	}
	if expected >= 0 && copied < expected {
		return total, "", fmt.Errorf("响应被截断，已收到 %d/%d 字节", copied, expected)
	}
	ext := imageExtByType[normalizeContentType(resp.Header.Get("Content-Type"))]
	if ext == "" {
		ext = extFromPath(cand)
	}
	return total, ext, nil
}

func normalizeContentType(v string) string {
	if i := strings.IndexByte(v, ';'); i >= 0 {
		v = v[:i]
	}
	return strings.ToLower(strings.TrimSpace(v))
}

func extFromPath(p string) string {
	u, err := url.Parse(p)
	if err == nil {
		p = u.Path
	}
	return imageExtBySuffix[strings.ToLower(path.Ext(p))]
}

func sameHost(host, origin string) bool {
	if origin == "" {
		return false
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Host, host)
}

func dedupe(in []string) []string {
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, v := range in {
		if v == "" {
			continue
		}
		if _, ok := seen[v]; ok {
			continue
		}
		seen[v] = struct{}{}
		out = append(out, v)
	}
	return out
}
