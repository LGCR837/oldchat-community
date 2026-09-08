package httpapi

import (
	"sync"
	"time"
)

type channelWebSession struct {
	PermitID string
	UserID   string
	Expires  time.Time
}

type channelWebSessions struct {
	mu       sync.Mutex
	sessions map[string]channelWebSession
}

func newChannelWebSessions() *channelWebSessions {
	return &channelWebSessions{sessions: make(map[string]channelWebSession)}
}

func (s *channelWebSessions) New(permitID, userID string, ttl time.Duration) string {
	token := randomToken()
	s.mu.Lock()
	s.sessions[token] = channelWebSession{PermitID: permitID, UserID: userID, Expires: time.Now().Add(ttl)}
	s.mu.Unlock()
	return token
}

func (s *channelWebSessions) Get(token string) (channelWebSession, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	session, ok := s.sessions[token]
	if !ok || time.Now().After(session.Expires) {
		delete(s.sessions, token)
		return channelWebSession{}, false
	}
	return session, true
}

func (s *channelWebSessions) Delete(token string) {
	s.mu.Lock()
	delete(s.sessions, token)
	s.mu.Unlock()
}
