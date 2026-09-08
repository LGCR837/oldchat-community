package httpapi

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"

	"metrochat/internal/data"
)

const (
	aiMaxRequestBytes  = 1 << 20
	aiDefaultMaxOutput = int64(8192)
	aiMaxOutput        = int64(16384)
)

func (a *API) handleAIQuota(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	quota, err := a.aiQuotas.Get(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "quota_failed", "failed to load AI quota")
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"token_limit": quota.TokenLimit, "used_tokens": quota.UsedTokens,
		"reserved_tokens": quota.ReservedTokens, "remaining_tokens": quota.Remaining(),
		"model": a.cfg.OpenCodeGoModel, "enabled": a.cfg.OpenCodeGoAPIKey != "",
	})
}

func (a *API) handleAIChatCompletions(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	if a.cfg.OpenCodeGoAPIKey == "" {
		writeError(w, http.StatusServiceUnavailable, "ai_not_configured", "AI service is not configured")
		return
	}
	if r.ContentLength > aiMaxRequestBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "request_too_large", "AI request is too large")
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, aiMaxRequestBytes+1))
	if err != nil || len(raw) == 0 {
		writeError(w, http.StatusBadRequest, "bad_body", "invalid request body")
		return
	}
	if len(raw) > aiMaxRequestBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "request_too_large", "AI request is too large")
		return
	}
	var payload map[string]interface{}
	if err = json.Unmarshal(raw, &payload); err != nil {
		writeError(w, http.StatusBadRequest, "bad_json", "invalid JSON")
		return
	}
	messages, ok := payload["messages"].([]interface{})
	if !ok || len(messages) == 0 || len(messages) > 256 {
		writeError(w, http.StatusBadRequest, "bad_messages", "messages are required")
		return
	}

	maxOutput := readPositiveJSONInt(payload["max_tokens"], aiDefaultMaxOutput)
	if value := readPositiveJSONInt(payload["max_completion_tokens"], 0); value > 0 {
		maxOutput = value
	}
	if maxOutput > aiMaxOutput {
		maxOutput = aiMaxOutput
	}
	payload["model"] = a.cfg.OpenCodeGoModel
	payload["max_tokens"] = maxOutput
	delete(payload, "max_completion_tokens")
	delete(payload, "user")
	streaming, _ := payload["stream"].(bool)
	if streaming {
		payload["stream_options"] = map[string]interface{}{"include_usage": true}
	}
	upstreamBody, err := json.Marshal(payload)
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_body", "invalid request body")
		return
	}

	// bytes/2 是偏保守的输入 token 上界，再加模型最大输出；实际最终以上游 usage 结算。
	inputFallback := int64((len(upstreamBody) + 1) / 2)
	reservation := inputFallback + maxOutput
	reserveCtx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	err = a.aiQuotas.Reserve(reserveCtx, claims.Subject, reservation)
	cancel()
	if errors.Is(err, data.ErrAIQuotaExceeded) {
		writeError(w, http.StatusPaymentRequired, "ai_quota_exhausted", "AI token quota exhausted")
		return
	}
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "quota_failed", "failed to reserve AI quota")
		return
	}

	settled := false
	settle := func(actual int64) {
		if settled {
			return
		}
		settled = true
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = a.aiQuotas.Settle(ctx, claims.Subject, reservation, actual)
	}
	defer func() {
		if !settled {
			settle(0)
		}
	}()

	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, a.cfg.OpenCodeGoEndpoint, bytes.NewReader(upstreamBody))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "proxy_failed", "failed to create upstream request")
		return
	}
	req.Header.Set("Authorization", "Bearer "+a.cfg.OpenCodeGoAPIKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", func() string {
		if streaming {
			return "text/event-stream"
		}
		return "application/json"
	}())
	resp, err := a.aiProxyClient.Do(req)
	if err != nil {
		writeError(w, http.StatusBadGateway, "upstream_unavailable", "AI upstream is unavailable")
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		writeError(w, http.StatusBadGateway, "upstream_error", fmt.Sprintf("AI upstream returned status %d", resp.StatusCode))
		return
	}

	quota, _ := a.aiQuotas.Get(r.Context(), claims.Subject)
	w.Header().Set("X-AI-Model", a.cfg.OpenCodeGoModel)
	w.Header().Set("X-AI-Remaining-Tokens", strconv.FormatInt(quota.Remaining(), 10))
	w.Header().Set("Cache-Control", "no-cache, no-transform")
	w.Header().Set("X-Accel-Buffering", "no")
	if streaming {
		a.relayAIStream(w, resp.Body, inputFallback, settle)
		return
	}
	a.relayAIJSON(w, resp.Body, inputFallback, settle)
}

func (a *API) relayAIJSON(w http.ResponseWriter, body io.Reader, fallback int64, settle func(int64)) {
	raw, err := io.ReadAll(io.LimitReader(body, 8<<20))
	if err != nil {
		settle(0)
		writeError(w, http.StatusBadGateway, "upstream_read_failed", "failed to read AI response")
		return
	}
	actual := usageTotal(raw)
	if actual <= 0 {
		actual = fallback + int64((len(raw)+3)/4)
	}
	settle(actual)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(raw)
}

func (a *API) relayAIStream(w http.ResponseWriter, body io.Reader, fallback int64, settle func(int64)) {
	w.Header().Set("Content-Type", "text/event-stream; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	scanner := bufio.NewScanner(body)
	scanner.Buffer(make([]byte, 32<<10), 2<<20)
	var usage, streamedBytes int64
	for scanner.Scan() {
		line := scanner.Bytes()
		if bytes.HasPrefix(line, []byte("data:")) {
			value := bytes.TrimSpace(bytes.TrimPrefix(line, []byte("data:")))
			if !bytes.Equal(value, []byte("[DONE]")) {
				if n := usageTotal(value); n > 0 {
					usage = n
				}
				streamedBytes += int64(len(value))
			}
		}
		if _, err := w.Write(append(append([]byte(nil), line...), '\n')); err != nil {
			break
		}
		if flusher != nil {
			flusher.Flush()
		}
	}
	if usage <= 0 {
		usage = fallback + (streamedBytes+3)/4
	}
	settle(usage)
}

func usageTotal(raw []byte) int64 {
	var value struct {
		Usage struct {
			Total int64 `json:"total_tokens"`
		} `json:"usage"`
	}
	if json.Unmarshal(raw, &value) == nil {
		return value.Usage.Total
	}
	return 0
}

func readPositiveJSONInt(value interface{}, fallback int64) int64 {
	switch v := value.(type) {
	case float64:
		if v > 0 {
			return int64(v)
		}
	case json.Number:
		if n, err := v.Int64(); err == nil && n > 0 {
			return n
		}
	}
	return fallback
}
