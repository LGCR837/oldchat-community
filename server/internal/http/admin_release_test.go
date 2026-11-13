package httpapi

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"metrochat/internal/config"
)

func newReleaseAPI(t *testing.T) *API {
	t.Helper()
	return &API{cfg: config.Config{UpdateDir: t.TempDir()}}
}

func decodeBody(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatalf("响应不是合法 JSON：%v（%s）", err, rec.Body.String())
	}
	return out
}

func TestReleaseSaveRequiresExistingArtifact(t *testing.T) {
	api := newReleaseAPI(t)

	body := `{"channel":"formal","version_name":"1.4.5.5","version_code":545,"apk":"missing.apk"}`
	rec := httptest.NewRecorder()
	api.handleAdminAPIReleaseSave(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/save", strings.NewReader(body)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("安装包不存在时应拒绝保存，实际 %d", rec.Code)
	}
	if _, err := os.Stat(filepath.Join(api.cfg.UpdateDir, "update.json")); !os.IsNotExist(err) {
		t.Fatalf("失败时不应写入配置文件")
	}
}

func TestReleaseSaveThenRead(t *testing.T) {
	api := newReleaseAPI(t)
	artifact := filepath.Join(api.cfg.UpdateDir, "oldchat-community-545.apk")
	if err := os.WriteFile(artifact, []byte("apk"), 0o644); err != nil {
		t.Fatal(err)
	}

	body := `{"channel":"dev","version_name":"1.4.5.5","version_code":545,"apk":"oldchat-community-545.apk","notes":"第一行\n第二行"}`
	rec := httptest.NewRecorder()
	api.handleAdminAPIReleaseSave(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/save", strings.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存失败：%d %s", rec.Code, rec.Body.String())
	}

	raw, err := os.ReadFile(filepath.Join(api.cfg.UpdateDir, "dev_update.json"))
	if err != nil {
		t.Fatal(err)
	}
	var release landingRelease
	if err := json.Unmarshal(raw, &release); err != nil {
		t.Fatal(err)
	}
	if release.VersionCode != 545 || release.VersionName != "1.4.5.5" || release.APK != "oldchat-community-545.apk" {
		t.Fatalf("写入内容不符：%+v", release)
	}

	rec = httptest.NewRecorder()
	api.handleAdminAPIReleaseGet(rec, httptest.NewRequest(http.MethodGet, "/v1/admin/release", nil))
	data := decodeBody(t, rec)
	channels, _ := data["channels"].([]any)
	if len(channels) != 2 {
		t.Fatalf("应返回两个通道，实际 %d", len(channels))
	}
	dev := channels[1].(map[string]any)
	if dev["channel"] != "dev" || dev["exists"] != true {
		t.Fatalf("测试通道状态异常：%+v", dev)
	}

	files, _ := data["files"].([]any)
	var found map[string]any
	for _, item := range files {
		entry := item.(map[string]any)
		if entry["name"] == "oldchat-community-545.apk" {
			found = entry
		}
	}
	if found == nil {
		t.Fatalf("目录列表缺少安装包：%+v", files)
	}
	if found["referenced"] != "dev" {
		t.Fatalf("引用通道标记异常：%+v", found)
	}
}

func TestReleaseSaveRejectsUnknownChannel(t *testing.T) {
	api := newReleaseAPI(t)
	rec := httptest.NewRecorder()
	body := `{"channel":"beta","version_name":"1.0","version_code":1}`
	api.handleAdminAPIReleaseSave(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/save", strings.NewReader(body)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("未知通道应拒绝，实际 %d", rec.Code)
	}
}

func buildUploadBody(t *testing.T, field, filename string, content []byte) (*bytes.Buffer, string) {
	t.Helper()
	buf := new(bytes.Buffer)
	writer := multipart.NewWriter(buf)
	part, err := writer.CreateFormFile(field, filename)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(content); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buf, writer.FormDataContentType()
}

