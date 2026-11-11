package httpapi

import "strings"

// captchaPowJS 是注册页与找回密码页共用的自建 PoW 前端实现。
// 只含纯计算部分（SHA-256 与求解循环），页面在渲染时替换 /*__POW_JS__*/ 标记，
// 这样两处页面不必各维护一份实现，也不需要任何第三方脚本。
const captchaPowJS = `
      var SHA256_K = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
      ];

      // 复用缓冲区：求解循环会连续算几十万次，避免每次分配对象。
      var POW_W = new Int32Array(64);
      var POW_BUF = new Uint8Array(512);
      var POW_OUT = new Uint8Array(32);

      function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

      function asciiBytes(str) {
        var len = str.length, out = new Uint8Array(len), i;
        for (i = 0; i < len; i++) { out[i] = str.charCodeAt(i) & 255; }
        return out;
      }

      // powDigest 把 baseBytes 与 nonce 拼起来直接算 SHA-256，结果写入 POW_OUT。
      function powDigest(baseBytes, nonce) {
        var base = baseBytes.length, nlen = nonce.length, len = base + nlen;
        var need = ((len + 9 + 63) >> 6) << 6;
        if (need > POW_BUF.length) { POW_BUF = new Uint8Array(need * 2); }
        var buf = POW_BUF, w = POW_W, i, t, off;
        buf.set(baseBytes, 0);
        for (i = 0; i < nlen; i++) { buf[base + i] = nonce.charCodeAt(i) & 255; }
        var padded = ((len + 9 + 63) >> 6) << 6;
        buf[len] = 0x80;
        for (i = len + 1; i < padded; i++) { buf[i] = 0; }
        var bits = len * 8;
        buf[padded - 8] = 0;
        buf[padded - 7] = 0;
        buf[padded - 6] = 0;
        buf[padded - 5] = 0;
        buf[padded - 4] = (bits >>> 24) & 255;
        buf[padded - 3] = (bits >>> 16) & 255;
        buf[padded - 2] = (bits >>> 8) & 255;
        buf[padded - 1] = bits & 255;

        var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
        var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
        for (off = 0; off < padded; off += 64) {
          for (t = 0; t < 16; t++) {
            w[t] = (buf[off + t * 4] << 24) | (buf[off + t * 4 + 1] << 16) | (buf[off + t * 4 + 2] << 8) | buf[off + t * 4 + 3];
          }
          for (t = 16; t < 64; t++) {
            var x = w[t - 15], y = w[t - 2];
            var s0 = rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3);
            var s1 = rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10);
            w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
          }
          var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
          for (t = 0; t < 64; t++) {
            var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            var ch = (e & f) ^ (~e & g);
            var t1 = (h + S1 + ch + SHA256_K[t] + w[t]) | 0;
            var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            var maj = (a & b) ^ (a & c) ^ (b & c);
            var t2 = (S0 + maj) | 0;
            h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
          }
          h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
          h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
        }
        var out = POW_OUT;
        out[0] = h0 >>> 24; out[1] = (h0 >>> 16) & 255; out[2] = (h0 >>> 8) & 255; out[3] = h0 & 255;
        out[4] = h1 >>> 24; out[5] = (h1 >>> 16) & 255; out[6] = (h1 >>> 8) & 255; out[7] = h1 & 255;
        out[8] = h2 >>> 24; out[9] = (h2 >>> 16) & 255; out[10] = (h2 >>> 8) & 255; out[11] = h2 & 255;
        out[12] = h3 >>> 24; out[13] = (h3 >>> 16) & 255; out[14] = (h3 >>> 8) & 255; out[15] = h3 & 255;
        out[16] = h4 >>> 24; out[17] = (h4 >>> 16) & 255; out[18] = (h4 >>> 8) & 255; out[19] = h4 & 255;
        out[20] = h5 >>> 24; out[21] = (h5 >>> 16) & 255; out[22] = (h5 >>> 8) & 255; out[23] = h5 & 255;
        out[24] = h6 >>> 24; out[25] = (h6 >>> 16) & 255; out[26] = (h6 >>> 8) & 255; out[27] = h6 & 255;
        out[28] = h7 >>> 24; out[29] = (h7 >>> 16) & 255; out[30] = (h7 >>> 8) & 255; out[31] = h7 & 255;
        return out;
      }

      function sha256(str) { return powDigest(asciiBytes(str), ''); }

      function leadingZeroBits(bytes) {
        var n = 0, i, k, b;
        for (i = 0; i < bytes.length; i++) {
          b = bytes[i];
          if (b === 0) { n += 8; continue; }
          for (k = 7; k >= 0; k--) {
            if (b & (1 << k)) { return n; }
            n++;
          }
          return n;
        }
        return n;
      }

      function solvePow(challenge, difficulty) {
        return new Promise(function (resolve, reject) {
          var base = asciiBytes(challenge);
          var nonce = 0, started = Date.now();
          function step() {
            var deadline = Date.now() + 40;
            while (Date.now() < deadline) {
              for (var i = 0; i < 4000; i++) {
                if (leadingZeroBits(powDigest(base, '' + nonce)) >= difficulty) {
                  resolve('' + nonce);
                  return;
                }
                nonce++;
              }
            }
            if (Date.now() - started > 40000) { reject(new Error('pow timeout')); return; }
            setTimeout(step, 0);
          }
          step();
        });
      }
`

// injectPowJS 把共用的人机验证脚本填入页面模板。
func injectPowJS(page string) string {
	return strings.ReplaceAll(page, "      /*__POW_JS__*/", captchaPowJS)
}
