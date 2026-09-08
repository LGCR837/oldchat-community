package auth

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type OAuthSigningKey struct {
	Private ed25519.PrivateKey
	Public  ed25519.PublicKey
	KeyID   string
}

type IDTokenClaims struct {
	Nonce    string `json:"nonce,omitempty"`
	AuthTime int64  `json:"auth_time"`
	UID      string `json:"uid,omitempty"`
	Name     string `json:"name,omitempty"`
	Picture  string `json:"picture,omitempty"`
	jwt.RegisteredClaims
}

func LoadOrCreateOAuthSigningKey(path string) (*OAuthSigningKey, error) {
	if strings.TrimSpace(path) == "" {
		return nil, errors.New("OAuth signing key path is empty")
	}
	var private ed25519.PrivateKey
	if raw, err := os.ReadFile(path); err == nil {
		block, _ := pem.Decode(raw)
		if block == nil {
			return nil, errors.New("invalid OAuth signing key PEM")
		}
		key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
		var ok bool
		private, ok = key.(ed25519.PrivateKey)
		if !ok {
			return nil, errors.New("OAuth signing key is not Ed25519")
		}
	} else if os.IsNotExist(err) {
		_, generated, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		private = generated
		der, err := x509.MarshalPKCS8PrivateKey(private)
		if err != nil {
			return nil, err
		}
		encoded := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
		if err = os.WriteFile(path, encoded, 0600); err != nil {
			return nil, err
		}
	} else {
		return nil, err
	}
	public := private.Public().(ed25519.PublicKey)
	digest := sha256.Sum256(public)
	return &OAuthSigningKey{
		Private: private,
		Public:  public,
		KeyID:   base64.RawURLEncoding.EncodeToString(digest[:9]),
	}, nil
}

func (k *OAuthSigningKey) SignIDToken(issuer, audience, subject, nonce, uid, name, picture string, authTime time.Time, ttl time.Duration) (string, error) {
	now := time.Now()
	jti, _, err := NewRefreshToken()
	if err != nil {
		return "", err
	}
	claims := IDTokenClaims{
		Nonce: nonce, AuthTime: authTime.Unix(), UID: uid, Name: name, Picture: picture,
		RegisteredClaims: jwt.RegisteredClaims{
			Issuer: issuer, Subject: subject, Audience: jwt.ClaimStrings{audience},
			IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(ttl)),
			ID: jti,
		},
	}
	token := jwt.NewWithClaims(jwt.SigningMethodEdDSA, claims)
	token.Header["kid"] = k.KeyID
	token.Header["typ"] = "JWT"
	return token.SignedString(k.Private)
}

func (k *OAuthSigningKey) JWK() map[string]string {
	return map[string]string{
		"kty": "OKP", "crv": "Ed25519", "use": "sig", "alg": "EdDSA",
		"kid": k.KeyID, "x": base64.RawURLEncoding.EncodeToString(k.Public),
	}
}

func PKCEChallengeS256(verifier string) (string, bool) {
	if len(verifier) < 43 || len(verifier) > 128 {
		return "", false
	}
	for i := 0; i < len(verifier); i++ {
		c := verifier[i]
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
			(c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~' {
			continue
		}
		return "", false
	}
	digest := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(digest[:]), true
}

func VerifyPKCES256(verifier, expectedChallenge string) bool {
	actual, ok := PKCEChallengeS256(verifier)
	return ok && expectedChallenge != "" && ConstantTimeEqual(actual, expectedChallenge)
}

func ValidateOAuthHTTPSURL(raw string, allowQuery bool) bool {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Fragment != "" {
		return false
	}
	if !allowQuery && u.RawQuery != "" {
		return false
	}
	return true
}

func NormalizeScopes(requested, allowed string) (string, bool) {
	allow := make(map[string]bool)
	for _, item := range strings.Fields(allowed) {
		allow[item] = true
	}
	seen := make(map[string]bool)
	out := make([]string, 0)
	for _, item := range strings.Fields(requested) {
		if !allow[item] || seen[item] {
			return "", false
		}
		seen[item] = true
		out = append(out, item)
	}
	if !seen["openid"] {
		return "", false
	}
	sort.Strings(out)
	return strings.Join(out, " "), true
}

func ScopeContains(scope, required string) bool {
	for _, item := range strings.Fields(scope) {
		if item == required {
			return true
		}
	}
	return false
}

func ConstantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	var diff byte
	for i := 0; i < len(a); i++ {
		diff |= a[i] ^ b[i]
	}
	return diff == 0
}
