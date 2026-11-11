package httpapi

import (
	"net/http"
	"strings"
)

func supportsBurnSecureView(r *http.Request) bool {
	if r == nil {
		return false
	}
	flag := strings.TrimSpace(r.Header.Get("X-Burn-Secure"))
	if flag == "1" {
		return true
	}
	return strings.EqualFold(flag, "true")
}
