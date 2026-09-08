package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/golang-jwt/jwt/v5"
	"github.com/jmoiron/sqlx"

	"metrochat/internal/auth"
	"metrochat/internal/data"
)

func newPtsTestAPI(t *testing.T) *API {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	pts := data.NewPtsStore(db)
	if err := pts.EnsureSchema(); err != nil {
		t.Fatal(err)
	}
	api := &API{
		pts:           pts,
		events:        newEventsService(pts, nilHub{}),
		adminSessions: newAdminSessions(),
	}
	t.Cleanup(func() { _ = db.Close() })
	return api
}

// nilHub 测试用：不推送。
type nilHub struct{}

func (nilHub) BroadcastToUser(userID string, msg []byte)     {}
func (nilHub) BroadcastToUsers(userIDs []string, msg []byte) {}

func addTestUserClaims(req *http.Request, userID string) {
	ctx := context.WithValue(req.Context(), claimsKey,
		&auth.AccessClaims{RegisteredClaims: jwt.RegisteredClaims{Subject: userID}})
	*req = *req.WithContext(ctx)
}

func TestUpdatesDifferenceEmpty(t *testing.T) {
	api := newPtsTestAPI(t)
	req := httptest.NewRequest(http.MethodGet, "/updates/difference?pts=0", nil)
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleUpdatesDifference(rec, req)
	if rec.Code != 200 {
		t.Fatalf("code = %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `"events":[]`) || strings.Contains(body, `"reset":true`) {
		t.Fatalf("empty diff = %s", body)
	}
}

func TestUpdatesDifferenceIncremental(t *testing.T) {
	api := newPtsTestAPI(t)
	ctx := context.Background()
	if _, err := api.pts.Emit(ctx, "user-1", "DIRECT_MESSAGE_NEW", "m1", map[string]interface{}{"id": "m1"}); err != nil {
		t.Fatal(err)
	}
	if _, err := api.pts.Emit(ctx, "user-1", "DIRECT_MESSAGE_RECALL", "m2", map[string]interface{}{"message_id": "m2"}); err != nil {
		t.Fatal(err)
	}

	req := httptest.NewRequest(http.MethodGet, "/updates/difference?pts=0&limit=10", nil)
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleUpdatesDifference(rec, req)
	if rec.Code != 200 {
		t.Fatalf("code = %d", rec.Code)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `"type":"DIRECT_MESSAGE_NEW"`) || !strings.Contains(body, `"type":"DIRECT_MESSAGE_RECALL"`) {
		t.Fatalf("diff events missing: %s", body)
	}
	if !strings.Contains(body, `"next_pts":2`) {
		t.Fatalf("next_pts wrong: %s", body)
	}
	if strings.Contains(body, `"reset":true`) {
		t.Fatalf("should not reset: %s", body)
	}
}

func TestUpdatesDifferenceResetOnHugeGap(t *testing.T) {
	api := newPtsTestAPI(t)
	ctx := context.Background()
	// 模拟长期离线：直接推进 pts 到远超阈值。
	if _, err := api.pts.EmitMulti(ctx, "user-1", "DIRECT_MESSAGE_NEW", "m1", map[string]interface{}{}, updatesDifferenceMaxGap+5); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/updates/difference?pts=0", nil)
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleUpdatesDifference(rec, req)
	body := rec.Body.String()
	if !strings.Contains(body, `"reset":true`) {
		t.Fatalf("huge gap should reset: %s", body)
	}
}

func TestUpdatesDifferenceResetOnClientAhead(t *testing.T) {
	api := newPtsTestAPI(t)
	ctx := context.Background()
	if _, err := api.pts.Emit(ctx, "user-1", "DIRECT_MESSAGE_NEW", "m1", map[string]interface{}{}); err != nil {
		t.Fatal(err)
	}
	// 客户端 pts=100 > 服务端 current=1 → reset
	req := httptest.NewRequest(http.MethodGet, "/updates/difference?pts=100", nil)
	addTestUserClaims(req, "user-1")
	rec := httptest.NewRecorder()
	api.handleUpdatesDifference(rec, req)
	body := rec.Body.String()
	if !strings.Contains(body, `"reset":true`) {
		t.Fatalf("client ahead should reset: %s", body)
	}
}
