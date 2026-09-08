package httpapi

import (
	"context"
	"net/http"
	"strings"
	"time"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

func (a *API) handleReportPage(w http.ResponseWriter, r *http.Request) {
	a.renderTitleShop(w, r, "", "")
}

func (a *API) handleReportSubmit(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := r.ParseForm(); err != nil {
		a.renderTitleShop(w, r, "提交失败，请稍后再试", "")
		return
	}
	customRaw := strings.TrimSpace(r.FormValue("custom_title"))
	titleID := strings.TrimSpace(r.FormValue("title_id"))
	token := extractShopToken(r)
	if token == "" {
		a.renderTitleShop(w, r, "请先登录后再购买", "")
		return
	}
	if titleID == "" && customRaw == "" {
		a.renderTitleShop(w, r, "请选择要购买的称号", "")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 4*time.Second)
	defer cancel()
	user, authErr := a.shopUserFromToken(ctx, token)
	if authErr != "" || user == nil {
		a.renderTitleShop(w, r, authErr, "")
		return
	}

	if customRaw != "" {
		customTitle := sanitizeShopTitle(customRaw)
		if customTitle == "" {
			a.renderTitleShop(w, r, "称号不能为空", "")
			return
		}
		if err := a.purchaseCustomTitle(ctx, user, customTitle); err != "" {
			a.renderTitleShop(w, r, err, "")
			return
		}
		a.renderTitleShop(w, r, "", "购买成功，称号已生效")
		return
	}

	titleRow, err := a.titles.GetByID(ctx, titleID)
	if err != nil || titleRow == nil || titleRow.Active == 0 || titleRow.IsCustom != 0 {
		a.renderTitleShop(w, r, "称号不可用或已下架", "")
		return
	}
	if titleRow.OwnerID.Valid && strings.TrimSpace(titleRow.OwnerID.String) != "" {
		a.renderTitleShop(w, r, "该称号已被购买", "")
		return
	}
	price := titleRow.Price
	if price <= 0 {
		price = 100
	}

	tx, err := a.db.BeginTxx(ctx, nil)
	if err != nil {
		a.renderTitleShop(w, r, "系统繁忙，请稍后再试", "")
		return
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()

	var current struct {
		UserTitle      string `db:"user_title"`
		UserTitlePrice int    `db:"user_title_price"`
		CoinBalance    int    `db:"coin_balance"`
	}
	if err = tx.GetContext(ctx, &current, `SELECT user_title, user_title_price, coin_balance FROM users WHERE id = $1`, user.ID); err != nil {
		a.renderTitleShop(w, r, "账号信息读取失败", "")
		return
	}
	if strings.TrimSpace(current.UserTitle) == strings.TrimSpace(titleRow.Title) {
		a.renderTitleShop(w, r, "该称号已拥有，无需重复购买", "")
		return
	}

	refund := 0
	if strings.TrimSpace(current.UserTitle) != "" {
		refund = current.UserTitlePrice
		if refund <= 0 {
			refund = 100
		}
	}

	newBalance := current.CoinBalance + refund - price
	if newBalance < 0 {
		a.renderTitleShop(w, r, "旧币不足", "")
		return
	}

	if err := releaseOldTitleToMarket(ctx, tx, strings.TrimSpace(current.UserTitle), current.UserTitlePrice, user.ID); err != nil {
		a.renderTitleShop(w, r, "购买失败，请稍后再试", "")
		return
	}

	res, err := tx.ExecContext(ctx, `
UPDATE title_catalog
SET owner_id = $1, updated_at = CURRENT_TIMESTAMP
WHERE id = $2 AND owner_id IS NULL
`, user.ID, titleRow.ID)
	if err != nil {
		a.renderTitleShop(w, r, "购买失败，请稍后再试", "")
		return
	}
	affected, _ := res.RowsAffected()
	if affected == 0 {
		a.renderTitleShop(w, r, "该称号已被购买", "")
		return
	}

	_, err = tx.ExecContext(ctx, `
UPDATE users
SET user_title = $1, user_title_price = $2, coin_balance = $3, updated_at = CURRENT_TIMESTAMP
WHERE id = $4
`, titleRow.Title, price, newBalance, user.ID)
	if err != nil {
		a.renderTitleShop(w, r, "购买失败，请稍后再试", "")
		return
	}

	if err = tx.Commit(); err != nil {
		a.renderTitleShop(w, r, "购买失败，请稍后再试", "")
		return
	}
	committed = true

	a.renderTitleShop(w, r, "", "购买成功，称号已生效")
}

func (a *API) handleShopLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if !a.ipLimiter.Allow("shop_login:" + clientIP(r)) {
		a.renderTitleShop(w, r, "登录过于频繁，请稍后再试", "")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 32<<10)
	if err := r.ParseForm(); err != nil {
		a.renderTitleShop(w, r, "登录失败，请稍后再试", "")
		return
	}
	identifier := strings.ToLower(strings.TrimSpace(r.FormValue("identifier")))
	password := strings.TrimSpace(r.FormValue("password"))
	if identifier == "" || password == "" {
		a.renderTitleShop(w, r, "请输入账号和密码", "")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	user, err := a.users.GetByEmailOrUsername(ctx, identifier)
	if err != nil {
		if err == data.ErrNotFound {
			_, _ = auth.VerifyPassword(password, dummyHash)
			a.renderTitleShop(w, r, "账号或密码错误", "")
			return
		}
		a.renderTitleShop(w, r, "登录失败，请稍后再试", "")
		return
	}

	ok, err := auth.VerifyPassword(password, user.PasswordHash)
	if err != nil || !ok {
		a.renderTitleShop(w, r, "账号或密码错误", "")
		return
	}

	if banned, err := a.devices.IsUserBanned(ctx, user.ID); err == nil && banned {
		a.renderTitleShop(w, r, "账号已被封禁", "")
		return
	}

	tokens, err := a.issueTokens(ctx, user)
	if err != nil {
		a.renderTitleShop(w, r, "登录失败，请稍后再试", "")
		return
	}

	setShopTokenCookie(w, r, tokens.AccessToken, a.cfg.AccessTokenTTL)
	next := strings.TrimSpace(r.FormValue("next"))
	if next != "/bot-apply" && next != "/channel-api/apply" {
		next = "/shop"
	}
	http.Redirect(w, r, next, http.StatusSeeOther)
}

func (a *API) handleShopLogout(w http.ResponseWriter, r *http.Request) {
	clearShopTokenCookie(w, r)
	http.Redirect(w, r, "/shop", http.StatusSeeOther)
}

func (a *API) renderTitleShop(w http.ResponseWriter, r *http.Request, errMsg, okMsg string) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	titles, _ := a.titles.ListAvailable(ctx)
	token := extractShopToken(r)

	// convert titles to any slice for template
	titleList := make([]any, len(titles))
	for i, t := range titles {
		titleList[i] = t
	}

	data := titleShopData{
		Error:   errMsg,
		Success: okMsg,
		Token:   token,
		Titles:  titleList,
	}

	if token != "" {
		user, authErr := a.shopUserFromToken(ctx, token)
		if authErr != "" {
			if data.Error == "" {
				data.Error = authErr
			}
		} else if user != nil {
			data.LoggedIn = true
			data.UID = user.UID
			data.UserTitle = user.UserTitle
			data.CoinBalance = user.CoinBalance
		}
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = titleShopTpl.Execute(w, data)
}
