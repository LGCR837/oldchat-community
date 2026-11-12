package httpapi

import (
	"context"
	"log"

	"metrochat/internal/data"
)

// joinOfficialGroup 让账号自动进入官方群。失败只记日志，不影响注册与登录。
func (a *API) joinOfficialGroup(ctx context.Context, userID string) {
	if a.groups == nil || userID == "" {
		return
	}
	if err := a.groups.JoinOfficialGroup(ctx, userID); err != nil {
		log.Printf("加入官方群失败 user=%s: %v", userID, err)
	}
}

// bootstrapOfficialGroup 启动时确保官方群存在，并把历史账号一次性补进群。
func (a *API) bootstrapOfficialGroup(ctx context.Context) {
	if a.groups == nil {
		return
	}
	ok, err := a.groups.EnsureOfficialGroup(ctx, data.OfficialGroupID, data.OfficialGroupName, "")
	if err != nil {
		log.Printf("官方群初始化失败: %v", err)
		return
	}
	if !ok {
		return
	}
	n, err := a.groups.BackfillOfficialGroupMembers(ctx)
	if err != nil {
		log.Printf("官方群补全成员失败: %v", err)
		return
	}
	if n > 0 {
		log.Printf("官方群已补入 %d 个账号", n)
	}
}
