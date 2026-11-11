package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func (a *API) handleLanding(w http.ResponseWriter, r *http.Request) {
	versionText := "最新版"
	if release := a.loadLandingRelease(); release.VersionName != "" {
		versionText = "当前版本 " + release.VersionName
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = appLandingTpl.Execute(w, appLandingData{VersionText: versionText})
}

func (a *API) handleRegisterPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, renderRegisterPage(a.cfg))
}

func (a *API) handleAppAPKDownload(w http.ResponseWriter, r *http.Request) {
	release := a.loadLandingRelease()
	rawApk := strings.TrimSpace(release.APK)
	if strings.HasPrefix(rawApk, "http://") || strings.HasPrefix(rawApk, "https://") {
		http.Redirect(w, r, rawApk, http.StatusFound)
		return
	}
	apkName := filepath.Base(rawApk)
	if apkName == "" || apkName == "." || apkName == ".." || !strings.HasSuffix(strings.ToLower(apkName), ".apk") {
		http.Redirect(w, r, "/update/", http.StatusFound)
		return
	}
	apkPath := filepath.Join(a.cfg.UpdateDir, apkName)
	if info, err := os.Stat(apkPath); err != nil || info.IsDir() {
		http.Redirect(w, r, "/update/", http.StatusFound)
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="oldchat.apk"`)
	http.ServeFile(w, r, apkPath)
}

func (a *API) handleAppDevAPKDownload(w http.ResponseWriter, r *http.Request) {
	release := a.loadDevLandingRelease()
	rawApk := strings.TrimSpace(release.APK)
	if strings.HasPrefix(rawApk, "http://") || strings.HasPrefix(rawApk, "https://") {
		http.Redirect(w, r, rawApk, http.StatusFound)
		return
	}
	apkName := filepath.Base(rawApk)
	if apkName == "" || apkName == "." || apkName == ".." || !strings.HasSuffix(strings.ToLower(apkName), ".apk") {
		http.Redirect(w, r, "/update/", http.StatusFound)
		return
	}
	apkPath := filepath.Join(a.cfg.UpdateDir, apkName)
	if info, err := os.Stat(apkPath); err != nil || info.IsDir() {
		http.Redirect(w, r, "/update/", http.StatusFound)
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="oldchat-dev.apk"`)
	http.ServeFile(w, r, apkPath)
}

func (a *API) handleAppWindowsDownload(w http.ResponseWriter, r *http.Request) {
	exePath := filepath.Join(a.cfg.UpdateDir, "oldchat-windows-x86-offical.exe")
	if info, err := os.Stat(exePath); err != nil || info.IsDir() {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Disposition", `attachment; filename="oldchat-windows-x86-offical.exe"`)
	http.ServeFile(w, r, exePath)
}

func (a *API) loadLandingRelease() landingRelease {
	var release landingRelease
	body, err := os.ReadFile(filepath.Join(a.cfg.UpdateDir, "update.json"))
	if err != nil {
		return release
	}
	if err := json.Unmarshal(body, &release); err != nil {
		return landingRelease{}
	}
	return release
}

func (a *API) loadDevLandingRelease() landingRelease {
	var release landingRelease
	body, err := os.ReadFile(filepath.Join(a.cfg.UpdateDir, "dev_update.json"))
	if err != nil {
		return release
	}
	if err := json.Unmarshal(body, &release); err != nil {
		return landingRelease{}
	}
	return release
}
