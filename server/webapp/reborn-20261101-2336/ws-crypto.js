/* ===== 旧聊 OldChat · WS 加密模块 =====
 * 对齐服务端/安卓端协议：
 *  - ECDH P-256 (secp256r1)
 *  - 公钥交换: SPKI (X.509 SubjectPublicKeyInfo) Base64
 *  - 共享密钥: ECDH 协商, 归一化到 32 字节 (P256 恰好 32)
 *  - encKey = SHA256(secret || "enc")
 *  - macKey = SHA256(secret || "mac")
 *  - 消息: { iv, data, mac }, iv=16B, data=AES-256-CBC(PKCS7)密文, mac=HMAC-SHA256(macKey, iv||data)
 */
(function (global) {
  'use strict';

  var CRYPTO = {};

  /* ---------- 工具 ---------- */
  function ab2b64(buf) {
    // 分块转换，避免 apply 展开超过引擎栈限制(65535+)导致 RangeError
    var u8 = new Uint8Array(buf);
    var bin = '';
    var CHUNK = 0x8000;
    for (var i = 0; i < u8.length; i += CHUNK) {
      bin += String.fromCharCode.apply(null, u8.subarray(i, i + CHUNK));
    }
    return btoa(bin);
  }
  function b642ab(b64) {
    var bin = atob(b64);
    var u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return u8.buffer;
  }
  function bytesToHex(u8) {
    return Array.prototype.map.call(u8, function (b) {
      return ('0' + b.toString(16)).slice(-2);
    }).join('');
  }

  /* ---------- 生成客户端 ECDH 密钥对, 返回 SPKI Base64 ---------- */
  CRYPTO.generateClientKeyPair = function () {
    return crypto.subtle.generateKey(
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits']
    ).then(function (keyPair) {
      return crypto.subtle.exportKey('spki', keyPair.publicKey).then(function (spki) {
        return { keyPair: keyPair, publicKeyB64: ab2b64(spki) };
      });
    });
  };

  /* ---------- 导入服务端 SPKI 公钥 ---------- */
  function importServerPublicKey(b64) {
    return crypto.subtle.importKey(
      'spki',
      b642ab(b64),
      { name: 'ECDH', namedCurve: 'P-256' },
      false,
      []
    );
  }

  /* ---------- 派生 encKey/macKey ---------- */
  function deriveSessionKeys(secretBuf) {
    function sha256WithSuffix(suffix) {
      var len = secretBuf.byteLength + suffix.length;
      var combined = new Uint8Array(len);
      combined.set(new Uint8Array(secretBuf), 0);
      combined.set(suffix, secretBuf.byteLength);
      return crypto.subtle.digest('SHA-256', combined.buffer);
    }
    return Promise.all([
      sha256WithSuffix(new Uint8Array([0x65, 0x6e, 0x63])), // "enc"
      sha256WithSuffix(new Uint8Array([0x6d, 0x61, 0x63]))  // "mac"
    ]).then(function (keys) {
      return { encKey: keys[0], macKey: keys[1] };
    });
  }

  /* ---------- 握手: 返回 { sessionId, encKey, macKey, session } ---------- */
  CRYPTO.handshake = function (apiBase, token) {
    return CRYPTO.generateClientKeyPair().then(function (gen) {
      return fetch(apiBase + '/auth/handshake', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': token ? 'Bearer ' + token : ''
        },
        body: JSON.stringify({ client_pub: gen.publicKeyB64 })
      }).then(function (resp) {
        if (!resp.ok) throw new Error('handshake failed: ' + resp.status);
        return resp.json();
      }).then(function (data) {
        var serverPubB64 = data.server_pub;
        var sessionId = data.session_id;
        if (!serverPubB64 || !sessionId) throw new Error('handshake bad response');
        return importServerPublicKey(serverPubB64).then(function (serverKey) {
          return crypto.subtle.deriveBits(
            { name: 'ECDH', public: serverKey },
            gen.keyPair.privateKey,
            256
          ).then(function (secret) {
            return deriveSessionKeys(secret).then(function (keys) {
              return {
                sessionId: sessionId,
                encKey: keys.encKey,
                macKey: keys.macKey,
                keyPair: gen.keyPair,
                serverKey: serverKey
              };
            });
          });
        });
      });
    });
  };

  /* ---------- 加密: 明文 -> {iv,data,mac} JSON 字符串 ---------- */
  CRYPTO.encrypt = function (session, plaintext) {
    var iv = crypto.getRandomValues(new Uint8Array(16));
    var enc = new TextEncoder().encode(plaintext);
    return crypto.subtle.importKey(
      'raw', session.encKey, { name: 'AES-CBC' }, false, ['encrypt']
    ).then(function (aesKey) {
      return crypto.subtle.encrypt(
        { name: 'AES-CBC', iv: iv },
        aesKey,
        enc
      );
    }).then(function (cipher) {
      // mac = HMAC-SHA256(macKey, iv || cipher)
      var toMac = new Uint8Array(iv.length + cipher.byteLength);
      toMac.set(iv, 0);
      toMac.set(new Uint8Array(cipher), iv.length);
      return crypto.subtle.importKey(
        'raw', session.macKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
      ).then(function (hmacKey) {
        return crypto.subtle.sign('HMAC', hmacKey, toMac);
      }).then(function (mac) {
        return JSON.stringify({
          iv: ab2b64(iv.buffer),
          data: ab2b64(cipher),
          mac: ab2b64(mac)
        });
      });
    });
  };

  /* ---------- 解密: {iv,data,mac} 或原始文本 -> 明文 ---------- */
  CRYPTO.decrypt = function (session, payload) {
    var obj;
    try { obj = JSON.parse(payload); } catch (e) { return Promise.resolve(payload); }
    if (!obj || !obj.iv || !obj.data || !obj.mac) {
      // 非加密消息(明文)，直接返回
      return Promise.resolve(payload);
    }
    var iv = new Uint8Array(b642ab(obj.iv));
    var data = b642ab(obj.data);
    var mac = b642ab(obj.mac);

    var toMac = new Uint8Array(iv.length + data.byteLength);
    toMac.set(iv, 0);
    toMac.set(new Uint8Array(data), iv.length);

    return crypto.subtle.importKey(
      'raw', session.macKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']
    ).then(function (hmacKey) {
      return crypto.subtle.verify('HMAC', hmacKey, mac, toMac);
    }).then(function (valid) {
      if (!valid) throw new Error('MAC mismatch');
      return crypto.subtle.importKey(
        'raw', session.encKey, { name: 'AES-CBC' }, false, ['decrypt']
      );
    }).then(function (aesKey) {
      return crypto.subtle.decrypt(
        { name: 'AES-CBC', iv: iv },
        aesKey,
        data
      );
    }).then(function (plainBuf) {
      return new TextDecoder().decode(plainBuf);
    });
  };

  global.OldChatCrypto = CRYPTO;
})(window);
