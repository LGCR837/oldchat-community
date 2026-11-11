package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/gorilla/websocket"

	"metrochat/internal/auth"
	"metrochat/internal/ws"
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

func (a *API) handleWS(w http.ResponseWriter, r *http.Request) {
	claims, ok := a.authenticateForWS(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}

	sessionID := strings.TrimSpace(r.URL.Query().Get("sid"))
	var encKey, macKey []byte
	if sessionID != "" {
		keys, ok := a.sessions.Get(sessionID)
		if !ok {
			writeError(w, http.StatusBadRequest, "invalid_session", "invalid session")
			return
		}
		encKey = keys.EncKey
		macKey = keys.MacKey
	}

	conn, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	client := ws.NewClient(a.wsHub, conn, claims.Subject, encKey, macKey)
	client.OnMessage = func(current *ws.Client, payload []byte) {
		var request struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(payload, &request) != nil || request.Type != "channel_subscriptions_refresh" {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		ids, err := a.channels.ListSubscriptionIDs(ctx, current.UserID, 500)
		if err == nil {
			a.wsHub.SetChannelSubscriptions(current, ids)
		}
	}
	if ids, err := a.channels.ListSubscriptionIDs(r.Context(), claims.Subject, 500); err == nil {
		a.wsHub.SetChannelSubscriptions(client, ids)
	}
	client.Start()
}

func (a *API) authenticateForWS(r *http.Request) (*auth.AccessClaims, bool) {
	if claims, ok := a.authenticateFromHeader(r); ok {
		if !a.validateTokenVersion(r.Context(), claims.Subject, claims.Version) {
			return nil, false
		}
		return claims, true
	}
	queryToken := strings.TrimSpace(r.URL.Query().Get("token"))
	if queryToken == "" {
		return nil, false
	}
	claims, ok := a.authenticateFromToken(queryToken)
	if !ok {
		return nil, false
	}
	if !a.validateTokenVersion(r.Context(), claims.Subject, claims.Version) {
		return nil, false
	}
	return claims, true
}
