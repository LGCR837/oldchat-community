using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using OldChat.Core.Crypto;
using OldChat.Core.Logging;

namespace OldChat.Core.Net
{
    /// <summary>
    /// 唯一网络出口。所有请求默认走加密会话，/v2 请求额外带 HMAC 签名。
    /// 握手、重新握手、token 刷新重试都在这一层收敛，业务层不需要关心。
    /// </summary>
    public sealed class ApiClient : IDisposable
    {
        private const string HeaderEnc = "X-Enc";
        private const string HeaderSession = "X-Session";
        private const string HeaderAuth = "X-Auth";
        private const string HeaderCompression = "X-Enc-Compression";
        private const string HeaderTs = "X-Ts";
        private const string HeaderNonce = "X-Nonce";
        private const string HeaderSign = "X-Sign";

        private readonly HttpClient _http;
        private readonly SemaphoreSlim _handshakeGate = new SemaphoreSlim(1, 1);

        public string Origin { get; private set; }
        public SecureChannel Channel { get; private set; }
        public string AccessToken { get; set; }
        public string DeviceId { get; set; }
        public TimeSpan Timeout { get; set; }

        /// <summary>401 时的续期回调，返回 true 表示已拿到新 token 可以重试一次。</summary>
        public Func<Task<bool>> OnAccessTokenRejected { get; set; }

        public ApiClient(string origin)
        {
            if (string.IsNullOrWhiteSpace(origin)) throw new ArgumentException("origin 不能为空", "origin");
            Origin = origin.TrimEnd('/');
            Timeout = TimeSpan.FromSeconds(20);

            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            ServicePointManager.Expect100Continue = false;

            var handler = new HttpClientHandler
            {
                AutomaticDecompression = DecompressionMethods.None,
                UseCookies = false,
                AllowAutoRedirect = true
            };
            _http = new HttpClient(handler);
            _http.Timeout = System.Threading.Timeout.InfiniteTimeSpan;
            _http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "OldChat-Desktop/0.1 (Windows)");
            _http.DefaultRequestHeaders.TryAddWithoutValidation("Accept", "application/json");
        }

        public void Dispose()
        {
            _http.Dispose();
            _handshakeGate.Dispose();
        }

        // ---------- 握手 ----------

        public async Task EnsureHandshakeAsync()
        {
            if (Channel != null) return;
            await _handshakeGate.WaitAsync().ConfigureAwait(false);
            try
            {
                if (Channel != null) return;
                Channel = await HandshakeCoreAsync().ConfigureAwait(false);
            }
            finally
            {
                _handshakeGate.Release();
            }
        }

        private void ResetHandshake()
        {
            Channel = null;
        }

        private async Task<SecureChannel> HandshakeCoreAsync()
        {
            ChannelHandshake handshake = ChannelHandshake.Begin();
            var body = new JObject { ["client_pub"] = handshake.ClientPubBase64 };

            // 握手本身必须明文：此时还没有会话密钥。
            using (var req = new HttpRequestMessage(HttpMethod.Post, BuildUri("/v1/auth/handshake", null)))
            {
                req.Content = new StringContent(body.ToString(Formatting.None), Encoding.UTF8, "application/json");
                using (var resp = await SendRawAsync(req).ConfigureAwait(false))
                {
                    string text = await ReadTextAsync(resp, null).ConfigureAwait(false);
                    if (!resp.IsSuccessStatusCode) throw BuildError(resp.StatusCode, text);

                    var json = JObject.Parse(text);
                    string sessionId = (string)json["session_id"];
                    string serverPub = (string)json["server_pub"];
                    if (string.IsNullOrEmpty(sessionId) || string.IsNullOrEmpty(serverPub))
                    {
                        throw new ApiException(resp.StatusCode, "handshake_failed", "握手响应字段缺失");
                    }
                    Log.Info("加密会话已建立 session=" + sessionId);
                    return handshake.Complete(serverPub, sessionId);
                }
            }
        }

