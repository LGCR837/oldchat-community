using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using Newtonsoft.Json;
using Org.BouncyCastle.Asn1.Sec;
using Org.BouncyCastle.Asn1.X9;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Agreement;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Math;
using Org.BouncyCastle.Security;
using Org.BouncyCastle.X509;

namespace OldChat.Core.Crypto
{
    /// <summary>
    /// 会话加密通道。协议由服务端 internal/secure 与 internal/http/secure_middleware.go 定义：
    /// 密钥协商 P-256 ECDH，共享密钥取 X 坐标定长 32 字节，
    /// encKey = SHA256(secret || "enc")，macKey = SHA256(secret || "mac")；
    /// 报文 AES-256-CBC + PKCS7，MAC 为 HMAC-SHA256(macKey, iv || ciphertext)。
    /// </summary>
    public sealed class SecureChannel
    {
        private const string EncSuffix = "enc";
        private const string MacSuffix = "mac";

        private readonly byte[] _encKey;
        private readonly byte[] _macKey;

        public string SessionId { get; private set; }

        internal SecureChannel(string sessionId, byte[] encKey, byte[] macKey)
        {
            SessionId = sessionId;
            _encKey = encKey;
            _macKey = macKey;
        }

        private sealed class Envelope
        {
            [JsonProperty("iv")] public string Iv;
            [JsonProperty("data")] public string Data;
            [JsonProperty("mac")] public string Mac;
        }

        /// <summary>加密后返回服务端要求的信封 JSON。</summary>
        public string Seal(byte[] plain)
        {
            if (plain == null) plain = new byte[0];
            using (var aes = new AesCryptoServiceProvider())
            {
                aes.KeySize = 256;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = _encKey;
                aes.GenerateIV();

                byte[] cipher;
                using (var enc = aes.CreateEncryptor())
                {
                    cipher = enc.TransformFinalBlock(plain, 0, plain.Length);
                }

                byte[] mac = ComputeMac(aes.IV, cipher);
                var env = new Envelope
                {
                    Iv = Convert.ToBase64String(aes.IV),
                    Data = Convert.ToBase64String(cipher),
                    Mac = Convert.ToBase64String(mac)
                };
                return JsonConvert.SerializeObject(env);
            }
        }

        public string SealText(string plain)
        {
            return Seal(Encoding.UTF8.GetBytes(plain ?? string.Empty));
        }

        /// <summary>解密服务端信封，返回明文。MAC 不符直接抛异常。</summary>
        public byte[] Unseal(string envelopeJson)
        {
            var env = JsonConvert.DeserializeObject<Envelope>(envelopeJson);
            if (env == null || string.IsNullOrEmpty(env.Iv) || string.IsNullOrEmpty(env.Data) || string.IsNullOrEmpty(env.Mac))
            {
                throw new CryptographicException("加密报文缺字段");
            }

            byte[] iv = Convert.FromBase64String(env.Iv);
            byte[] cipher = Convert.FromBase64String(env.Data);
            byte[] mac = Convert.FromBase64String(env.Mac);

            byte[] expected = ComputeMac(iv, cipher);
            if (!FixedTimeEquals(mac, expected))
            {
                throw new CryptographicException("MAC 校验失败");
            }

            using (var aes = new AesCryptoServiceProvider())
            {
                aes.KeySize = 256;
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                aes.Key = _encKey;
                aes.IV = iv;
                using (var dec = aes.CreateDecryptor())
                {
                    return dec.TransformFinalBlock(cipher, 0, cipher.Length);
                }
            }
        }

        /// <summary>
        /// /v2 请求签名：HMAC-SHA256(macKey, "METHOD\nPATH\nTS\nNONCE")，Base64 无填充。
        /// </summary>
        public string Sign(string method, string path, string timestamp, string nonce)
        {
            string signing = (method ?? "GET").ToUpperInvariant()
                             + "\n" + path
                             + "\n" + timestamp
                             + "\n" + nonce;
            using (var hmac = new HMACSHA256(_macKey))
            {
                byte[] sum = hmac.ComputeHash(Encoding.UTF8.GetBytes(signing));
                return Convert.ToBase64String(sum).TrimEnd('=');
            }
        }

        private byte[] ComputeMac(byte[] iv, byte[] cipher)
        {
            using (var hmac = new HMACSHA256(_macKey))
            {
                byte[] buffer = new byte[iv.Length + cipher.Length];
                Buffer.BlockCopy(iv, 0, buffer, 0, iv.Length);
                Buffer.BlockCopy(cipher, 0, buffer, iv.Length, cipher.Length);
                return hmac.ComputeHash(buffer);
            }
        }

