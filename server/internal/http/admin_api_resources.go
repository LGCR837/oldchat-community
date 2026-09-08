package httpapi

import (
	"encoding/json"
	"net/http"
)

type resourceConfigReq struct {
	QuotaGB     float64 `json:"quota_gb"`
	MediaRateMB float64 `json:"media_rate_mb"`
}

func (a *API) handleAdminAPIResources(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		quotaBytes := a.resourceQuotaBytes()
		quotaGB := float64(quotaBytes) / float64(1024*1024*1024)
		if quotaGB <= 0 {
			quotaGB = 20
		}
		mediaRate, _, _, _ := getTransferRateLimits()
		mediaRateMB := float64(mediaRate) / float64(1024*1024)
		if mediaRateMB <= 0 {
			mediaRateMB = 5
		}

		writeJSON(w, http.StatusOK, map[string]any{
			"code": 200,
			"data": map[string]any{
				"quota_gb":      quotaGB,
				"media_rate_mb": mediaRateMB,
			},
		})
		return
	}

	if r.Method == http.MethodPost {
		var req resourceConfigReq
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
			return
		}
		if req.QuotaGB > 0 {
			a.cfg.ResourceQuota = int64(req.QuotaGB * 1024 * 1024 * 1024)
		}
		if req.MediaRateMB > 0 {
			a.cfg.MediaRateBytes = int64(req.MediaRateMB * 1024 * 1024)
		}

		writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "saved"})
		return
	}

	writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
}
