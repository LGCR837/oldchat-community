package httpapi

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"metrochat/internal/data"
)

type presenceData struct {
	UID            string `json:"uid"`
	IsOnline       bool   `json:"is_online"`
	PresenceStatus string `json:"presence_status"`
}

type wsEnvelopePresence struct {
	Type string       `json:"type"`
	Data presenceData `json:"data"`
}

func (a *API) handlePresenceChange(userID string, isOnline bool) {
	// 异步处理，不阻塞 Hub 的 Add/Remove
	go func() {
		defer func() {
			if r := recover(); r != nil {
				log.Printf("presence: panic in handlePresenceChange: %v", r)
			}
		}()
		// Add/Remove 回调异步执行，快速重连时旧事件可能晚到；
		// 发送前以 Hub 当前真实状态为准，丢弃已经过期的在线/离线事件。
		if a.wsHub.IsUserOnline(userID) != isOnline {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		user, err := a.users.GetByID(ctx, userID)
		if err != nil {
			log.Printf("presence: failed to get user %s: %v", userID, err)
			return
		}

		friendIDs, err := a.friends.ListFriendIDs(ctx, userID)
		if err != nil {
			log.Printf("presence: failed to list friends for %s: %v", userID, err)
			return
		}

		// 先广播自己的在线状态给所有好友
		envelope := wsEnvelopePresence{
			Type: "presence",
			Data: presenceData{
				UID:            user.UID,
				IsOnline:       isOnline,
				PresenceStatus: user.PresenceStatus,
			},
		}

		payload, err := json.Marshal(envelope)
		if err != nil {
			log.Printf("presence: marshal error: %v", err)
			return
		}

		a.wsHub.BroadcastToUsers(friendIDs, payload)

		// 上线时，将当前已在线的好友状态推送给该用户（初始状态同步）
		if isOnline {
			a.broadcastOnlineFriendsToUser(ctx, user, friendIDs)
		}
	}()
}

// broadcastOnlineFriendsToUser 发送所有当前在线好友的在线状态给指定用户
func (a *API) broadcastOnlineFriendsToUser(ctx context.Context, user *data.User, friendIDs []string) {
	onlineUsers := a.wsHub.GetOnlineUsers()
	// 建立 friendID 集合用于快速查找
	friendSet := make(map[string]struct{}, len(friendIDs))
	for _, fid := range friendIDs {
		friendSet[fid] = struct{}{}
	}

	visibleOnlineIDs := make([]string, 0, len(onlineUsers))
	for _, onlineID := range onlineUsers {
		if onlineID == user.ID {
			continue
		}
		if _, isFriend := friendSet[onlineID]; isFriend {
			visibleOnlineIDs = append(visibleOnlineIDs, onlineID)
		}
	}

	friendUsers, err := a.users.ListPresenceUsersByIDs(ctx, visibleOnlineIDs)
	if err != nil {
		log.Printf("presence: batch load online friends for %s: %v", user.ID, err)
		return
	}
	for i := range friendUsers {
		friendUser := &friendUsers[i]
		if friendUser.PresenceStatus == "invisible" {
			continue
		}
		envelope := wsEnvelopePresence{
			Type: "presence",
			Data: presenceData{
				UID:            friendUser.UID,
				IsOnline:       true,
				PresenceStatus: friendUser.PresenceStatus,
			},
		}
		payload, err := json.Marshal(envelope)
		if err == nil {
			a.wsHub.BroadcastToUser(user.ID, payload)
		}
	}
}
