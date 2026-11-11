package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"time"

	"metrochat/internal/data"
)

const (
	directBurnCleanupInterval = 2 * time.Second
	directBurnCleanupBatch    = 100
)

func StartDirectBurnCleanup(api *API) {
	if api == nil || api.direct == nil || api.wsHub == nil {
		return
	}
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("direct_burn_cleanup: panic: %v", r)
			}
		}()
		runDirectBurnCleanupOnce(api)
		ticker := time.NewTicker(directBurnCleanupInterval)
		defer ticker.Stop()
		for range ticker.C {
			runDirectBurnCleanupOnce(api)
		}
	}()
}

func runDirectBurnCleanupOnce(api *API) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	expired, err := api.direct.ListExpiredBurnMessages(ctx, directBurnCleanupBatch)
	if err != nil || len(expired) == 0 {
		return
	}

	for _, row := range expired {
		if row.ID == "" || row.ThreadID == "" || row.SenderID == "" {
			continue
		}
		if err := api.direct.MarkMessageBurned(ctx, row.ID); err != nil {
			if errors.Is(err, data.ErrNotFound) {
				continue
			}
			continue
		}
		peerID := row.UserAID
		if peerID == row.SenderID {
			peerID = row.UserBID
		}
		env := wsEnvelope{
			Type: "direct_recall",
			Data: map[string]interface{}{
				"message_id": row.ID,
				"thread_id":  row.ThreadID,
				"from_uid":   row.SenderUID,
			},
		}
		payload, marshalErr := json.Marshal(env)
		if marshalErr != nil {
			continue
		}
		api.wsHub.BroadcastToUser(row.SenderID, payload)
		if peerID != "" {
			api.wsHub.BroadcastToUser(peerID, payload)
		}
	}
}
