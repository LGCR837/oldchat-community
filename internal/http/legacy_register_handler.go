package httpapi

import (
	"net/http"
)

// legacyRegisterResponse 旧客户端调用 /auth/register 时返回的引导信息。
// 客户端注册已迁移到官网：https://oc.mcl0.dpdns.org/register
type legacyRegisterResponse struct {
	Code        string `json:"code"`
	Error       string `json:"error"`
	Message     string `json:"message"`
	RegisterURL string `json:"register_url"`
}

const legacyRegisterGuideURL = "https://oc.mcl0.dpdns.org/register"
const legacyRegisterGuideMessage = "请前往 https://oc.mcl0.dpdns.org/register 注册"

// handleLegacyRegister 兼容旧客户端：注册已迁移到官网，返回引导信息（HTTP 200）。
// 同时保留旧版请求体结构，避免旧客户端解析失败。
func (a *API) handleLegacyRegister(w http.ResponseWriter, r *http.Request) {
	if !requireJSON(w, r) {
		return
	}
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Email    string `json:"email"`
	}
	_ = decodeJSON(w, r, &req)

	writeJSON(w, http.StatusOK, legacyRegisterResponse{
		Code:        "register_moved",
		Error:       legacyRegisterGuideMessage,
		Message:     legacyRegisterGuideMessage,
		RegisterURL: legacyRegisterGuideURL,
	})
}