        private static bool FixedTimeEquals(byte[] a, byte[] b)
        {
            if (a == null || b == null) return false;
            if (a.Length != b.Length) return false;
            int diff = 0;
            for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i];
            return diff == 0;
        }
    }

    /// <summary>
    /// 握手第一阶段：本地生成 P-256 密钥对，把公钥按 X.509 SubjectPublicKeyInfo DER 交给服务端。
    /// </summary>
    public sealed class ChannelHandshake
    {
        private readonly AsymmetricCipherKeyPair _keyPair;

        public string ClientPubBase64 { get; private set; }

        private ChannelHandshake(AsymmetricCipherKeyPair keyPair, string clientPubBase64)
        {
            _keyPair = keyPair;
            ClientPubBase64 = clientPubBase64;
        }

        private static ECDomainParameters P256Domain()
        {
            X9ECParameters curve = SecNamedCurves.GetByName("secp256r1");
            return new ECDomainParameters(curve.Curve, curve.G, curve.N, curve.H);
        }

        public static ChannelHandshake Begin()
        {
            var generator = new ECKeyPairGenerator();
            generator.Init(new ECKeyGenerationParameters(P256Domain(), new SecureRandom()));
            AsymmetricCipherKeyPair pair = generator.GenerateKeyPair();

            var pub = (ECPublicKeyParameters)pair.Public;
            byte[] der = EncodeP256Spki(pub);
            return new ChannelHandshake(pair, Convert.ToBase64String(der));
        }

        /// <summary>
        /// 手工拼 X.509 SubjectPublicKeyInfo（P-256），前缀固定 26 字节后接 0x04||X||Y。
        /// 不用 BouncyCastle 的工厂方法，避免不同版本编出 Go 侧不认的结构。
        /// </summary>
        private static readonly byte[] SpkiPrefixP256 =
        {
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07,
            0x03, 0x42, 0x00
        };

        private static byte[] EncodeP256Spki(ECPublicKeyParameters pub)
        {
            byte[] x = pub.Q.AffineXCoord.GetEncoded();
            byte[] y = pub.Q.AffineYCoord.GetEncoded();

            byte[] der = new byte[SpkiPrefixP256.Length + 1 + x.Length + y.Length];
            Buffer.BlockCopy(SpkiPrefixP256, 0, der, 0, SpkiPrefixP256.Length);
            int offset = SpkiPrefixP256.Length;
            der[offset++] = 0x04;
            Buffer.BlockCopy(x, 0, der, offset, x.Length);
            Buffer.BlockCopy(y, 0, der, offset + x.Length, y.Length);
            return der;
        }

        /// <summary>
        /// 握手第二阶段：用服务端公钥算出共享密钥并派生对称密钥。
        /// </summary>
        public SecureChannel Complete(string serverPubBase64, string sessionId)
        {
            if (string.IsNullOrEmpty(serverPubBase64)) throw new ArgumentNullException("serverPubBase64");
            if (string.IsNullOrEmpty(sessionId)) throw new ArgumentNullException("sessionId");

            var serverPub = (ECPublicKeyParameters)PublicKeyFactory.CreateKey(Convert.FromBase64String(serverPubBase64));

            var agreement = new ECDHBasicAgreement();
            agreement.Init(_keyPair.Private);
            BigInteger shared = agreement.CalculateAgreement(serverPub);

            int fieldSize = (serverPub.Parameters.Curve.FieldSize + 7) / 8;
            byte[] secret = FixedLength(shared, fieldSize);

            byte[] encKey = DeriveKey(secret, "enc");
            byte[] macKey = DeriveKey(secret, "mac");
            return new SecureChannel(sessionId, encKey, macKey);
        }

        /// <summary>把大整数转成定长大端字节串，服务端按字段宽度左补零。</summary>
        private static byte[] FixedLength(BigInteger value, int size)
        {
            byte[] raw = value.ToByteArrayUnsigned();
            if (raw.Length == size) return raw;
            if (raw.Length > size)
            {
                // 理论上不会发生：共享点 X 坐标一定小于素域 p。
                byte[] trimmed = new byte[size];
                Buffer.BlockCopy(raw, raw.Length - size, trimmed, 0, size);
                return trimmed;
            }
            byte[] padded = new byte[size];
            Buffer.BlockCopy(raw, 0, padded, size - raw.Length, raw.Length);
            return padded;
        }

        private static byte[] DeriveKey(byte[] secret, string suffix)
        {
            byte[] suffixBytes = Encoding.UTF8.GetBytes(suffix);
            byte[] buffer = new byte[secret.Length + suffixBytes.Length];
            Buffer.BlockCopy(secret, 0, buffer, 0, secret.Length);
            Buffer.BlockCopy(suffixBytes, 0, buffer, secret.Length, suffixBytes.Length);
            using (var sha = SHA256.Create())
            {
                return sha.ComputeHash(buffer);
            }
        }
    }
}
