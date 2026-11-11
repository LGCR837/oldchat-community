package httpapi

import (
	"encoding/json"
	"net/http"
	"os"
	"sync/atomic"
)

type adminSystemSettingsData struct {
	RegistrationLimit        int    `json:"registration_limit"`
	ResourceQuotaGB          int64  `json:"resource_quota_gb"`
	VideoEnabled             bool   `json:"video_enabled"`
	PublicBaseURL            string `json:"public_base_url"`
	MediaRateMB              int64  `json:"media_rate_mb"`
	UpdateRateMB             int64  `json:"update_rate_mb"`
	VideoRateMB              int64  `json:"video_rate_mb"`
	MusicRateMB              int64  `json:"music_rate_mb"`
	MediaDownloadConcurrency int    `json:"media_download_concurrency"`
}

// handleAdminAPISettingsGet GET /v1/admin/settings
func (a *API) handleAdminAPISettingsGet(w http.ResponseWriter, r *http.Request) {
	quotaBytes := a.resourceQuotaBytes()
	mediaRate := atomic.LoadInt64(&mediaTransferRateBytes) / (1 << 20)
	if mediaRate <= 0 {
		mediaRate = a.cfg.MediaRateBytes / (1 << 20)
	}
	updateRate := atomic.LoadInt64(&updateTransferRateBytes) / (1 << 20)
	if updateRate <= 0 {
		updateRate = a.cfg.UpdateRateBytes / (1 << 20)
	}
	videoRate := atomic.LoadInt64(&videoTransferRateBytes) / (1 << 20)
	if videoRate <= 0 {
		videoRate = a.cfg.VideoRateBytes / (1 << 20)
	}
	musicRate := atomic.LoadInt64(&musicTransferRateBytes) / (1 << 20)
	if musicRate <= 0 {
		musicRate = a.cfg.MusicRateBytes / (1 << 20)
	}

	data := adminSystemSettingsData{
		RegistrationLimit:        a.cfg.RegistrationLimit,
		ResourceQuotaGB:          quotaBytes / (1024 * 1024 * 1024),
		VideoEnabled:             a.cfg.VideoEnabled,
		PublicBaseURL:            a.cfg.PublicBaseURL,
		MediaRateMB:              mediaRate,
		UpdateRateMB:             updateRate,
		VideoRateMB:              videoRate,
		MusicRateMB:              musicRate,
		MediaDownloadConcurrency: a.cfg.MediaDownloadConcurrency,
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "settings": data})
}

// handleAdminAPISettingsSave POST /v1/admin/settings
func (a *API) handleAdminAPISettingsSave(w http.ResponseWriter, r *http.Request) {
	var req adminSystemSettingsData
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_req", "json parse error")
		return
	}

	a.cfg.RegistrationLimit = req.RegistrationLimit
	a.cfg.VideoEnabled = req.VideoEnabled
	if req.PublicBaseURL != "" {
		a.cfg.PublicBaseURL = req.PublicBaseURL
	}
	if req.ResourceQuotaGB > 0 {
		a.cfg.ResourceQuota = req.ResourceQuotaGB * 1024 * 1024 * 1024
	}
	if req.MediaRateMB > 0 {
		atomic.StoreInt64(&mediaTransferRateBytes, req.MediaRateMB<<20)
	}
	if req.UpdateRateMB > 0 {
		atomic.StoreInt64(&updateTransferRateBytes, req.UpdateRateMB<<20)
	}
	if req.VideoRateMB > 0 {
		atomic.StoreInt64(&videoTransferRateBytes, req.VideoRateMB<<20)
	}
	if req.MusicRateMB > 0 {
		atomic.StoreInt64(&musicTransferRateBytes, req.MusicRateMB<<20)
	}

	// 持久化到 settings.json
	settingsFile := "settings.json"
	diskData, _ := os.ReadFile(settingsFile)
	var obj map[string]any
	if len(diskData) > 0 {
		_ = json.Unmarshal(diskData, &obj)
	}
	if obj == nil {
		obj = make(map[string]any)
	}
	obj["registration_limit"] = req.RegistrationLimit
	obj["video_enabled"] = req.VideoEnabled
	obj["resource_quota_bytes"] = req.ResourceQuotaGB * 1024 * 1024 * 1024
	obj["public_base_url"] = req.PublicBaseURL

	newBytes, _ := json.MarshalIndent(obj, "", "  ")
	_ = os.WriteFile(settingsFile, newBytes, 0644)

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "全站系统配置已热更新并保存"})
}
