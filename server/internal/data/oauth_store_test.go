package data

import (
	"context"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"
)

func newOAuthTestStore(t *testing.T) *OAuthStore {
	t.Helper()
	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	_, err = db.Exec(`
PRAGMA foreign_keys=ON;
CREATE TABLE users(id TEXT PRIMARY KEY);
INSERT INTO users(id) VALUES('u1');
CREATE TABLE oauth_clients(client_id TEXT PRIMARY KEY,name TEXT,secret_hash TEXT,redirect_uri TEXT,allowed_scopes TEXT,public_client INTEGER,active INTEGER,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE oauth_authorization_requests(id TEXT PRIMARY KEY,client_id TEXT,user_id TEXT,redirect_uri TEXT,scope TEXT,state TEXT,nonce TEXT,code_challenge TEXT,csrf_hash TEXT,auth_time DATETIME,expires_at DATETIME,created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE oauth_authorization_codes(code_hash TEXT PRIMARY KEY,client_id TEXT,user_id TEXT,redirect_uri TEXT,scope TEXT,nonce TEXT,code_challenge TEXT,auth_time DATETIME,expires_at DATETIME,consumed_at DATETIME);
CREATE TABLE oauth_grants(id TEXT PRIMARY KEY,client_id TEXT,user_id TEXT,scope TEXT,revoked_at DATETIME,created_at DATETIME DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME DEFAULT CURRENT_TIMESTAMP);
CREATE UNIQUE INDEX idx_oauth_grants_active ON oauth_grants(client_id,user_id) WHERE revoked_at IS NULL;
CREATE TABLE oauth_access_tokens(token_hash TEXT PRIMARY KEY,grant_id TEXT,client_id TEXT,user_id TEXT,scope TEXT,expires_at DATETIME,revoked_at DATETIME,created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE oauth_refresh_tokens(token_hash TEXT PRIMARY KEY,grant_id TEXT,client_id TEXT,user_id TEXT,scope TEXT,family_id TEXT,auth_time DATETIME,expires_at DATETIME,revoked_at DATETIME,replaced_by TEXT,created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
INSERT INTO oauth_clients(client_id,name,secret_hash,redirect_uri,allowed_scopes,public_client,active) VALUES('c1','测试','','https://client/cb','openid profile',1,1);`)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return NewOAuthStore(db)
}

func TestOAuthCodeSingleUseAndBinding(t *testing.T) {
	store := newOAuthTestStore(t)
	ctx := context.Background()
	req := OAuthAuthorizationRequest{ID: "r1", ClientID: "c1", RedirectURI: "https://client/cb", Scope: "openid profile", State: "s", Nonce: "n", CodeChallenge: "challenge", CSRFHash: "csrf", ExpiresAt: time.Now().Add(time.Minute)}
	if err := store.CreateAuthorizationRequest(ctx, req); err != nil {
		t.Fatal(err)
	}
	if err := store.SetAuthorizationUser(ctx, "r1", "u1"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CreateCodeAndDeleteRequest(ctx, "r1", "codehash", time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ConsumeCode(ctx, "codehash", "c1", "https://wrong/cb", "challenge"); err == nil {
		t.Fatal("错误 redirect_uri 不应消费授权码")
	}
	if _, err := store.ConsumeCode(ctx, "codehash", "c1", "https://client/cb", "challenge"); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ConsumeCode(ctx, "codehash", "c1", "https://client/cb", "challenge"); err == nil {
		t.Fatal("授权码只能消费一次")
	}
}
