package httpapi

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"metrochat/internal/config"
)

func TestAppLandingShowsDownloadAndServiceEntrances(t *testing.T) {
	updateDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(updateDir, "update.json"), []byte(`{
		"version_name":"1.3.47","apk":"oldchat-1.3.47.apk"
	}`), 0o644); err != nil {
		t.Fatal(err)
	}
	api := &API{cfg: config.Config{UpdateDir: updateDir}}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)

	api.handleLanding(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("主页状态码异常：%d", rec.Code)
	}
	body := rec.Body.String()
	for _, expected := range []string{"Oldchat 旧聊", "当前版本 1.3.47", "/update/", "/shop", "/igotbanned", "开发者平台", "/bot-apply", "/channel-api/apply", "/channel-manage", "开放文档", "/routes.md", "/api.md", "/client-guide.md", "/lua-cip.md", "/bot-api.md", "/channel-api.md"} {
		if !strings.Contains(body, expected) {
			t.Fatalf("主页缺少 %q", expected)
		}
	}
}

func TestShopAndAppealUseGeometricStyleWithoutEmoji(t *testing.T) {
	var shop bytes.Buffer
	if err := titleShopTpl.Execute(&shop, titleShopData{}); err != nil {
		t.Fatal(err)
	}
	var appeal bytes.Buffer
	if err := iGotBannedTpl.Execute(&appeal, iGotBannedPageData{}); err != nil {
		t.Fatal(err)
	}
	for name, body := range map[string]string{"shop": shop.String(), "appeal": appeal.String()} {
		for _, expected := range []string{"border-radius:0!important", "HOME", "/download/oldchat.apk"} {
			if !strings.Contains(body, expected) {
				t.Fatalf("%s 页面缺少 %q", name, expected)
			}
		}
		for _, value := range body {
			if value >= 0x1F300 && value <= 0x1FAFF {
				t.Fatalf("%s 页面包含 Emoji：%U", name, value)
			}
		}
	}
}

func TestAppAPKDownloadUsesCurrentReleaseFile(t *testing.T) {
	updateDir := t.TempDir()
	apkBody := []byte("test-apk")
	if err := os.WriteFile(filepath.Join(updateDir, "oldchat-1.3.47.apk"), apkBody, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(updateDir, "update.json"), []byte(`{
		"version_name":"1.3.47","apk":"oldchat-1.3.47.apk"
	}`), 0o644); err != nil {
		t.Fatal(err)
	}
	api := &API{cfg: config.Config{UpdateDir: updateDir}}
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/download/oldchat.apk", nil)

	api.handleAppAPKDownload(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("下载状态码异常：%d", rec.Code)
	}
	if rec.Body.String() != string(apkBody) {
		t.Fatalf("下载内容异常：%q", rec.Body.String())
	}
	if !strings.Contains(rec.Header().Get("Content-Disposition"), "oldchat.apk") {
		t.Fatalf("缺少下载文件名响应头：%q", rec.Header().Get("Content-Disposition"))
	}
}
