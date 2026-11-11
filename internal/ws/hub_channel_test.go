package ws

import "testing"

func newTopicTestClient(hub *Hub, user string) *Client {
	return &Client{Hub: hub, UserID: user, Send: make(chan []byte, 4), done: make(chan struct{})}
}

func TestChannelTopicBroadcastOnlyReachesOnlineSubscribers(t *testing.T) {
	hub := NewHub()
	a := newTopicTestClient(hub, "a")
	b := newTopicTestClient(hub, "b")
	c := newTopicTestClient(hub, "c")
	hub.SetChannelSubscriptions(a, []string{"CH-1"})
	hub.SetChannelSubscriptions(b, []string{"CH-1", "CH-2"})
	hub.SetChannelSubscriptions(c, []string{"CH-2"})
	hub.BroadcastToChannel("CH-1", []byte("update"))
	if len(a.Send) != 1 || len(b.Send) != 1 || len(c.Send) != 0 {
		t.Fatalf("unexpected queues a=%d b=%d c=%d", len(a.Send), len(b.Send), len(c.Send))
	}
}

func TestChannelTopicRefreshRemovesStaleAndEmptyTopics(t *testing.T) {
	hub := NewHub()
	client := newTopicTestClient(hub, "a")
	hub.SetChannelSubscriptions(client, []string{"CH-OLD"})
	hub.SetChannelSubscriptions(client, []string{"CH-NEW"})
	if _, ok := hub.channelTopics["CH-OLD"]; ok {
		t.Fatal("empty old topic was not removed")
	}
	hub.BroadcastToChannel("CH-OLD", []byte("old"))
	hub.BroadcastToChannel("CH-NEW", []byte("new"))
	if len(client.Send) != 1 || string(<-client.Send) != "new" {
		t.Fatal("subscription replacement failed")
	}
}
