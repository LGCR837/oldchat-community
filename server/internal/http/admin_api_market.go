package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type adminTitleAddReq struct {
	Title string `json:"title"`
	Price int    `json:"price"`
}

type adminTitleUpdateReq struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Price  int    `json:"price"`
	Active *bool  `json:"active"`
}

// handleAdminAPITitlesCatalog GET /v1/admin/titles/catalog
func (a *API) handleAdminAPITitlesCatalog(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	list, err := a.titles.List(ctx, true)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list titles")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "titles": list})
}

// handleAdminAPITitleAdd POST /v1/admin/titles/catalog/add — 新增商城称号
func (a *API) handleAdminAPITitleAdd(w http.ResponseWriter, r *http.Request) {
	var req adminTitleAddReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.Title) == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "title required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.titles.Create(ctx, strings.TrimSpace(req.Title), req.Price); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to create title: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "称号添加成功"})
}

// handleAdminAPITitleUpdate POST /v1/admin/titles/catalog/update — 修改称号
func (a *API) handleAdminAPITitleUpdate(w http.ResponseWriter, r *http.Request) {
	var req adminTitleUpdateReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if req.Title != "" || req.Price >= 0 {
		if err := a.titles.Update(ctx, req.ID, strings.TrimSpace(req.Title), req.Price); err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "failed to update title")
			return
		}
	}
	if req.Active != nil {
		if err := a.titles.SetActive(ctx, req.ID, *req.Active); err != nil {
			writeError(w, http.StatusInternalServerError, "db_error", "failed to toggle title active")
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "称号更新成功"})
}

// handleAdminAPITitleDelete POST /v1/admin/titles/catalog/delete — 删除称号
func (a *API) handleAdminAPITitleDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.titles.Delete(ctx, req.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to delete title")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "称号已删除"})
}

// handleAdminAPIEmojiPlazaList GET /v1/admin/emoji-plaza
func (a *API) handleAdminAPIEmojiPlazaList(w http.ResponseWriter, r *http.Request) {
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	pageSize := 50
	offset := (page - 1) * pageSize
	keyword := strings.TrimSpace(r.URL.Query().Get("q"))

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	list, err := a.emojis.List(ctx, keyword, pageSize, offset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to list emoji items")
		return
	}
	total, _ := a.emojis.Count(ctx, keyword)

	writeJSON(w, http.StatusOK, map[string]any{
		"code":      200,
		"emojis":    list,
		"total":     total,
		"page":      page,
		"page_size": pageSize,
	})
}

// handleAdminAPIEmojiPlazaDelete POST /v1/admin/emoji-plaza/delete — 删除违规表情
func (a *API) handleAdminAPIEmojiPlazaDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ID == "" {
		writeError(w, http.StatusBadRequest, "invalid_req", "id required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	if err := a.emojis.DeleteByID(ctx, req.ID); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "failed to delete emoji")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": 200, "message": "表情包已下架删除"})
}
