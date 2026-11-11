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
	groupBurnCleanupInterval = 2 * time.Second
	groupBurnCleanupBatch    = 100
)

func StartGroupBurnCleanup(api *API) {
	if api == nil || api.groupMsgs == nil || api.groups == nil || api.wsHub == nil {
		return
	}
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("group_burn_cleanup: panic: %v", r)
			}
		}()
		runGroupBurnCleanupOnce(api)
		ticker := time.NewTicker(groupBurnCleanupInterval)
		defer ticker.Stop()
		for range ticker.C {
			runGroupBurnCleanupOnce(api)
		}
	}()
}

func runGroupBurnCleanupOnce(api *API) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	expired, err := api.groupMsgs.ListExpiredBurnMessages(ctx, groupBurnCleanupBatch)
	if err != nil || len(expired) == 0 {
		return
	}

	for _, row := range expired {
		if row.ID == "" || row.GroupID == "" {
			continue
		}
		if err := api.groupMsgs.MarkMessageBurned(ctx, row.ID); err != nil {
			if errors.Is(err, data.ErrNotFound) {
				continue
			}
			continue
		}
		memberIDs, err := api.groups.ListMemberIDs(ctx, row.GroupID)
		if err != nil || len(memberIDs) == 0 {
			continue
		}
		env := wsEnvelope{
			Type: "group_recall",
			Data: map[string]interface{}{
				"message_id": row.ID,
				"group_id":   row.GroupID,
				"from_uid":   row.SenderUID,
			},
		}
		payload, marshalErr := json.Marshal(env)
		if marshalErr != nil {
			continue
		}
		api.wsHub.BroadcastToUsers(memberIDs, payload)
	}
}
