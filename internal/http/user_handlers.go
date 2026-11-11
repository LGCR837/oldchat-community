package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"

	"metrochat/internal/data"
)

type userProfileResponse struct {
	userResponse
	IsFriend bool `json:"is_friend"`
}

func (a *API) handleUserProfile(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	// 新客户端只使用不可变 NCUID；uid 参数继续保留给旧客户端兼容。
	ncuid := strings.TrimSpace(r.URL.Query().Get("ncuid"))
	uid := strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("uid")))
	if ncuid == "" && uid == "" {
		writeError(w, http.StatusBadRequest, "invalid_identity", "ncuid or uid required")
		return
	}
	if ncuid != "" && !isValidNcuid(ncuid) {
		writeError(w, http.StatusBadRequest, "invalid_ncuid", "invalid ncuid")
		return
	}
	if ncuid == "" && !isValidUID(uid) {
		writeError(w, http.StatusBadRequest, "invalid_uid", "invalid uid")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	var user *data.User
	var err error
	if ncuid != "" {
		user, err = a.users.GetByNcuid(ctx, ncuid)
	} else {
		user, err = a.users.GetByUID(ctx, uid)
	}
	if err != nil {
		if err == data.ErrNotFound {
			writeError(w, http.StatusNotFound, "not_found", "user not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}

	isFriend := false
	if claims.Subject != user.ID {
		isFriend, err = a.friends.AreFriends(ctx, claims.Subject, user.ID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "internal error")
			return
		}
	}

	writeJSON(w, http.StatusOK, userProfileResponse{
		userResponse: toUserResponse(user),
		IsFriend:     isFriend,
	})
}

func isValidNcuid(ncuid string) bool {
	if len(ncuid) < 4 || len(ncuid) > 64 {
		return false
	}
	for i := 0; i < len(ncuid); i++ {
		c := ncuid[i]
		if (c >= 'a' && c <= 'z') ||
			(c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '-' || c == '_' {
			continue
		}
		return false
	}
	return true
}
