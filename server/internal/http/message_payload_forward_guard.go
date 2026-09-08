package httpapi

import (
	"context"
	"strings"
)

func (a *API) sanitizePayloadForward(ctx context.Context, senderID string, payload *messagePayloadGuard) {
	if payload == nil || payload.Forward == nil || len(payload.Forward.Items) == 0 {
		payload.Forward = nil
		return
	}
	items := payload.Forward.Items
	if len(items) > messagePayloadForwardMaxItems {
		items = items[:messagePayloadForwardMaxItems]
	}
	sanitized := make([]messagePayloadForwardItemGuard, 0, len(items))
	for i := 0; i < len(items); i++ {
		sourceID := strings.TrimSpace(items[i].SourceMessageID)
		if sourceID == "" {
			continue
		}
		ref, ok := a.resolveAccessibleMessageReference(ctx, senderID, "", sourceID)
		if !ok {
			continue
		}
		sanitized = append(sanitized, messagePayloadForwardItemGuard{
			SourceMessageID: ref.MessageID,
			FromUID:         ref.FromUID,
			FromName:        ref.FromName,
			FromAvatar:      ref.FromAvatar,
			Type:            ref.Type,
			MediaKind:       ref.MediaKind,
			Text:            ref.Text,
		})
	}
	if len(sanitized) == 0 {
		payload.Forward = nil
		return
	}
	title := strings.TrimSpace(payload.Forward.Title)
	if title == "" {
		title = "聊天记录"
	}
	titleRunes := []rune(title)
	if len(titleRunes) > 32 {
		title = string(titleRunes[:32])
	}
	payload.Forward.Title = title
	payload.Forward.Items = sanitized
	if strings.TrimSpace(payload.Text) == "" {
		payload.Text = "[聊天记录]"
	}
}
