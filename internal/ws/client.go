package ws

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	maxMessageSize = 64 * 1024
)

type Client struct {
	Hub       *Hub
	Conn      *websocket.Conn
	Send      chan []byte
	UserID    string
	EncKey    []byte
	MacKey    []byte
	OnMessage func(client *Client, payload []byte)
	closeOnce sync.Once
	done      chan struct{}
}

func NewClient(hub *Hub, conn *websocket.Conn, userID string, encKey, macKey []byte) *Client {
	return &Client{
		Hub:    hub,
		Conn:   conn,
		Send:   make(chan []byte, 64), // 增大缓冲区从8到64
		UserID: userID,
		EncKey: append([]byte(nil), encKey...),
		MacKey: append([]byte(nil), macKey...),
		done:   make(chan struct{}),
	}
}

func (c *Client) Start() {
	c.Hub.Add(c.UserID, c)
	go c.writePump()
	go c.readPump()
}

func (c *Client) readPump() {
	defer func() {
		c.Hub.Remove(c.UserID, c)
		c.Conn.Close()
	}()

	c.Conn.SetReadLimit(maxMessageSize)
	c.Conn.SetReadDeadline(time.Now().Add(pongWait))
	c.Conn.SetPongHandler(func(string) error {
		c.Conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	for {
		messageType, payload, err := c.Conn.ReadMessage()
		if err != nil {
			return
		}
		if messageType == websocket.TextMessage && c.OnMessage != nil {
			c.OnMessage(c, payload)
		}
	}
}

func (c *Client) writePump() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.Conn.Close()
	}()

	for {
		select {
		case msg, ok := <-c.Send:
			c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			w, err := c.Conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			_, _ = w.Write(msg)
			_ = w.Close()
		case <-ticker.C:
			c.Conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		case <-c.done:
			return
		}
	}
}
