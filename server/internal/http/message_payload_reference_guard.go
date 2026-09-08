package httpapi

import (
	"context"
	"strings"

	"metrochat/internal/data"
)

type verifiedMessageReference struct {
	MessageID  string
	FromUID    string
	FromNcuid  string
	FromName   string
	FromAvatar string
	Type       string
	MediaKind  string
	Text       string
	ThumbURL   string
}

const burnLockedReferencePreview = "(阅后即焚)"

func (a *API) resolveAccessibleMessageReference(ctx context.Context, senderID, scopeID, messageID string) (*verifiedMessageReference, bool) {
	messageID = strings.TrimSpace(messageID)
	if messageID == "" {
		return nil, false
	}

	directMsg, err := a.direct.GetMessageByID(ctx, messageID)
	if err == nil {
		// 群聊里不能引用私聊消息（scopeID 非空时拒绝跨会话引用）
		if scopeID != "" {
			return nil, false
		}
		thread, threadErr := a.direct.GetThreadByID(ctx, directMsg.ThreadID)
		if threadErr != nil {
			return nil, false
		}
		if thread.UserAID != senderID && thread.UserBID != senderID {
			return nil, false
		}
		if isDirectReferenceBurnLocked(directMsg) {
			return a.buildBurnLockedReference(ctx, directMsg.ID, directMsg.SenderID, ""), true
		}
		return a.buildDirectReference(ctx, directMsg), true
	}
	if err != nil && err != data.ErrNotFound {
		return nil, false
	}

	groupMsg, err := a.groupMsgs.GetByID(ctx, messageID)
	if err == nil {
		// 跨群引用防护：被引用消息必须属于当前发送所在的群
		if scopeID == "" || groupMsg.GroupID != scopeID {
			return nil, false
		}
		if _, roleErr := a.groups.GetRole(ctx, groupMsg.GroupID, senderID); roleErr != nil {
			return nil, false
		}
		if isGroupReferenceBurnLocked(groupMsg) {
			return a.buildBurnLockedReference(ctx, groupMsg.ID, groupMsg.SenderID, groupMsg.SenderNcuid), true
		}
		return a.buildGroupReference(ctx, groupMsg), true
	}
	return nil, false
}

func (a *API) buildDirectReference(ctx context.Context, msg *data.DirectMessage) *verifiedMessageReference {
	if msg == nil {
		return nil
	}
	// DirectMessage 模型不携带 SenderNcuid，buildReferenceCore 内部按 SenderID 查库补齐。
	return a.buildReferenceCore(ctx, msg.ID, msg.SenderID, "", msg.MsgType, msg.Body, msg.MediaURL, msg.ThumbURL)
}

func (a *API) buildGroupReference(ctx context.Context, msg *data.GroupMessage) *verifiedMessageReference {
	if msg == nil {
		return nil
	}
	return a.buildReferenceCore(ctx, msg.ID, msg.SenderID, msg.SenderNcuid, msg.MsgType, msg.Body, msg.MediaURL, msg.ThumbURL)
}

func isDirectReferenceBurnLocked(msg *data.DirectMessage) bool {
	if msg == nil {
		return false
	}
	return msg.BurnAfterSeconds > 0 || msg.BurnedAt != nil
}

func isGroupReferenceBurnLocked(msg *data.GroupMessage) bool {
	if msg == nil {
		return false
	}
	return msg.BurnAfterSeconds > 0 || msg.BurnedAt != nil
}

func (a *API) buildBurnLockedReference(ctx context.Context, messageID, senderID, senderNcuid string) *verifiedMessageReference {
	return a.buildReferenceCore(ctx, messageID, senderID, senderNcuid, "text", burnLockedReferencePreview, "", "")
}

