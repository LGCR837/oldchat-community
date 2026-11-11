package httpapi

import (
	"context"
	"database/sql"
	"strings"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/data"
)

const customTitlePrice = 200

func (a *API) purchaseCustomTitle(ctx context.Context, user *data.User, title string) string {
	if user == nil {
		return "账号无效"
	}
	tx, err := a.db.BeginTxx(ctx, nil)
	if err != nil {
		return "系统繁忙，请稍后再试"
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()

	var exists string
	err = tx.GetContext(ctx, &exists, `SELECT id FROM title_catalog WHERE title = $1 LIMIT 1`, title)
	if err == nil {
		return "该称号已存在"
	}
	if err != sql.ErrNoRows {
		return "系统繁忙，请稍后再试"
	}

	err = tx.GetContext(ctx, &exists, `SELECT id FROM users WHERE user_title = $1 LIMIT 1`, title)
	if err == nil {
		return "该称号已被使用"
	}
	if err != sql.ErrNoRows {
		return "系统繁忙，请稍后再试"
	}

	var current struct {
		UserTitle      string `db:"user_title"`
		UserTitlePrice int    `db:"user_title_price"`
		CoinBalance    int    `db:"coin_balance"`
	}
	if err = tx.GetContext(ctx, &current, `SELECT user_title, user_title_price, coin_balance FROM users WHERE id = $1`, user.ID); err != nil {
		return "账号信息读取失败"
	}
	if strings.TrimSpace(current.UserTitle) == title {
		return "该称号已拥有，无需重复购买"
	}

	refund := 0
	if strings.TrimSpace(current.UserTitle) != "" {
		refund = current.UserTitlePrice
		if refund <= 0 {
			refund = 100
		}
	}
	newBalance := current.CoinBalance + refund - customTitlePrice
	if newBalance < 0 {
		return "旧币不足"
	}

	if err := releaseOldTitleToMarket(ctx, tx, strings.TrimSpace(current.UserTitle), current.UserTitlePrice, user.ID); err != nil {
		return "购买失败，请稍后再试"
	}

	_, err = tx.ExecContext(ctx, `
INSERT INTO title_catalog (id, title, price, active, is_custom, owner_id, created_at, updated_at)
VALUES ($1, $2, $3, 1, 1, $4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
`, data.NewID(), title, customTitlePrice, user.ID)
	if err != nil {
		if strings.Contains(err.Error(), "title_catalog.title") {
			return "该称号已存在"
		}
		return "购买失败，请稍后再试"
	}

	_, err = tx.ExecContext(ctx, `
UPDATE users
SET user_title = $1, user_title_price = $2, coin_balance = $3, updated_at = CURRENT_TIMESTAMP
WHERE id = $4
`, title, customTitlePrice, newBalance, user.ID)
	if err != nil {
		return "购买失败，请稍后再试"
	}

	if err = tx.Commit(); err != nil {
		return "购买失败，请稍后再试"
	}
	committed = true
	return ""
}

func releaseOldTitleToMarket(ctx context.Context, tx *sqlx.Tx, oldTitle string, oldPrice int, userID string) error {
	if tx == nil || strings.TrimSpace(oldTitle) == "" {
		return nil
	}
	var old struct {
		ID       string `db:"id"`
		IsCustom int    `db:"is_custom"`
	}
	err := tx.GetContext(ctx, &old, `SELECT id, is_custom FROM title_catalog WHERE title = $1`, oldTitle)
	if err == nil && old.ID != "" {
		if old.IsCustom != 0 {
			_, _ = tx.ExecContext(ctx, `
UPDATE title_catalog
SET owner_id = NULL, updated_at = CURRENT_TIMESTAMP
WHERE id = $1 AND (owner_id = $2 OR owner_id IS NULL)
`, old.ID, userID)
			return nil
		}
		_, _ = tx.ExecContext(ctx, `
UPDATE title_catalog
SET owner_id = NULL, active = 1, updated_at = CURRENT_TIMESTAMP
WHERE id = $1 AND (owner_id = $2 OR owner_id IS NULL)
`, old.ID, userID)
			return nil
	}
	if err != sql.ErrNoRows {
		return err
	}
	if oldPrice <= 0 {
		oldPrice = 100
	}
	_, err = tx.ExecContext(ctx, `
INSERT INTO title_catalog (id, title, price, active, is_custom, owner_id, created_at, updated_at)
VALUES ($1, $2, $3, 1, 0, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
`, data.NewID(), oldTitle, oldPrice)
	return err
}
