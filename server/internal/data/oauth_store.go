package data

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/jmoiron/sqlx"
)

var ErrOAuthRefreshReused = errors.New("oauth refresh token reused")

type OAuthClient struct {
	ClientID      string `db:"client_id"`
	Name          string `db:"name"`
	SecretHash    string `db:"secret_hash"`
	RedirectURI   string `db:"redirect_uri"`
	AllowedScopes string `db:"allowed_scopes"`
	PublicClient  bool   `db:"public_client"`
	Active        bool   `db:"active"`
}

type OAuthAuthorizationRequest struct {
	ID            string       `db:"id"`
	ClientID      string       `db:"client_id"`
	RedirectURI   string       `db:"redirect_uri"`
	Scope         string       `db:"scope"`
	State         string       `db:"state"`
	Nonce         string       `db:"nonce"`
	CodeChallenge string       `db:"code_challenge"`
	CSRFHash      string       `db:"csrf_hash"`
	UserID        string       `db:"user_id"`
	AuthTime      sql.NullTime `db:"auth_time"`
	ExpiresAt     time.Time    `db:"expires_at"`
}

type OAuthAuthorizationCode struct {
	CodeHash      string       `db:"code_hash"`
	ClientID      string       `db:"client_id"`
	UserID        string       `db:"user_id"`
	RedirectURI   string       `db:"redirect_uri"`
	Scope         string       `db:"scope"`
	Nonce         string       `db:"nonce"`
	CodeChallenge string       `db:"code_challenge"`
	AuthTime      time.Time    `db:"auth_time"`
	ExpiresAt     time.Time    `db:"expires_at"`
	ConsumedAt    sql.NullTime `db:"consumed_at"`
}

type OAuthTokenRecord struct {
	TokenHash  string         `db:"token_hash"`
	GrantID    string         `db:"grant_id"`
	ClientID   string         `db:"client_id"`
	UserID     string         `db:"user_id"`
	Scope      string         `db:"scope"`
	FamilyID   string         `db:"family_id"`
	AuthTime   time.Time      `db:"auth_time"`
	ExpiresAt  time.Time      `db:"expires_at"`
	RevokedAt  sql.NullTime   `db:"revoked_at"`
	ReplacedBy sql.NullString `db:"replaced_by"`
}

type OAuthStore struct{ db *sqlx.DB }

func NewOAuthStore(db *sqlx.DB) *OAuthStore { return &OAuthStore{db: db} }

func (s *OAuthStore) GetClient(ctx context.Context, clientID string) (*OAuthClient, error) {
	var out OAuthClient
	err := s.db.GetContext(ctx, &out, `SELECT client_id,name,secret_hash,redirect_uri,allowed_scopes,public_client,active FROM oauth_clients WHERE client_id=?`, clientID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &out, err
}

func (s *OAuthStore) ListClients(ctx context.Context) ([]OAuthClient, error) {
	var out []OAuthClient
	err := s.db.SelectContext(ctx, &out, `SELECT client_id,name,secret_hash,redirect_uri,allowed_scopes,public_client,active FROM oauth_clients ORDER BY created_at DESC`)
	return out, err
}

func (s *OAuthStore) SetClientActive(ctx context.Context, clientID string, active bool) error {
	_, err := s.db.ExecContext(ctx, `UPDATE oauth_clients SET active=?,updated_at=CURRENT_TIMESTAMP WHERE client_id=?`, active, clientID)
	return err
}

func (s *OAuthStore) UpsertClient(ctx context.Context, client OAuthClient) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO oauth_clients(client_id,name,secret_hash,redirect_uri,allowed_scopes,public_client,active,updated_at)
VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
ON CONFLICT(client_id) DO UPDATE SET name=excluded.name,secret_hash=excluded.secret_hash,redirect_uri=excluded.redirect_uri,allowed_scopes=excluded.allowed_scopes,public_client=excluded.public_client,active=excluded.active,updated_at=CURRENT_TIMESTAMP`,
		client.ClientID, client.Name, client.SecretHash, client.RedirectURI, client.AllowedScopes, client.PublicClient, client.Active)
	return err
}

func (s *OAuthStore) CreateAuthorizationRequest(ctx context.Context, req OAuthAuthorizationRequest) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO oauth_authorization_requests(id,client_id,redirect_uri,scope,state,nonce,code_challenge,csrf_hash,user_id,expires_at)
VALUES(?,?,?,?,?,?,?,?,NULL,?)`, req.ID, req.ClientID, req.RedirectURI, req.Scope, req.State, req.Nonce, req.CodeChallenge, req.CSRFHash, req.ExpiresAt)
	return err
}

