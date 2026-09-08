package data

import (
	"context"
	"sync"
	"testing"
	"time"
)

func TestOAuthConcurrentRefreshRevokesGrantOnReuse(t *testing.T) {
	store := newOAuthTestStore(t)
	ctx := context.Background()
	grantID, err := store.UpsertGrant(ctx, "g1", "c1", "u1", "openid offline_access")
	if err != nil {
		t.Fatal(err)
	}
	authTime := time.Now().Add(-time.Minute)
	if err = store.CreateTokenPair(ctx,
		OAuthTokenRecord{TokenHash: "a0", GrantID: grantID, ClientID: "c1", UserID: "u1", Scope: "openid offline_access", ExpiresAt: time.Now().Add(time.Minute)},
		OAuthTokenRecord{TokenHash: "r0", GrantID: grantID, ClientID: "c1", UserID: "u1", Scope: "openid offline_access", FamilyID: "f1", AuthTime: authTime, ExpiresAt: time.Now().Add(time.Hour)}); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	errs := make(chan error, 2)
	for i, suffix := range []string{"1", "2"} {
		wg.Add(1)
		go func(i int, suffix string) {
			defer wg.Done()
			errs <- store.RotateRefresh(ctx, "r0",
				OAuthTokenRecord{TokenHash: "a" + suffix, GrantID: grantID, ClientID: "c1", UserID: "u1", Scope: "openid offline_access", ExpiresAt: time.Now().Add(time.Minute)},
				OAuthTokenRecord{TokenHash: "r" + suffix, GrantID: grantID, ClientID: "c1", UserID: "u1", Scope: "openid offline_access", FamilyID: "f1", AuthTime: authTime, ExpiresAt: time.Now().Add(time.Hour)})
		}(i, suffix)
	}
	wg.Wait()
	close(errs)
	success, reused := 0, 0
	for err := range errs {
		if err == nil {
			success++
		} else if err == ErrOAuthRefreshReused {
			reused++
		} else {
			t.Fatalf("意外错误: %v", err)
		}
	}
	if success != 1 || reused != 1 {
		t.Fatalf("轮换结果异常 success=%d reused=%d", success, reused)
	}
	if _, err = store.GetAccessToken(ctx, "a1"); err == nil {
		t.Fatal("检测到 refresh 重放后 grant 下 access token 必须失效")
	}
	if _, err = store.GetAccessToken(ctx, "a2"); err == nil {
		t.Fatal("检测到 refresh 重放后 grant 下 access token 必须失效")
	}
}
