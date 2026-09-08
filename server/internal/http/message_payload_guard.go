package httpapi

import (
	"context"
	"encoding/json"
	"strings"

	"metrochat/internal/data"
)

const (
	messagePayloadGuardVersion       = 2
	messagePayloadGuardVersionLegacy = 1
	messagePayloadForwardMaxItems    = 30
	messagePayloadQuotePreviewMaxLen = 96
)

type messagePayloadGuard struct {
	V         int                          `json:"v"`
	Text      string                       `json:"text"`
	MediaKind string                       `json:"media_kind,omitempty"`
	VoiceText string                       `json:"voice_text,omitempty"`
	Quote     *messagePayloadQuoteGuard    `json:"quote,omitempty"`
	Mentions  []messagePayloadMentionGuard `json:"mentions,omitempty"`
	Forward   *messagePayloadForwardGuard  `json:"forward_v2,omitempty"`
}

type messagePayloadQuoteGuard struct {
	ID        string `json:"id"`
	FromUID   string `json:"from_uid"`
	FromNcuid string `json:"from_ncuid,omitempty"`
	FromName  string `json:"from_name"`
	Type      string `json:"type"`
	Text      string `json:"text"`
	MediaKind string `json:"media_kind,omitempty"`
	ThumbURL  string `json:"thumb_url,omitempty"`
}

type messagePayloadMentionGuard struct {
	UID   string `json:"uid"`
	Ncuid string `json:"ncuid,omitempty"`
	Name  string `json:"name"`
}

type messagePayloadForwardGuard struct {
	Title string                           `json:"title"`
	Items []messagePayloadForwardItemGuard `json:"items"`
}

type messagePayloadForwardItemGuard struct {
	SourceMessageID string `json:"sid,omitempty"`
	FromUID         string `json:"from_uid"`
	FromName        string `json:"from_name"`
	FromAvatar      string `json:"from_avatar,omitempty"`
	Type            string `json:"type"`
	MediaKind       string `json:"media_kind,omitempty"`
	Text            string `json:"text"`
}

func (a *API) sanitizeMessageBodyForSend(ctx context.Context, senderID, scopeID, msgType, body string) (string, bool) {
	trimmedBody := strings.TrimSpace(body)
	if trimmedBody == "" {
		return "", true
	}

	rawPayload, hasRawPayload := parseRawPayloadMap(trimmedBody)
	if isRejectedLegacyPayload(trimmedBody) {
		return "", false
	}
	if hasRawPayload && rawPayloadHasStructuredFields(rawPayload) {
		if _, ok := parseGuardPayload(trimmedBody); !ok {
			return "", false
		}
	}

	payload, ok := parseGuardPayload(trimmedBody)
	if !ok {
		return trimmedBody, true
	}

	rawQuoteID := ""
	if payload.Quote != nil {
		rawQuoteID = strings.TrimSpace(payload.Quote.ID)
	}
	rawForwardExpected := 0
	if payload.Forward != nil {
		rawForwardExpected = len(payload.Forward.Items)
		if rawForwardExpected > messagePayloadForwardMaxItems {
			rawForwardExpected = messagePayloadForwardMaxItems
		}
	}

	payload.Text = strings.TrimSpace(payload.Text)
	payload.MediaKind = strings.TrimSpace(payload.MediaKind)
	payload.VoiceText = strings.TrimSpace(payload.VoiceText)

	a.sanitizePayloadQuote(ctx, senderID, scopeID, payload)
	a.sanitizePayloadMentions(ctx, payload)
	if !a.verifyForwardBundleBeforeDispatch(ctx, senderID, payload, rawForwardExpected) {
		return "", false
	}

	// 如果引用的消息已被撤回/删除/不可访问，降级为普通文本消息发送，不阻断正常文本发送。
	if rawQuoteID != "" && (payload.Quote == nil || strings.TrimSpace(payload.Quote.ID) == "") {
		payload.Quote = nil
	}

	if !payloadHasExtras(payload) {
		if payload.Text != "" {
			return payload.Text, true
		}
		return "", true
	}

	if payload.Text == "" && strings.EqualFold(strings.TrimSpace(msgType), "text") {
		payload.Text = "..."
	}

	encoded, err := json.Marshal(payload)
	if err != nil {
		if payload.Text != "" {
			return payload.Text, true
		}
		return "", true
	}
	return string(encoded), true
}