func (s *OAuthStore) GetAuthorizationRequest(ctx context.Context, id string) (*OAuthAuthorizationRequest, error) {
	var out OAuthAuthorizationRequest
	err := s.db.GetContext(ctx, &out, `SELECT id,client_id,redirect_uri,scope,state,nonce,code_challenge,csrf_hash,COALESCE(user_id,'') user_id,auth_time,expires_at FROM oauth_authorization_requests WHERE id=?`, id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &out, err
}

func (s *OAuthStore) DeleteAuthorizationRequest(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM oauth_authorization_requests WHERE id=?`, id)
	return err
}

func (s *OAuthStore) PruneExpired(ctx context.Context) error {
	for _, query := range []string{
		`DELETE FROM oauth_authorization_requests WHERE expires_at<=CURRENT_TIMESTAMP`,
		`DELETE FROM oauth_authorization_codes WHERE expires_at<=CURRENT_TIMESTAMP`,
		`DELETE FROM oauth_access_tokens WHERE expires_at<=datetime('now','-1 day')`,
		`DELETE FROM oauth_refresh_tokens WHERE expires_at<=datetime('now','-7 day')`,
	} {
		if _, err := s.db.ExecContext(ctx, query); err != nil {
			return err
		}
	}
	return nil
}

func (s *OAuthStore) SetAuthorizationUser(ctx context.Context, id, userID string) error {
	res, err := s.db.ExecContext(ctx, `UPDATE oauth_authorization_requests SET user_id=?,auth_time=? WHERE id=? AND expires_at>CURRENT_TIMESTAMP`, userID, time.Now(), id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		return ErrNotFound
	}
	return nil
}

func (s *OAuthStore) CreateCodeAndDeleteRequest(ctx context.Context, reqID, codeHash string, expiresAt time.Time) (*OAuthAuthorizationCode, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var req OAuthAuthorizationRequest
	if err = tx.GetContext(ctx, &req, `SELECT id,client_id,redirect_uri,scope,state,nonce,code_challenge,csrf_hash,COALESCE(user_id,'') user_id,auth_time,expires_at FROM oauth_authorization_requests WHERE id=? AND expires_at>CURRENT_TIMESTAMP`, reqID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if req.UserID == "" || !req.AuthTime.Valid {
		return nil, ErrNotFound
	}
	code := &OAuthAuthorizationCode{CodeHash: codeHash, ClientID: req.ClientID, UserID: req.UserID, RedirectURI: req.RedirectURI, Scope: req.Scope, Nonce: req.Nonce, CodeChallenge: req.CodeChallenge, AuthTime: req.AuthTime.Time, ExpiresAt: expiresAt}
	_, err = tx.ExecContext(ctx, `INSERT INTO oauth_authorization_codes(code_hash,client_id,user_id,redirect_uri,scope,nonce,code_challenge,auth_time,expires_at) VALUES(?,?,?,?,?,?,?,?,?)`, code.CodeHash, code.ClientID, code.UserID, code.RedirectURI, code.Scope, code.Nonce, code.CodeChallenge, code.AuthTime, code.ExpiresAt)
	if err != nil {
		return nil, err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM oauth_authorization_requests WHERE id=?`, reqID); err != nil {
		return nil, err
	}
	return code, tx.Commit()
}

func (s *OAuthStore) ConsumeCode(ctx context.Context, codeHash, clientID, redirectURI, challenge string) (*OAuthAuthorizationCode, error) {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var out OAuthAuthorizationCode
	err = tx.GetContext(ctx, &out, `SELECT code_hash,client_id,user_id,redirect_uri,scope,nonce,code_challenge,auth_time,expires_at,consumed_at FROM oauth_authorization_codes WHERE code_hash=? AND client_id=? AND redirect_uri=? AND code_challenge=? AND consumed_at IS NULL AND expires_at>CURRENT_TIMESTAMP`, codeHash, clientID, redirectURI, challenge)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	res, err := tx.ExecContext(ctx, `UPDATE oauth_authorization_codes SET consumed_at=CURRENT_TIMESTAMP WHERE code_hash=? AND consumed_at IS NULL`, codeHash)
	if err != nil {
		return nil, err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		return nil, ErrNotFound
	}
	return &out, tx.Commit()
}