        // ---------- 请求 ----------

        public Task<JToken> GetAsync(string path, IDictionary<string, string> query = null)
        {
            return SendAsync(HttpMethod.Get, path, query, null);
        }

        public Task<JToken> PostAsync(string path, object body = null, IDictionary<string, string> query = null)
        {
            return SendAsync(HttpMethod.Post, path, query, body);
        }

        public Task<JToken> DeleteAsync(string path, IDictionary<string, string> query = null)
        {
            return SendAsync(HttpMethod.Delete, path, query, null);
        }

        public async Task<JToken> SendAsync(HttpMethod method, string path, IDictionary<string, string> query, object body, bool allowRetry = true)
        {
            bool needAuth = !IsPublicPath(path);
            if (needAuth || Channel != null)
            {
                await EnsureHandshakeAsync().ConfigureAwait(false);
            }

            SecureChannel channel = Channel;
            using (var req = BuildRequest(method, path, query, body, channel))
            {
                HttpResponseMessage resp = null;
                try
                {
                    resp = await SendRawAsync(req).ConfigureAwait(false);
                    string text = await ReadTextAsync(resp, channel).ConfigureAwait(false);

                    if (resp.IsSuccessStatusCode)
                    {
                        if (string.IsNullOrWhiteSpace(text)) return null;
                        try
                        {
                            return JToken.Parse(text);
                        }
                        catch (JsonException)
                        {
                            // 少数接口返回纯文本，包成字符串方便统一处理
                            return new JValue(text);
                        }
                    }

                    ApiException err = BuildError(resp.StatusCode, text);
                    if (allowRetry && err.IsSessionInvalid)
                    {
                        Log.Warn("会话失效，重新握手后重试：" + path);
                        ResetHandshake();
                        await EnsureHandshakeAsync().ConfigureAwait(false);
                        return await SendAsync(method, path, query, body, false).ConfigureAwait(false);
                    }

                    if (allowRetry && resp.StatusCode == HttpStatusCode.Unauthorized && OnAccessTokenRejected != null)
                    {
                        bool renewed = await OnAccessTokenRejected().ConfigureAwait(false);
                        if (renewed)
                        {
                            Log.Info("token 已续期，重试：" + path);
                            return await SendAsync(method, path, query, body, false).ConfigureAwait(false);
                        }
                    }

                    throw err;
                }
                finally
                {
                    if (resp != null) resp.Dispose();
                }
            }
        }

        private static bool IsPublicPath(string path)
        {
            return path == "/v1/auth/login"
                   || path == "/v1/auth/handshake"
                   || path == "/v1/auth/register"
                   || path.StartsWith("/v1/auth/email", StringComparison.Ordinal)
                   || path.StartsWith("/v1/auth/captcha", StringComparison.Ordinal)
                   || path == "/v1/auth/refresh";
        }

        private HttpRequestMessage BuildRequest(HttpMethod method, string path, IDictionary<string, string> query, object body, SecureChannel channel)
        {
            var req = new HttpRequestMessage(method, BuildUri(path, query));

            if (channel != null)
            {
                req.Headers.TryAddWithoutValidation(HeaderEnc, "1");
                req.Headers.TryAddWithoutValidation(HeaderSession, channel.SessionId);
                if (!string.IsNullOrEmpty(AccessToken))
                {
                    // 服务端把解密结果直接塞进 Authorization，所以这里必须带 Bearer 前缀。
                    req.Headers.TryAddWithoutValidation(HeaderAuth, channel.SealText("Bearer " + AccessToken));
                }

                if (path.StartsWith("/v2", StringComparison.Ordinal))
                {
                    string ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString();
                    string nonce = Guid.NewGuid().ToString("N");
                    req.Headers.TryAddWithoutValidation(HeaderTs, ts);
                    req.Headers.TryAddWithoutValidation(HeaderNonce, nonce);
                    req.Headers.TryAddWithoutValidation(HeaderSign, channel.Sign(method.Method, path, ts, nonce));
                }
            }

            string json = body == null ? null : (body as string ?? JsonConvert.SerializeObject(body, JsonSettings.Write));
            if (json != null || method == HttpMethod.Post || method == HttpMethod.Put)
            {
                string payload = json ?? "{}";
                if (channel != null) payload = channel.SealText(payload);
                req.Content = new StringContent(payload, Encoding.UTF8, "application/json");
            }

            return req;
        }