func (a *API) buildReferenceCore(ctx context.Context, messageID, senderID, senderNcuid, msgType, body, mediaURL, thumbURL string) *verifiedMessageReference {
	fromUID := strings.TrimSpace(senderID)
	fromNcuid := strings.TrimSpace(senderNcuid)
	fromName := fromUID
	fromAvatar := ""
	if sender, err := a.users.GetByID(ctx, senderID); err == nil && sender != nil {
		if strings.TrimSpace(sender.UID) != "" {
			fromUID = strings.TrimSpace(sender.UID)
		}
		if strings.TrimSpace(sender.Ncuid) != "" {
			fromNcuid = strings.TrimSpace(sender.Ncuid)
		}
		fromName = resolveReferenceDisplayName(sender)
		fromAvatar = strings.TrimSpace(sender.AvatarURL)
	}

	kind, preview := extractReferencePreview(msgType, body)
	typeNorm := normalizeReferenceType(msgType)
	thumb := ""
	if typeNorm == "image" || typeNorm == "video" {
		thumb = strings.TrimSpace(thumbURL)
		if thumb == "" {
			thumb = strings.TrimSpace(mediaURL)
		}
	}

	return &verifiedMessageReference{
		MessageID:  strings.TrimSpace(messageID),
		FromUID:    fromUID,
		FromNcuid:  fromNcuid,
		FromName:   fromName,
		FromAvatar: fromAvatar,
		Type:       typeNorm,
		MediaKind:  kind,
		Text:       preview,
		ThumbURL:   thumb,
	}
}

func resolveReferenceDisplayName(user *data.User) string {
	if user == nil {
		return ""
	}
	if name := strings.TrimSpace(user.DisplayName); name != "" {
		return name
	}
	if name := strings.TrimSpace(user.Username); name != "" {
		return name
	}
	if uid := strings.TrimSpace(user.UID); uid != "" {
		return uid
	}
	return strings.TrimSpace(user.ID)
}

func extractReferencePreview(msgType, body string) (string, string) {
	payload, _ := parseGuardPayload(body)
	mediaKind := ""
	legacyMediaKind, legacyText, legacyVoiceText, legacyForward, hasLegacy := extractLegacyPayloadInfo(body)
	if payload != nil {
		mediaKind = strings.TrimSpace(payload.MediaKind)
	} else {
		mediaKind = legacyMediaKind
	}

	typeNorm := normalizeReferenceType(msgType)
	if typeNorm == "text" {
		if payload != nil {
			if payload.Forward != nil && len(payload.Forward.Items) > 0 {
				return mediaKind, "[聊天记录]"
			}
			text := compactReferenceText(payload.Text)
			if text != "" {
				return mediaKind, text
			}
		}
		if hasLegacy {
			if legacyForward {
				return mediaKind, "[聊天记录]"
			}
			text := compactReferenceText(legacyText)
			if text != "" {
				return mediaKind, text
			}
		}
		return mediaKind, compactReferenceText(body)
	}
	if typeNorm == "voice" {
		if payload != nil {
			voiceText := compactReferenceText(payload.VoiceText)
			if voiceText != "" {
				return mediaKind, voiceText
			}
		}
		if hasLegacy {
			voiceText := compactReferenceText(legacyVoiceText)
			if voiceText != "" {
				return mediaKind, voiceText
			}
		}
		return mediaKind, "[语音]"
	}
	if typeNorm == "image" {
		if mediaKind == "emoji" {
			return mediaKind, "[表情]"
		}
		return mediaKind, "[图片]"
	}
	if typeNorm == "video" {
		return mediaKind, "[视频]"
	}
	if typeNorm == "resource" {
		if mediaKind == "music" {
			return mediaKind, "[音乐]"
		}
		return mediaKind, "[资源]"
	}
	if typeNorm == "music" {
		return mediaKind, "[音乐]"
	}
	if typeNorm == "red_packet" {
		return mediaKind, "[红包]"
	}
	return mediaKind, compactReferenceText(body)
}

func extractLegacyPayloadInfo(body string) (string, string, string, bool, bool) {
	raw, ok := parseRawPayloadMap(body)
	if !ok {
		return "", "", "", false, false
	}
	if payloadVersionFromRaw(raw) != messagePayloadGuardVersionLegacy {
		return "", "", "", false, false
	}
	mediaKind := trimRawString(raw["media_kind"])
	text := trimRawString(raw["text"])
	voiceText := trimRawString(raw["voice_text"])
	_, hasForward := raw["forward"]
	return mediaKind, text, voiceText, hasForward, true
}

func normalizeReferenceType(msgType string) string {
	norm := strings.ToLower(strings.TrimSpace(msgType))
	if norm == "" {
		return "text"
	}
	return norm
}

func compactReferenceText(value string) string {
	out := strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(value, "\r", " "), "\n", " "))
	runes := []rune(out)
	if len(runes) > messagePayloadQuotePreviewMaxLen {
		out = string(runes[:messagePayloadQuotePreviewMaxLen]) + "..."
	}
	return out
}
