package data

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math/rand"
	"strconv"
	"strings"
	"time"

	"github.com/jmoiron/sqlx"
)

// 每日刮刮乐：5 个槽位各自独立随机。
// 奖品权重（百分比）：谢谢惠顾 40 / 1币 30 / 5币 15 / 10币 10 / 20币 5。
const (
	ScratchSlotCount        = 5
	scratchPrizes           = "0,1,5,10,20"
	scratchWeights          = "40,30,15,10,5"
	scratchMaxTotalReward   = 1000
	scratchMaxBalanceReward = 100000
)

// DailyScratchResult 一次刮刮乐的结果（含今日是否已刮）。
type DailyScratchResult struct {
	ScratchDate string
	AlreadyDone bool
	Slots       []int
	TotalReward int
	CoinBalance int
}

type ScratchStore struct {
	db *sqlx.DB
}

func NewScratchStore(db *sqlx.DB) *ScratchStore {
	return &ScratchStore{db: db}
}

// QueryToday 查询今日刮刮乐状态（不产生新记录）。
func (s *ScratchStore) QueryToday(ctx context.Context, userID string) (*DailyScratchResult, error) {
	if userID == "" {
		return nil, ErrNotFound
	}
	today := time.Now().Local().Format("2006-01-02")

	var id, scratchDate, slots string
	var totalReward int
	err := s.db.QueryRowContext(ctx, `
SELECT id, scratch_date, slots, total_reward
FROM user_daily_scratches
WHERE user_id = $1 AND scratch_date = $2
`, userID, today).Scan(&id, &scratchDate, &slots, &totalReward)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			balance, berr := s.coinBalance(ctx, userID)
			if berr != nil {
				return nil, berr
			}
			return &DailyScratchResult{
				ScratchDate: today,
				AlreadyDone: false,
				Slots:       nil,
				TotalReward: 0,
				CoinBalance: balance,
			}, nil
		}
		return nil, err
	}

	parsed, perr := parseScratchSlots(slots)
	if perr != nil {
		parsed = make([]int, ScratchSlotCount)
	}
	balance, berr := s.coinBalance(ctx, userID)
	if berr != nil {
		return nil, berr
	}
	return &DailyScratchResult{
		ScratchDate: today,
		AlreadyDone: true,
		Slots:       parsed,
		TotalReward: totalReward,
		CoinBalance: balance,
	}, nil
}

// DailyScratch 执行今日刮刮乐：首次生成 5 槽位并发放金币，已刮过则幂等返回。
func (s *ScratchStore) DailyScratch(ctx context.Context, userID string) (*DailyScratchResult, error) {
	if userID == "" {
		return nil, ErrNotFound
	}
	today := time.Now().Local().Format("2006-01-02")

	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer func() {
		if tx != nil {
			_ = tx.Rollback()
		}
	}()

	// 幂等插入：今天已刮过则 ON CONFLICT DO NOTHING，rows=0。
	scratchID := NewID()
	res, err := tx.ExecContext(ctx, `
INSERT INTO user_daily_scratches (
    id, user_id, scratch_date, slots, total_reward, created_at
) VALUES (
    $1, $2, $3, $4, $5, CURRENT_TIMESTAMP
)
ON CONFLICT(user_id, scratch_date)
DO NOTHING
`, scratchID, userID, today, "", 0)
	if err != nil {
		return nil, err
	}
	rows, err := res.RowsAffected()
	if err != nil {
		return nil, err
	}

	var slots []int
	var totalReward int
	alreadyDone := rows == 0
	if !alreadyDone {
		slots = rollScratchSlots()
		totalReward = sumSlots(slots)
		if totalReward > scratchMaxTotalReward {
			totalReward = scratchMaxTotalReward
		}
		_, err = tx.ExecContext(ctx, `
UPDATE user_daily_scratches
SET slots = $1, total_reward = $2
WHERE id = $3
`, joinSlots(slots), totalReward, scratchID)
		if err != nil {
			return nil, err
		}
		if totalReward > 0 {
			_, err = tx.ExecContext(ctx, `
UPDATE users
SET coin_balance = coin_balance + $1, updated_at = CURRENT_TIMESTAMP
WHERE id = $2
`, totalReward, userID)
			if err != nil {
				return nil, err
			}
		}
	} else {
		var storedSlots string
		err = tx.QueryRowContext(ctx, `
SELECT slots, total_reward
FROM user_daily_scratches
WHERE user_id = $1 AND scratch_date = $2
`, userID, today).Scan(&storedSlots, &totalReward)
		if err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return nil, ErrNotFound
			}
			return nil, err
		}
		slots, err = parseScratchSlots(storedSlots)
		if err != nil {
			slots = make([]int, ScratchSlotCount)
		}
	}

	var balance int
	err = tx.QueryRowContext(ctx, `
SELECT coin_balance
FROM users
WHERE id = $1
`, userID).Scan(&balance)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if balance > scratchMaxBalanceReward {
		balance = scratchMaxBalanceReward
	}

	if err = tx.Commit(); err != nil {
		return nil, err
	}
	tx = nil

	return &DailyScratchResult{
		ScratchDate: today,
		AlreadyDone: alreadyDone,
		Slots:       slots,
		TotalReward: totalReward,
		CoinBalance: balance,
	}, nil
}

func (s *ScratchStore) coinBalance(ctx context.Context, userID string) (int, error) {
	var balance int
	err := s.db.QueryRowContext(ctx, `
SELECT coin_balance
FROM users
WHERE id = $1
`, userID).Scan(&balance)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, ErrNotFound
		}
		return 0, err
	}
	if balance > scratchMaxBalanceReward {
		balance = scratchMaxBalanceReward
	}
	return balance, nil
}

// rollScratchSlots 按权重掷出 5 个槽位（0 = 谢谢惠顾）。
func rollScratchSlots() []int {
	prizes := strings.Split(scratchPrizes, ",")
	weights := strings.Split(scratchWeights, ",")
	total := 0
	for _, w := range weights {
		if n, err := strconv.Atoi(w); err == nil {
			total += n
		}
	}
	if total <= 0 {
		total = 100
	}
	slots := make([]int, ScratchSlotCount)
	for i := 0; i < ScratchSlotCount; i++ {
		roll := rand.Intn(total)
		cum := 0
		for j := range prizes {
			w, err := strconv.Atoi(weights[j])
			if err != nil || w <= 0 {
				continue
			}
			cum += w
			if roll < cum {
				slots[i], _ = strconv.Atoi(prizes[j])
				break
			}
		}
	}
	return slots
}

func parseScratchSlots(s string) ([]int, error) {
	if s == "" {
		return make([]int, ScratchSlotCount), nil
	}
	parts := strings.Split(s, ",")
	if len(parts) > ScratchSlotCount {
		return nil, fmt.Errorf("too many slots")
	}
	out := make([]int, len(parts))
	for i, p := range parts {
		v, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || v < 0 {
			return nil, fmt.Errorf("invalid slot value")
		}
		out[i] = v
	}
	return out, nil
}

func joinSlots(slots []int) string {
	parts := make([]string, len(slots))
	for i, v := range slots {
		parts[i] = strconv.Itoa(v)
	}
	return strings.Join(parts, ",")
}

func sumSlots(slots []int) int {
	total := 0
	for _, v := range slots {
		total += v
	}
	return total
}