func TestReleaseUploadWritesArtifact(t *testing.T) {
	api := newReleaseAPI(t)
	buf, contentType := buildUploadBody(t, "file", "oldchat-community-545.apk", []byte("apk-payload"))
	req := httptest.NewRequest(http.MethodPost, "/v1/admin/release/upload", buf)
	req.Header.Set("Content-Type", contentType)

	rec := httptest.NewRecorder()
	api.handleAdminAPIReleaseUpload(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("上传失败：%d %s", rec.Code, rec.Body.String())
	}
	data := decodeBody(t, rec)
	if data["name"] != "oldchat-community-545.apk" {
		t.Fatalf("返回文件名异常：%+v", data)
	}
	got, err := os.ReadFile(filepath.Join(api.cfg.UpdateDir, "oldchat-community-545.apk"))
	if err != nil || string(got) != "apk-payload" {
		t.Fatalf("落盘内容不符：%q %v", got, err)
	}

	// 再传同名文件不应覆盖原包
	buf2, contentType2 := buildUploadBody(t, "file", "oldchat-community-545.apk", []byte("second"))
	req2 := httptest.NewRequest(http.MethodPost, "/v1/admin/release/upload", buf2)
	req2.Header.Set("Content-Type", contentType2)
	rec2 := httptest.NewRecorder()
	api.handleAdminAPIReleaseUpload(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("二次上传失败：%d", rec2.Code)
	}
	second := decodeBody(t, rec2)["name"].(string)
	if second == "oldchat-community-545.apk" {
		t.Fatalf("同名文件被直接覆盖")
	}
	if _, err := os.Stat(filepath.Join(api.cfg.UpdateDir, second)); err != nil {
		t.Fatalf("二次上传文件未落盘：%v", err)
	}
}

func TestReleaseUploadRejectsBadExtension(t *testing.T) {
	api := newReleaseAPI(t)
	buf, contentType := buildUploadBody(t, "file", "payload.sh", []byte("#!/bin/sh"))
	req := httptest.NewRequest(http.MethodPost, "/v1/admin/release/upload", buf)
	req.Header.Set("Content-Type", contentType)
	rec := httptest.NewRecorder()
	api.handleAdminAPIReleaseUpload(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非白名单后缀应拒绝，实际 %d", rec.Code)
	}
}

func TestReleaseDeleteKeepsReferencedArtifact(t *testing.T) {
	api := newReleaseAPI(t)
	artifact := filepath.Join(api.cfg.UpdateDir, "oldchat-community-545.apk")
	if err := os.WriteFile(artifact, []byte("apk"), 0o644); err != nil {
		t.Fatal(err)
	}
	save := `{"channel":"formal","version_name":"1.4.5.5","version_code":545,"apk":"oldchat-community-545.apk"}`
	rec := httptest.NewRecorder()
	api.handleAdminAPIReleaseSave(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/save", strings.NewReader(save)))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存失败：%d", rec.Code)
	}

	rec = httptest.NewRecorder()
	api.handleAdminAPIReleaseDelete(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/delete", strings.NewReader(`{"name":"oldchat-community-545.apk"}`)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("被引用的安装包不应允许删除，实际 %d", rec.Code)
	}
	if _, err := os.Stat(artifact); err != nil {
		t.Fatalf("文件被误删：%v", err)
	}

	// 未引用的旧包可以删除
	stale := filepath.Join(api.cfg.UpdateDir, "oldchat-community-544.apk")
	if err := os.WriteFile(stale, []byte("apk"), 0o644); err != nil {
		t.Fatal(err)
	}
	rec = httptest.NewRecorder()
	api.handleAdminAPIReleaseDelete(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/delete", strings.NewReader(`{"name":"oldchat-community-544.apk"}`)))
	if rec.Code != http.StatusOK {
		t.Fatalf("未引用文件应可删除，实际 %d", rec.Code)
	}
}

func TestReleaseDeleteRejectsTraversal(t *testing.T) {
	api := newReleaseAPI(t)
	outside := filepath.Join(filepath.Dir(api.cfg.UpdateDir), "escape.apk")
	if err := os.WriteFile(outside, []byte("apk"), 0o644); err != nil {
		t.Fatal(err)
	}
	rec := httptest.NewRecorder()
	api.handleAdminAPIReleaseDelete(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/release/delete", strings.NewReader(`{"name":"../escape.apk"}`)))
	if rec.Code != http.StatusNotFound && rec.Code != http.StatusBadRequest {
		t.Fatalf("路径穿越应被拒绝，实际 %d", rec.Code)
	}
	if _, err := os.Stat(outside); err != nil {
		t.Fatalf("目录外文件被删除：%v", err)
	}
}

func TestClientsDefaultEntriesCoverWPF(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)
	api := &API{cfg: config.Config{UpdateDir: "update"}}
	rec := httptest.NewRecorder()
	api.handleAdminAPIClientsGet(rec, httptest.NewRequest(http.MethodGet, "/v1/admin/clients", nil))
	clients, _ := decodeBody(t, rec)["clients"].([]any)

	var ids []string
	for _, item := range clients {
		ids = append(ids, item.(map[string]any)["id"].(string))
	}
	for _, want := range []string{"android", "wpf", "web"} {
		found := false
		for _, id := range ids {
			if id == want {
				found = true
			}
		}
		if !found {
			t.Fatalf("默认客户端清单缺少 %q：%v", want, ids)
		}
	}
}

