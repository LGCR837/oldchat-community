package media

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func TestCandidatesRewriteOSSAndUploadPaths(t *testing.T) {
	f := NewFetcher(t.TempDir(), "https://old.example.com/")
	cases := map[string]string{
		"https://ocf.oss-cn-shanghai.aliyuncs.com/avatars/a.png": "https://old.example.com/v1/uploads/avatars/a.png",
		"https://ocf.oss-cn-shanghai.aliyuncs.com/media/1/b.jpg": "https://old.example.com/v1/uploads/media/1/b.jpg",
		"https://old.example.com/oss/avatars/c.png":              "https://old.example.com/v1/uploads/avatars/c.png",
		"https://old.example.com/v1/uploads/avatars/d.png":       "https://old.example.com/v1/uploads/avatars/d.png",
		"https://old.example.com/uploads/avatars/e.png":          "https://old.example.com/v1/uploads/avatars/e.png",
		"/oss/avatars/f.png": "https://old.example.com/v1/uploads/avatars/f.png",
	}
	for raw, want := range cases {
		got := f.Candidates(raw)
		if len(got) == 0 {
			t.Fatalf("%s 没有候选地址", raw)
		}
		if got[0] != want {
			t.Fatalf("%s 首选候选 %q，期望 %q", raw, got[0], want)
		}
	}
}

func TestCandidatesKeepOriginalAsFallback(t *testing.T) {
	f := NewFetcher(t.TempDir(), "https://old.example.com")
	got := f.Candidates("https://cdn.example.net/pic.png")
	if len(got) != 2 {
		t.Fatalf("应保留原始地址兜底，实际 %v", got)
	}
	if got[1] != "https://cdn.example.net/pic.png" {
		t.Fatalf("兜底地址不对：%v", got)
	}
}

func TestDownloadResumesTruncatedResponse(t *testing.T) {
	payload := []byte(strings.Repeat("oldchat", 200))
	var calls int32
	var ranged int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&calls, 1)
		if r.URL.Path != "/v1/uploads/avatars/big.png" {
			http.NotFound(w, r)
			return
		}
		start := int64(0)
		if rg := r.Header.Get("Range"); rg != "" {
			atomic.AddInt32(&ranged, 1)
			v := strings.TrimSuffix(strings.TrimPrefix(rg, "bytes="), "-")
			n, err := strconv.ParseInt(v, 10, 64)
			if err != nil {
				t.Errorf("Range 头异常: %s", rg)
			}
			start = n
			w.Header().Set("Content-Range", "bytes "+rg+"-/"+strconv.Itoa(len(payload)))
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)-int(n)))
			w.WriteHeader(http.StatusPartialContent)
		} else {
			w.Header().Set("Content-Type", "image/png")
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		}
		if _, err := w.Write(payload[start:]); err != nil {
			return
		}
	}))
	defer srv.Close()

	f := NewFetcher(t.TempDir(), srv.URL)
	res, err := f.Download(context.Background(), srv.URL+"/oss/avatars/big.png", "avatars")
	if err != nil {
		t.Fatalf("下载失败: %v", err)
	}
	body, err := os.ReadFile(res.Path)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != string(payload) {
		t.Fatalf("内容不完整：%d/%d 字节", len(body), len(payload))
	}
	if !strings.HasPrefix(res.URL, "/v1/uploads/avatars/") || !strings.HasSuffix(res.URL, ".png") {
		t.Fatalf("返回 URL 异常: %s", res.URL)
	}
	if atomic.LoadInt32(&ranged) != 0 {
		t.Fatalf("没有断流时不应使用 Range 续传")
	}
}

func TestDownloadRetriesAfterShortWrite(t *testing.T) {
	payload := []byte(strings.Repeat("x", 5000))
	var calls int32
	var resumed int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt32(&calls, 1)
		if n == 1 {
			// 声明完整长度但只发一半，模拟源站截断。
			w.Header().Set("Content-Type", "image/jpeg")
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
			_, _ = w.Write(payload[:2500])
			return
		}
		start := 0
		if rg := r.Header.Get("Range"); rg != "" {
			atomic.AddInt32(&resumed, 1)
			v := strings.TrimSuffix(strings.TrimPrefix(rg, "bytes="), "-")
			if parsed, err := strconv.Atoi(v); err == nil {
				start = parsed
			}
			w.Header().Set("Content-Range", "bytes "+rg+"-/"+strconv.Itoa(len(payload)))
			w.Header().Set("Content-Length", strconv.Itoa(len(payload)-start))
			w.Header().Set("Content-Type", "image/jpeg")
			w.WriteHeader(http.StatusPartialContent)
		}
		_, _ = w.Write(payload[start:])
	}))
	defer srv.Close()

	f := NewFetcher(t.TempDir(), srv.URL)
	res, err := f.Download(context.Background(), srv.URL+"/v1/uploads/avatars/x.jpg", "avatars")
	if err != nil {
		t.Fatalf("重试后仍失败: %v", err)
	}
	body, err := os.ReadFile(res.Path)
	if err != nil {
		t.Fatal(err)
	}
	if len(body) != len(payload) {
		t.Fatalf("大小 %d，期望 %d", len(body), len(payload))
	}
	if atomic.LoadInt32(&resumed) == 0 {
		t.Fatal("应使用 Range 从断点续传")
	}
}

func TestDownloadRefusesCrossHostRedirect(t *testing.T) {
	oss := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("不应跟随到 OSS 域名")
		http.NotFound(w, r)
	}))
	defer oss.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, oss.URL+"/avatars/a.png", http.StatusTemporaryRedirect)
	}))
	defer srv.Close()

	f := NewFetcher(t.TempDir(), srv.URL)
	if _, err := f.Download(context.Background(), srv.URL+"/oss/avatars/a.png", "avatars"); err == nil {
		t.Fatal("跨域重定向应导致失败，而不是把 OSS 错误页存下来")
	}
}

func TestDownloadIgnoresTooLargeFile(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Content-Length", "64")
		_, _ = w.Write(make([]byte, 64))
	}))
	defer srv.Close()

	f := NewFetcher(t.TempDir(), srv.URL)
	f.MaxBytes = 16
	if _, err := f.Download(context.Background(), srv.URL+"/v1/uploads/avatars/a.png", "avatars"); err == nil {
		t.Fatal("超过上限应报错")
	}
}

func TestDownloadRejectsNonImage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		_, _ = w.Write([]byte("<html>404</html>"))
	}))
	defer srv.Close()

	f := NewFetcher(t.TempDir(), srv.URL)
	if _, err := f.Download(context.Background(), srv.URL+"/v1/uploads/avatars/a", "avatars"); err == nil {
		t.Fatal("非图片应报错")
	}
	if entries, err := os.ReadDir(filepath.Join(f.UploadDir, "avatars")); err == nil {
		for _, e := range entries {
			if strings.HasSuffix(e.Name(), ".part") {
				t.Fatalf("残留临时文件 %s", e.Name())
			}
		}
	}
}
