package httpapi

import (
	"net/http"
	"path/filepath"
)

func (a *API) handleCoinToolPage(w http.ResponseWriter, r *http.Request) {
	if a == nil || a.webAppDir == "" {
		if a != nil {
			a.handleLanding(w, r)
			return
		}
		http.NotFound(w, r)
		return
	}
	http.ServeFile(w, r, filepath.Join(a.webAppDir, "coin-tool.html"))
}