        private Uri BuildUri(string path, IDictionary<string, string> query)
        {
            var sb = new StringBuilder(Origin);
            sb.Append(path.StartsWith("/") ? path : "/" + path);
            if (query != null && query.Count > 0)
            {
                bool first = true;
                foreach (var pair in query)
                {
                    if (pair.Value == null) continue;
                    sb.Append(first ? '?' : '&');
                    first = false;
                    sb.Append(Uri.EscapeDataString(pair.Key));
                    sb.Append('=');
                    sb.Append(Uri.EscapeDataString(pair.Value));
                }
            }
            return new Uri(sb.ToString());
        }

        private async Task<HttpResponseMessage> SendRawAsync(HttpRequestMessage req)
        {
            using (var cts = new CancellationTokenSource(Timeout))
            {
                return await _http.SendAsync(req, HttpCompletionOption.ResponseContentRead, cts.Token).ConfigureAwait(false);
            }
        }

        private async Task<string> ReadTextAsync(HttpResponseMessage resp, SecureChannel channel)
        {
            byte[] raw = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
            if (raw.Length == 0) return string.Empty;

            bool encrypted = HeaderHasValue(resp, HeaderEnc, "1");
            if (!encrypted) return Encoding.UTF8.GetString(raw);

            if (channel == null) throw new ApiException(resp.StatusCode, "no_session", "响应已加密但本地无会话密钥");

            byte[] plain = channel.Unseal(Encoding.UTF8.GetString(raw));
            if (HeaderHasValue(resp, HeaderCompression, "gzip"))
            {
                plain = Gunzip(plain);
            }
            return Encoding.UTF8.GetString(plain);
        }

        private static byte[] Gunzip(byte[] data)
        {
            using (var input = new MemoryStream(data))
            using (var gzip = new GZipStream(input, CompressionMode.Decompress))
            using (var output = new MemoryStream())
            {
                gzip.CopyTo(output);
                return output.ToArray();
            }
        }

        private static bool HeaderHasValue(HttpResponseMessage resp, string name, string expected)
        {
            IEnumerable<string> values;
            if (!resp.Headers.TryGetValues(name, out values) || values == null) return false;
            return values.Any(v => string.Equals(v, expected, StringComparison.OrdinalIgnoreCase));
        }

        private static ApiException BuildError(HttpStatusCode status, string text)
        {
            string code = string.Empty;
            string message = string.Empty;
            if (!string.IsNullOrWhiteSpace(text))
            {
                try
                {
                    var json = JObject.Parse(text);
                    code = (string)json["code"] ?? string.Empty;
                    message = (string)json["error"] ?? string.Empty;
                }
                catch (JsonException)
                {
                    message = text.Length > 200 ? text.Substring(0, 200) : text;
                }
            }
            if (string.IsNullOrEmpty(code)) code = "http_" + (int)status;
            if (string.IsNullOrEmpty(message)) message = "HTTP " + (int)status;

            Log.Warn("请求失败 " + (int)status + " " + code + " " + message);
            return new ApiException(status, code, message);
        }
    }

    /// <summary>统一 JSON 参数：服务端字段是 snake_case，这里只做只读映射。</summary>
    public static class JsonSettings
    {
        public static readonly JsonSerializerSettings Read = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Ignore,
            DateParseHandling = DateParseHandling.None,
            MissingMemberHandling = MissingMemberHandling.Ignore
        };

        public static readonly JsonSerializerSettings Write = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Ignore,
            DateParseHandling = DateParseHandling.None,
            Formatting = Formatting.None
        };
    }
}
