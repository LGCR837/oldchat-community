package ws

import (
	"sync"

	"metrochat/internal/secure"
)

type Hub struct {
	mu               sync.RWMutex
	clients          map[string]map[*Client]struct{}
	channelTopics    map[string]map[*Client]struct{}
	clientChannels   map[*Client]map[string]struct{}
	OnPresenceChange func(userID string, isOnline bool)
}

func NewHub() *Hub {
	return &Hub{
		clients:        make(map[string]map[*Client]struct{}),
		channelTopics:  make(map[string]map[*Client]struct{}),
		clientChannels: make(map[*Client]map[string]struct{}),
	}
}

func (h *Hub) Add(userID string, c *Client) {
	h.mu.Lock()
	set, ok := h.clients[userID]
	firstConnection := !ok || len(set) == 0
	if !ok {
		set = make(map[*Client]struct{})
		h.clients[userID] = set
	}
	set[c] = struct{}{}
	h.mu.Unlock()

	// 同一账号多设备或重连重叠时，只在 0→1 条连接时广播上线。
	// 这与 Remove 的最后连接离线语义保持对称，避免重复 presence 包和数据库查询。
	if firstConnection && h.OnPresenceChange != nil {
		h.OnPresenceChange(userID, true)
	}
}

func (h *Hub) Remove(userID string, c *Client) {
	h.mu.Lock()
	set, ok := h.clients[userID]
	if !ok {
		h.mu.Unlock()
		return
	}
	if _, exists := set[c]; !exists {
		h.mu.Unlock()
		return
	}
	delete(set, c)
	if topics := h.clientChannels[c]; topics != nil {
		for channelID := range topics {
			if members := h.channelTopics[channelID]; members != nil {
				delete(members, c)
				if len(members) == 0 {
					delete(h.channelTopics, channelID)
				}
			}
		}
		delete(h.clientChannels, c)
	}

	c.closeOnce.Do(func() {
		close(c.done)
	})

	var lastUser bool
	if len(set) == 0 {
		delete(h.clients, userID)
		lastUser = true
	}
	h.mu.Unlock()

	if lastUser && h.OnPresenceChange != nil {
		h.OnPresenceChange(userID, false)
	}
}

func (h *Hub) BroadcastToUser(userID string, msg []byte) {
	h.BroadcastToUsers([]string{userID}, msg)
}

// BroadcastToUsers 只获取一次 Hub 锁，并对重复用户/多次出现的连接去重。
// 群消息、群输入状态和 presence 广播可避免逐成员反复加锁及分配切片。
func (h *Hub) BroadcastToUsers(userIDs []string, msg []byte) {
	if len(userIDs) == 0 || len(msg) == 0 {
		return
	}
	h.mu.RLock()
	unique := make(map[*Client]struct{})
	for _, userID := range userIDs {
		if set, ok := h.clients[userID]; ok {
			for c := range set {
				unique[c] = struct{}{}
			}
		}
	}
	clients := make([]*Client, 0, len(unique))
	for c := range unique {
		clients = append(clients, c)
	}
	h.mu.RUnlock()
	sendToClients(clients, msg)
}

// SetChannelSubscriptions 原子替换单条连接的频道 topic；只保存在线连接，不枚举离线订阅者。
func (h *Hub) SetChannelSubscriptions(c *Client, channelIDs []string) {
	h.mu.Lock()
	if old := h.clientChannels[c]; old != nil {
		for channelID := range old {
			if members := h.channelTopics[channelID]; members != nil {
				delete(members, c)
				if len(members) == 0 {
					delete(h.channelTopics, channelID)
				}
			}
		}
	}
	fresh := make(map[string]struct{}, len(channelIDs))
	for _, channelID := range channelIDs {
		if channelID == "" {
			continue
		}
		members := h.channelTopics[channelID]
		if members == nil {
			members = make(map[*Client]struct{})
			h.channelTopics[channelID] = members
		}
		members[c] = struct{}{}
		fresh[channelID] = struct{}{}
	}
	h.clientChannels[c] = fresh
	h.mu.Unlock()
}

func (h *Hub) BroadcastToChannel(channelID string, msg []byte) {
	h.mu.RLock()
	members := h.channelTopics[channelID]
	clients := make([]*Client, 0, len(members))
	for c := range members {
		clients = append(clients, c)
	}
	h.mu.RUnlock()
	sendToClients(clients, msg)
}

func (h *Hub) Broadcast(msg []byte) {
	h.mu.RLock()
	// 在锁内复制客户端列表，防止并发修改
	allClients := make([]*Client, 0, len(h.clients))
	for _, set := range h.clients {
		for c := range set {
			allClients = append(allClients, c)
		}
	}
	h.mu.RUnlock()

	sendToClients(allClients, msg)
}

func sendToClients(clients []*Client, msg []byte) {
	for _, c := range clients {
		payload := msg
		if len(c.EncKey) > 0 && len(c.MacKey) > 0 {
			enc, err := secure.EncryptWithKeys(msg, c.EncKey, c.MacKey)
			if err != nil {
				continue
			}
			payload = enc
		}
		select {
		case c.Send <- payload:
		default:
			// 慢客户端由 HTTP 水位补偿，不阻塞广播 goroutine。
		}
	}
}

func (h *Hub) IsUserOnline(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.clients[userID]
	return ok
}

// AreUsersOnline 批量读取在线状态，只获取一次 Hub 锁。
func (h *Hub) AreUsersOnline(userIDs []string) map[string]bool {
	result := make(map[string]bool, len(userIDs))
	h.mu.RLock()
	for _, userID := range userIDs {
		_, result[userID] = h.clients[userID]
	}
	h.mu.RUnlock()
	return result
}

func (h *Hub) GetOnlineUsers() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()
	users := make([]string, 0, len(h.clients))
	for userID := range h.clients {
		users = append(users, userID)
	}
	return users
}

func (h *Hub) OnlineCounts() (onlineUsers int, onlineConnections int) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	onlineUsers = len(h.clients)
	for _, set := range h.clients {
		onlineConnections += len(set)
	}
	return
}