func (s *OAuthStore) UpsertGrant(ctx context.Context, id, clientID, userID, scope string) (string, error) {
	var existing string
	err := s.db.GetContext(ctx, &existing, `SELECT id FROM oauth_grants WHERE client_id=? AND user_id=? AND revoked_at IS NULL LIMIT 1`, clientID, userID)
	if err == nil {
		_, err = s.db.ExecContext(ctx, `UPDATE oauth_grants SET scope=?,updated_at=CURRENT_TIMESTAMP WHERE id=?`, scope, existing)
		return existing, err
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO oauth_grants(id,client_id,user_id,scope) VALUES(?,?,?,?)`, id, clientID, userID, scope)
	return id, err
}

func (s *OAuthStore) CreateTokenPair(ctx context.Context, access, refresh OAuthTokenRecord) error {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `INSERT INTO oauth_access_tokens(token_hash,grant_id,client_id,user_id,scope,expires_at) VALUES(?,?,?,?,?,?)`, access.TokenHash, access.GrantID, access.ClientID, access.UserID, access.Scope, access.ExpiresAt)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO oauth_refresh_tokens(token_hash,grant_id,client_id,user_id,scope,family_id,auth_time,expires_at) VALUES(?,?,?,?,?,?,?,?)`, refresh.TokenHash, refresh.GrantID, refresh.ClientID, refresh.UserID, refresh.Scope, refresh.FamilyID, refresh.AuthTime, refresh.ExpiresAt)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *OAuthStore) CreateAccessToken(ctx context.Context, access OAuthTokenRecord) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO oauth_access_tokens(token_hash,grant_id,client_id,user_id,scope,expires_at) VALUES(?,?,?,?,?,?)`, access.TokenHash, access.GrantID, access.ClientID, access.UserID, access.Scope, access.ExpiresAt)
	return err
}

func (s *OAuthStore) GetAccessToken(ctx context.Context, hash string) (*OAuthTokenRecord, error) {
	var out OAuthTokenRecord
	err := s.db.GetContext(ctx, &out, `SELECT t.token_hash,t.grant_id,t.client_id,t.user_id,t.scope,'' family_id,CURRENT_TIMESTAMP auth_time,t.expires_at,t.revoked_at,NULL replaced_by FROM oauth_access_tokens t JOIN oauth_grants g ON g.id=t.grant_id AND g.revoked_at IS NULL WHERE t.token_hash=?`, hash)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &out, err
}

func (s *OAuthStore) GetRefreshToken(ctx context.Context, hash string) (*OAuthTokenRecord, error) {
	var out OAuthTokenRecord
	err := s.db.GetContext(ctx, &out, `SELECT t.token_hash,t.grant_id,t.client_id,t.user_id,t.scope,t.family_id,t.auth_time,t.expires_at,t.revoked_at,t.replaced_by FROM oauth_refresh_tokens t JOIN oauth_grants g ON g.id=t.grant_id AND g.revoked_at IS NULL WHERE t.token_hash=?`, hash)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &out, err
}

func (s *OAuthStore) GetRefreshTokenAny(ctx context.Context, hash string) (*OAuthTokenRecord, error) {
	var out OAuthTokenRecord
	err := s.db.GetContext(ctx, &out, `SELECT token_hash,grant_id,client_id,user_id,scope,family_id,auth_time,expires_at,revoked_at,replaced_by FROM oauth_refresh_tokens WHERE token_hash=?`, hash)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &out, err
}

func (s *OAuthStore) RotateRefresh(ctx context.Context, oldHash string, access, refresh OAuthTokenRecord) error {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE oauth_refresh_tokens SET revoked_at=CURRENT_TIMESTAMP,replaced_by=? WHERE token_hash=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP`, refresh.TokenHash, oldHash)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n != 1 {
		var grantID string
		if lookupErr := tx.GetContext(ctx, &grantID, `SELECT grant_id FROM oauth_refresh_tokens WHERE token_hash=?`, oldHash); lookupErr == nil {
			if _, err = tx.ExecContext(ctx, `UPDATE oauth_grants SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=?`, grantID); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, `UPDATE oauth_access_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE grant_id=?`, grantID); err != nil {
				return err
			}
			if _, err = tx.ExecContext(ctx, `UPDATE oauth_refresh_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE grant_id=?`, grantID); err != nil {
				return err
			}
			if err = tx.Commit(); err != nil {
				return err
			}
			return ErrOAuthRefreshReused
		}
		return ErrNotFound
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO oauth_access_tokens(token_hash,grant_id,client_id,user_id,scope,expires_at) VALUES(?,?,?,?,?,?)`, access.TokenHash, access.GrantID, access.ClientID, access.UserID, access.Scope, access.ExpiresAt)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO oauth_refresh_tokens(token_hash,grant_id,client_id,user_id,scope,family_id,auth_time,expires_at) VALUES(?,?,?,?,?,?,?,?)`, refresh.TokenHash, refresh.GrantID, refresh.ClientID, refresh.UserID, refresh.Scope, refresh.FamilyID, refresh.AuthTime, refresh.ExpiresAt)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *OAuthStore) RevokeToken(ctx context.Context, hash string) error {
	_, err := s.db.ExecContext(ctx, `UPDATE oauth_access_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE token_hash=?`, hash)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `UPDATE oauth_refresh_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE token_hash=?`, hash)
	return err
}

func (s *OAuthStore) RevokeAllByUser(ctx context.Context, userID string) error {
	var grants []string
	if err := s.db.SelectContext(ctx, &grants, `SELECT id FROM oauth_grants WHERE user_id=? AND revoked_at IS NULL`, userID); err != nil {
		return err
	}
	for _, grantID := range grants {
		if err := s.RevokeGrant(ctx, grantID); err != nil {
			return err
		}
	}
	return nil
}

func (s *OAuthStore) RevokeGrant(ctx context.Context, grantID string) error {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `UPDATE oauth_grants SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=?`, grantID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE oauth_access_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE grant_id=?`, grantID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE oauth_refresh_tokens SET revoked_at=COALESCE(revoked_at,CURRENT_TIMESTAMP) WHERE grant_id=?`, grantID); err != nil {
		return err
	}
	return tx.Commit()
}
