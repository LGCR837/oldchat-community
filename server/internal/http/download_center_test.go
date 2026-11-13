package httpapi

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"metrochat/internal/config"
)

func newDownloadCenterAPI(t *testing.T, formalJSON, devJSON string) *API {
	t.Helper()
	updateDir := t.TempDir()
	for name, body := range map[string]string{"update.json": formalJSON, "dev_update.json": devJSON} {
		if body == "" {
			continue
		}
		if err := os.WriteFile(filepath.Join(updateDir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return &API{cfg: config.Config{UpdateDir: updateDir}}
}

func TestDownloadCenterRendersFormalAndDevOnly(t *testing.T) {
	api := newDownloadCenterAPI(t,
		`{"version_name":"1.4.5","version_code":520,"apk":"oldchat-1.4.5.apk","notes":"正式版说明"}`,
		`{"version_name":"1.4.5-v2test228","version_code":538,"apk":"oldchat-1.4.5-v2test228.apk","release_notes":"测试版说明"}`)
	for _, name := range []string{"oldchat-1.4.5.apk", "oldchat-1.4.5-v2test228.apk"} {
		if err := os.WriteFile(filepath.Join(api.cfg.UpdateDir, name), []byte("apk"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	rec := httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("下载中心状态码异常：%d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{
		"旧聊 正式版", "旧聊 测试版 (Dev)",
		"v1.4.5", "v1.4.5-v2test228", "520", "538",
		"正式版说明", "测试版说明",
		"/update/oldchat-1.4.5.apk", "/update/oldchat-1.4.5-v2test228.apk",
		"/update/update.json", "/update/dev_update.json",
	} {
		if !strings.Contains(body, want) {
			t.Fatalf("下载中心缺少 %q", want)
		}
	}
	for _, banned := range []string{"Android 1.6", "text-android", "com.im.oldchat.text"} {
		if strings.Contains(body, banned) {
			t.Fatalf("下载中心不应包含 %q", banned)
		}
	}
}

func TestDownloadCenterWithoutArtifacts(t *testing.T) {
	api := newDownloadCenterAPI(t, "", "")

	rec := httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("无安装包时下载中心状态码异常：%d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{"暂未发布", "未发布", "尚未发布该通道版本", "安装包尚未上传"} {
		if !strings.Contains(body, want) {
			t.Fatalf("下载中心缺少 %q", want)
		}
	}
	if strings.Contains(body, "href=\"/update/\"") {
		t.Fatalf("缺少安装包时不应输出空的下载链接")
	}
}

func TestDownloadCenterServesFilesAndRejectsTraversal(t *testing.T) {
	api := newDownloadCenterAPI(t, `{"version_name":"1.4.5","apk":"a.apk"}`, "")
	if err := os.WriteFile(filepath.Join(api.cfg.UpdateDir, "a.apk"), []byte("apk-body"), 0o644); err != nil {
		t.Fatal(err)
	}

	rec := httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/update.json", nil))
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "1.4.5") {
		t.Fatalf("配置文件读取异常：%d", rec.Code)
	}
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("配置文件应禁用缓存，实际：%q", rec.Header().Get("Cache-Control"))
	}

	rec = httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/a.apk", nil))
	if rec.Code != http.StatusOK || rec.Body.String() != "apk-body" {
		t.Fatalf("安装包分发异常：%d", rec.Code)
	}

	rec = httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/../settings.json", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("越权路径应返回 404，实际：%d", rec.Code)
	}
}

func TestLandingDropsLegacyAndroid16Entrance(t *testing.T) {
	api := newDownloadCenterAPI(t, `{"version_name":"1.4.5","apk":"oldchat-1.4.5.apk"}`, "")
	rec := httptest.NewRecorder()
	api.handleLanding(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	body := rec.Body.String()
	if !strings.Contains(body, `href="/update/"`) {
		t.Fatalf("主页缺少下载中心入口")
	}
	if strings.Contains(body, "Android 1.6") || strings.Contains(body, "text-android") {
		t.Fatalf("主页不应再出现 Android 1.6 入口")
	}
}

func TestDownloadCenterShowsDesktopPlaceholder(t *testing.T) {
	t.Chdir(t.TempDir())
	api := newDownloadCenterAPI(t, "", "")
	rec := httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("下载页状态码异常：%d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{"WPF 客户端", "桌面端安装包暂未提供", ".NET Framework 4.8", "预留"} {
		if !strings.Contains(body, want) {
			t.Fatalf("下载页缺少桌面端占位内容 %q", want)
		}
	}
}

func TestDownloadCenterDesktopFollowsClientRegistry(t *testing.T) {
	t.Chdir(t.TempDir())
	payload := `{"clients":[{"id":"wpf","name":"旧聊桌面端","platform":"Windows","version":"0.1.0","description":"内测版本","download_url":"https://example.com/oldchat-wpf.exe","enabled":true}]}`
	if err := os.WriteFile("clients.json", []byte(payload), 0o644); err != nil {
		t.Fatal(err)
	}
	api := newDownloadCenterAPI(t, "", "")
	rec := httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/", nil))
	body := rec.Body.String()
	for _, want := range []string{"旧聊桌面端", "v0.1.0", "https://example.com/oldchat-wpf.exe", "内测版本", "下载 WPF 客户端安装包"} {
		if !strings.Contains(body, want) {
			t.Fatalf("下载页未跟随客户端清单 %q", want)
		}
	}
	if strings.Contains(body, "桌面端安装包暂未提供") {
		t.Fatalf("已填下载地址时不应再显示占位按钮")
	}
}

func TestDownloadCenterHidesDisabledDesktop(t *testing.T) {
	t.Chdir(t.TempDir())
	payload := `{"clients":[{"id":"wpf","name":"旧聊桌面端","platform":"Windows","enabled":false}]}`
	if err := os.WriteFile("clients.json", []byte(payload), 0o644); err != nil {
		t.Fatal(err)
	}
	api := newDownloadCenterAPI(t, "", "")
	rec := httptest.NewRecorder()
	api.handleUpdateCenter(rec, httptest.NewRequest(http.MethodGet, "/update/", nil))
	if strings.Contains(rec.Body.String(), "WPF 客户端") || strings.Contains(rec.Body.String(), "旧聊桌面端") {
		t.Fatalf("关闭显示后不应渲染桌面端卡片")
	}
}
