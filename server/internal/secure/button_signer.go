package secure

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"os"
	"path/filepath"
	"sync"
)

// ButtonSigner 生成/验证消息按钮的 tid（RSA 签名，不可伪造）。
// 客户端内置公钥离线验签；私钥仅服务端持有并持久化。
type ButtonSigner struct {
	mu        sync.RWMutex
	key       *rsa.PrivateKey
	publicPEM []byte
}

// LoadOrCreateButtonSigner 从 keyDir 加载或生成 RSA 密钥对。
func LoadOrCreateButtonSigner(keyDir string) (*ButtonSigner, error) {
	privPath := filepath.Join(keyDir, "button_sign_rsa.pem")
	pubPath := filepath.Join(keyDir, "button_sign_rsa_pub.pem")

	if data, err := os.ReadFile(privPath); err == nil {
		if block, _ := pem.Decode(data); block != nil {
			if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
				signer := &ButtonSigner{key: key}
				if pub, perr := os.ReadFile(pubPath); perr == nil {
					signer.publicPEM = pub
				} else {
					signer.publicPEM = signer.exportPublic()
					_ = os.WriteFile(pubPath, signer.publicPEM, 0o600)
				}
				return signer, nil
			}
		}
	}

	// 生成新密钥对
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(keyDir, 0o700); err != nil {
		return nil, err
	}
	privDER := x509.MarshalPKCS1PrivateKey(key)
	privPEM := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: privDER})
	if err := os.WriteFile(privPath, privPEM, 0o600); err != nil {
		return nil, err
	}
	signer := &ButtonSigner{key: key}
	signer.publicPEM = signer.exportPublic()
	_ = os.WriteFile(pubPath, signer.publicPEM, 0o600)
	return signer, nil
}

// SignTID 对 (msgID, index, action, nonce) 签名，返回 base64url tid。
func (s *ButtonSigner) SignTID(msgID string, index int, action string, nonce string) string {
	payload := buildTIDPayload(msgID, index, action, nonce)
	digest := sha256.Sum256([]byte(payload))
	s.mu.RLock()
	defer s.mu.RUnlock()
	sig, err := rsa.SignPKCS1v15(rand.Reader, s.key, crypto.SHA256, digest[:])
	if err != nil {
		return ""
	}
	return base64.RawURLEncoding.EncodeToString(sig)
}

// VerifyTID 用公钥离线验证 tid 是否匹配 (msgID, index, action, nonce)。
func (s *ButtonSigner) VerifyTID(tid string, msgID string, index int, action string, nonce string) bool {
	if tid == "" {
		return false
	}
	sig, err := base64.RawURLEncoding.DecodeString(tid)
	if err != nil {
		return false
	}
	payload := buildTIDPayload(msgID, index, action, nonce)
	digest := sha256.Sum256([]byte(payload))
	s.mu.RLock()
	defer s.mu.RUnlock()
	return rsa.VerifyPKCS1v15(&s.key.PublicKey, crypto.SHA256, digest[:], sig) == nil
}

// PublicPEM 返回公钥 PEM（供客户端内置验签）。
func (s *ButtonSigner) PublicPEM() []byte {
	return s.publicPEM
}

func (s *ButtonSigner) exportPublic() []byte {
	pubDER, err := x509.MarshalPKIXPublicKey(&s.key.PublicKey)
	if err != nil {
		return nil
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: pubDER})
}

func buildTIDPayload(msgID string, index int, action string, nonce string) string {
	return msgID + "|" + itoa(index) + "|" + action + "|" + nonce
}

func itoa(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [20]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