func (a *API) verifyForwardBundleBeforeDispatch(ctx context.Context, senderID string, payload *messagePayloadGuard, rawForwardExpected int) bool {
	if payload == nil {
		return rawForwardExpected == 0
	}
	a.sanitizePayloadForward(ctx, senderID, payload)
	if rawForwardExpected <= 0 {
		return true
	}
	if payload.Forward == nil {
		return false
	}
	return len(payload.Forward.Items) == rawForwardExpected
}

func parseGuardPayload(body string) (*messagePayloadGuard, bool) {
	trimmed := strings.TrimSpace(body)
	if !strings.HasPrefix(trimmed, "{") || !strings.HasSuffix(trimmed, "}") {
		return nil, false
	}
	var payload messagePayloadGuard
	if err := json.Unmarshal([]byte(trimmed), &payload); err != nil {
		return nil, false
	}
	if payload.V != messagePayloadGuardVersion {
		return nil, false
	}
	return &payload, true
}

func (a *API) sanitizePayloadQuote(ctx context.Context, senderID, scopeID string, payload *messagePayloadGuard) {
	if payload == nil || payload.Quote == nil {
		return
	}
	quoteID := strings.TrimSpace(payload.Quote.ID)
	if quoteID == "" {
		payload.Quote = nil
		return
	}
	ref, ok := a.resolveAccessibleMessageReference(ctx, senderID, scopeID, quoteID)
	if !ok {
		payload.Quote = nil
		return
	}
	payload.Quote = &messagePayloadQuoteGuard{
		ID:        ref.MessageID,
		FromUID:   ref.FromUID,
		FromNcuid: ref.FromNcuid,
		FromName:  ref.FromName,
		Type:      ref.Type,
		Text:      ref.Text,
		MediaKind: ref.MediaKind,
		ThumbURL:  ref.ThumbURL,
	}
}

func (a *API) sanitizePayloadMentions(ctx context.Context, payload *messagePayloadGuard) {
	if payload == nil || len(payload.Mentions) == 0 {
		return
	}
	sanitized := make([]messagePayloadMentionGuard, 0, len(payload.Mentions))
	for i := 0; i < len(payload.Mentions); i++ {
		if len(sanitized) >= 30 {
			break
		}
		uid := strings.ToUpper(strings.TrimSpace(payload.Mentions[i].UID))
		ncuid := strings.ToUpper(strings.TrimSpace(payload.Mentions[i].Ncuid))
		uidOK := isValidUID(uid)
		ncuidOK := isValidUID(ncuid)
		if !uidOK && !ncuidOK {
			continue
		}
		nameRunes := []rune(strings.TrimSpace(payload.Mentions[i].Name))
		name := string(nameRunes)
		if len(nameRunes) > 64 {
			name = string(nameRunes[:64])
		}
		// 双字段齐全且格式合法：信任客户端（快速路径），避免每条 mention 都查库。
		if uidOK && ncuidOK {
			sanitized = append(sanitized, messagePayloadMentionGuard{UID: uid, Ncuid: ncuid, Name: name})
			continue
		}
		// 仅提供单字段：查库补齐缺失身份并校验用户存在。
		user, err := a.lookupUserByIdentity(ctx, uid, ncuid)
		if err != nil || user == nil {
			continue
		}
		canonicalUID := strings.TrimSpace(user.UID)
		canonicalNcuid := strings.TrimSpace(user.Ncuid)
		if uidOK {
			canonicalUID = uid
		}
		if ncuidOK {
			canonicalNcuid = ncuid
		}
		sanitized = append(sanitized, messagePayloadMentionGuard{UID: canonicalUID, Ncuid: canonicalNcuid, Name: name})
	}
	payload.Mentions = sanitized
}

// lookupUserByIdentity 优先按不可变 ncuid 查询，回退按 uid 查询，用于补齐 mention 身份。
func (a *API) lookupUserByIdentity(ctx context.Context, uid, ncuid string) (*data.User, error) {
	if ncuid != "" {
		if u, err := a.users.GetByNcuid(ctx, ncuid); err == nil && u != nil {
			return u, nil
		}
	}
	if uid != "" {
		if u, err := a.users.GetByUID(ctx, uid); err == nil && u != nil {
			return u, nil
		}
	}
	return nil, data.ErrNotFound
}

func payloadHasExtras(payload *messagePayloadGuard) bool {
	if payload == nil {
		return false
	}
	if payload.Quote != nil {
		return true
	}
	if len(payload.Mentions) > 0 {
		return true
	}
	if payload.MediaKind != "" || payload.VoiceText != "" {
		return true
	}
	return payload.Forward != nil && len(payload.Forward.Items) > 0
}
