package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"metrochat/internal/data"
)

var (
	errInvalidUserIdentity  = errors.New("invalid user identity")
	errUserIdentityMismatch = errors.New("user identity mismatch")
)

// resolveUserIdentity resolves an external user reference. NCUID is the stable
// identity and therefore takes precedence; UID remains as a legacy fallback.
// When both are supplied they must resolve to the same internal user ID.
func (a *API) resolveUserIdentity(ctx context.Context, uid, ncuid string) (*data.User, error) {
	uid = strings.ToUpper(strings.TrimSpace(uid))
	ncuid = strings.TrimSpace(ncuid)
	if uid == "" && ncuid == "" {
		return nil, errInvalidUserIdentity
	}
	if ncuid != "" {
		if !isValidNcuid(ncuid) {
			return nil, errInvalidUserIdentity
		}
		user, err := a.users.GetByNcuid(ctx, ncuid)
		if err != nil {
			return nil, err
		}
		if uid != "" {
			if !isValidUID(uid) {
				return nil, errInvalidUserIdentity
			}
			legacyUser, legacyErr := a.users.GetByUID(ctx, uid)
			if errors.Is(legacyErr, data.ErrNotFound) {
				// UID may be a stale display snapshot after the user changed it;
				// the stable NCUID remains authoritative.
				return user, nil
			}
			if legacyErr != nil {
				return nil, legacyErr
			}
			if legacyUser.ID != user.ID {
				return nil, errUserIdentityMismatch
			}
		}
		return user, nil
	}
	if !isValidUID(uid) {
		return nil, errInvalidUserIdentity
	}
	return a.users.GetByUID(ctx, uid)
}

func writeUserIdentityError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, errInvalidUserIdentity):
		writeError(w, http.StatusBadRequest, "invalid_identity", "uid or ncuid is required")
	case errors.Is(err, errUserIdentityMismatch):
		writeError(w, http.StatusConflict, "identity_mismatch", "uid and ncuid refer to different users")
	case errors.Is(err, data.ErrNotFound):
		writeError(w, http.StatusNotFound, "user_not_found", "user not found")
	default:
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
	}
}
