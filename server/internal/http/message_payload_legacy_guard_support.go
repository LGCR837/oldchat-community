package httpapi

import (
	"encoding/json"
	"strings"
)

func parseRawPayloadMap(body string) (map[string]interface{}, bool) {
	trimmed := strings.TrimSpace(body)
	if !strings.HasPrefix(trimmed, "{") || !strings.HasSuffix(trimmed, "}") {
		return nil, false
	}
	var raw map[string]interface{}
	if err := json.Unmarshal([]byte(trimmed), &raw); err != nil {
		return nil, false
	}
	if raw == nil {
		return nil, false
	}
	return raw, true
}

func payloadVersionFromRaw(raw map[string]interface{}) int {
	if raw == nil {
		return 0
	}
	value, ok := raw["v"]
	if !ok || value == nil {
		return 0
	}
	switch num := value.(type) {
	case float64:
		return int(num)
	case float32:
		return int(num)
	case int:
		return num
	case int64:
		return int(num)
	default:
		return 0
	}
}

func payloadVersionIsStrictNumber(raw map[string]interface{}) bool {
	if raw == nil {
		return false
	}
	value, ok := raw["v"]
	if !ok || value == nil {
		return false
	}
	switch value.(type) {
	case float64:
		return true
	case float32:
		return true
	case int:
		return true
	case int64:
		return true
	default:
		return false
	}
}

func trimRawString(value interface{}) string {
	str, ok := value.(string)
	if !ok {
		return ""
	}
	return strings.TrimSpace(str)
}

func isRejectedLegacyPayload(body string) bool {
	raw, ok := parseRawPayloadMap(body)
	if !ok {
		return false
	}
	if _, hasLegacyForward := raw["forward"]; hasLegacyForward {
		return true
	}
	if rawPayloadHasStructuredFields(raw) {
		if !payloadVersionIsStrictNumber(raw) {
			return true
		}
		version := payloadVersionFromRaw(raw)
		if version != messagePayloadGuardVersion {
			return true
		}
	}
	return false
}

func rawPayloadHasStructuredFields(raw map[string]interface{}) bool {
	if raw == nil {
		return false
	}
	if _, ok := raw["quote"]; ok {
		return true
	}
	if _, ok := raw["mentions"]; ok {
		return true
	}
	if _, ok := raw["media_kind"]; ok {
		return true
	}
	if _, ok := raw["voice_text"]; ok {
		return true
	}
	if _, ok := raw["forward_v2"]; ok {
		return true
	}
	return false
}