func TestClientsRegistryRoundTrip(t *testing.T) {
	dir := t.TempDir()
	t.Chdir(dir)

	api := &API{cfg: config.Config{UpdateDir: filepath.Join(dir, "update")}}
	rec := httptest.NewRecorder()
	api.handleAdminAPIClientsGet(rec, httptest.NewRequest(http.MethodGet, "/v1/admin/clients", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("首次读取失败：%d", rec.Code)
	}
	defaults, _ := decodeBody(t, rec)["clients"].([]any)
	if len(defaults) == 0 {
		t.Fatalf("首次读取应返回默认条目")
	}

	payload := `{"clients":[{"id":"android","name":"Android 客户端","platform":"Android","version":"1.4.5.5","description":"适配 Android 2.3 及以上","download_url":"","enabled":true},{"id":"","name":"Windows 客户端","platform":"Windows","version":"","description":"","download_url":"","enabled":false}]}`
	rec = httptest.NewRecorder()
	api.handleAdminAPIClientsSave(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/clients", strings.NewReader(payload)))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存失败：%d %s", rec.Code, rec.Body.String())
	}

	rec = httptest.NewRecorder()
	api.handleAdminAPIClientsGet(rec, httptest.NewRequest(http.MethodGet, "/v1/admin/clients", nil))
	saved, _ := decodeBody(t, rec)["clients"].([]any)
	if len(saved) != 2 {
		t.Fatalf("条目数量异常：%d", len(saved))
	}
	windows := saved[1].(map[string]any)
	if windows["id"] == "" {
		t.Fatalf("空 ID 应自动补全：%+v", windows)
	}
	if windows["download_url"] != "" {
		t.Fatalf("下载地址应保持为空：%+v", windows)
	}
	if windows["enabled"] != false {
		t.Fatalf("显示开关状态异常：%+v", windows)
	}
}

func TestClientsSaveSkipsNameless(t *testing.T) {	t.Chdir(t.TempDir())
	api := &API{cfg: config.Config{UpdateDir: "update"}}
	rec := httptest.NewRecorder()
	body := `{"clients":[{"name":"  "},{"name":"Web 版","platform":"Web"}]}`
	api.handleAdminAPIClientsSave(rec, httptest.NewRequest(http.MethodPost, "/v1/admin/clients", strings.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存失败：%d", rec.Code)
	}
	clients := decodeBody(t, rec)["clients"].([]any)
	if len(clients) != 1 {
		t.Fatalf("空名称条目应被丢弃，实际 %d", len(clients))
	}
}

func TestSanitizeReleaseFileName(t *testing.T) {
	cases := map[string]string{
		"oldchat.apk":            "oldchat.apk",
		`C:\build\oldchat.apk`:   "oldchat.apk",
		"../../etc/passwd":       "passwd",
		".hidden.apk":            "hidden.apk",
		"bad:name?.apk":          "badname.apk",
		"   ":                    "",
		"a/b/c/oldchat-545.apk":  "oldchat-545.apk",
	}
	for input, want := range cases {
		if got := sanitizeReleaseFileName(input); got != want {
			t.Fatalf("sanitize(%q) = %q，期望 %q", input, got, want)
		}
	}
}

func TestClientEntryID(t *testing.T) {
	if got := clientEntryID("Android 客户端", 1); got != "android" {
		t.Fatalf("中文名称应回落到 ascii 片段，实际 %q", got)
	}
	if got := clientEntryID("Web Client", 2); got != "web-client" {
		t.Fatalf("空格应转为连字符，实际 %q", got)
	}
	if got := clientEntryID("客户端", 3); got != "client-3" {
		t.Fatalf("纯中文应使用序号兜底，实际 %q", got)
	}
}
