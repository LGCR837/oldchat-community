package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"golang.org/x/crypto/bcrypt"
	"metrochat/internal/data"
)

type adminCreateUserReq struct {
	Username    string `json:"username"`
	Email       string `json:"email"`
	Password    string `json:"password"`
	UID         string `json:"uid"`
	DisplayName string `json:"display_name"`
	Coins       int    `json:"coins"`
	Title       string `json:"title"`
}

type adminResetPasswordReq struct {
	UID         string `json:"uid"`
	NewPassword string `json:"new_password"`
}

type adminUpdateProfileReq struct {
	UID         string `json:"uid"`
	DisplayName string `json:"display_name"`
	AvatarURL   string `json:"avatar_url"`
	Signature   string `json:"signature"`
	NewUID      string `json:"new_uid"`
}

type adminAdjustCoinsReq struct {
	UID    string `json:"uid"`
	Amount int    `json:"amount"`
	SetTo  *int   `json:"set_to"`
}

type adminSetTitleReq struct {
	UID   string `json:"uid"`
	Title string `json:"title"`
}

// handleAdminAPICreateUser POST /v1/admin/users/create — 管理员直接创建账户
func (a *API) handleAdminAPICreateUser(w http.ResponseWriter, r *http.Request) {
	var req adminCreateUserReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_req", "json parse error")
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	req.Email = strings.TrimSpace(strings.ToLower(req.Email))
	req.Password = strings.TrimSpace(req.Password)
	if req.Username == "" || req.Email == "" || req.Password == "" {
		writeError(w, http.StatusBadRequest, "missing_fields", "username, email and password required")
		return
	}
	if len(req.Password) < 6 {
		writeError(w, http.StatusBadRequest, "password_too_short", "password min 6 chars")
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "crypto_error", "failed to hash password")
		return
	}

	rawID := make([]byte, 16)
	_, _ = rand.Read(rawID)
	userID := "usr_" + hex.EncodeToString(rawID)

	uid := strings.TrimSpace(req.UID)
	if uid == "" {
		uid, _ = newPublicID("U-", 6)
	}

	displayName := strings.TrimSpace(req.DisplayName)
	if displayName == "" {
		displayName = req.Username
	}

	user := &data.User{
		ID:           userID,
		UID:          uid,
		Username:     req.Username,
		Email:        req.Email,
		PasswordHash: string(hash),
		DisplayName:  displayName,
		CoinBalance:  req.Coins,
		UserTitle:    req.Title,
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.users.Create(ctx, user); err != nil {
		writeError(w, http.StatusBadRequest, "create_failed", err.Error())
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code":    200,
		"message": "用户创建成功",
		"data": map[string]any{
			"id":       user.ID,
			"uid":      user.UID,
			"username": user.Username,
			"email":    user.Email,
		},
	})
}

// handleAdminAPIResetPassword POST /v1/admin/users/reset-password — 强制重置密码
func (a *API) handleAdminAPIResetPassword(w http.ResponseWriter, r *http.Request) {
	var req adminResetPasswordReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" || req.NewPassword == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid and new_password required")
		return
	}
	if len(req.NewPassword) < 6 {
		writeError(w, http.StatusBadRequest, "password_too_short", "password min 6 chars")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	user, err := a.users.GetByUID(ctx, req.UID)
	if err != nil || user == nil {
		writeError(w, http.StatusNotFound, "user_not_found", "user not found")
		return
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.NewPassword), bcrypt.DefaultCost)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "crypto_error", "failed to hash password")
		return
	}

	if _, err := a.users.ResetPasswordAndRevokeAll(ctx, user.ID, string(hash)); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to reset password")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "密码重置成功，该用户旧会话已注销"})
}

// handleAdminAPIUpdateProfile POST /v1/admin/users/update-profile — 强制更新资料
func (a *API) handleAdminAPIUpdateProfile(w http.ResponseWriter, r *http.Request) {
	var req adminUpdateProfileReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	user, err := a.users.GetByUID(ctx, req.UID)
	if err != nil || user == nil {
		writeError(w, http.StatusNotFound, "user_not_found", "user not found")
		return
	}

	displayName := req.DisplayName
	if displayName == "" {
		displayName = user.DisplayName
	}

	if err := a.users.UpdateProfile(ctx, user.ID, displayName, req.AvatarURL, req.Signature, user.CoverURL); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to update profile")
		return
	}

	if req.NewUID != "" && req.NewUID != user.UID {
		if err := a.users.UpdateUIDForce(ctx, user.ID, req.NewUID); err != nil {
			writeError(w, http.StatusBadRequest, "uid_update_failed", err.Error())
			return
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "用户资料更新成功"})
}

// handleAdminAPIAdjustCoins POST /v1/admin/users/adjust-coins — 调整用户金币
func (a *API) handleAdminAPIAdjustCoins(w http.ResponseWriter, r *http.Request) {
	var req adminAdjustCoinsReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	user, err := a.users.GetByUID(ctx, req.UID)
	if err != nil || user == nil {
		writeError(w, http.StatusNotFound, "user_not_found", "user not found")
		return
	}

	targetBalance := user.CoinBalance + req.Amount
	if req.SetTo != nil {
		targetBalance = *req.SetTo
	}
	if targetBalance < 0 {
		targetBalance = 0
	}

	if err := a.users.UpdateCoinByUID(ctx, req.UID, targetBalance); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to adjust coins")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"code":         200,
		"message":      "金币调整成功",
		"coin_balance": targetBalance,
	})
}

// handleAdminAPISetTitle POST /v1/admin/users/set-title — 赋予/修改专属称号
func (a *API) handleAdminAPISetTitle(w http.ResponseWriter, r *http.Request) {
	var req adminSetTitleReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.UID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "uid required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.users.UpdateTitleByUID(ctx, req.UID, strings.TrimSpace(req.Title)); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to set title")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "称号设置成功"})
}
