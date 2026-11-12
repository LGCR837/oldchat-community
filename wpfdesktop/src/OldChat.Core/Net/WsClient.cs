using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using OldChat.Core.Logging;

namespace OldChat.Core.Net
{
    public sealed class WsEventArgs : EventArgs
    {
        public string Type { get; private set; }
        public JToken Data { get; private set; }

        public WsEventArgs(string type, JToken data)
        {
            Type = type;
            Data = data;
        }
    }

    /// <summary>
    /// /v1/ws 长连接。握手只需 Bearer，不需要客户端发 auth 帧；
    /// 服务端约 54 秒一次 Ping，客户端必须让底层自动回 Pong，否则会被清连接。
    /// </summary>
    public sealed class WsClient : IDisposable
    {
        private readonly Func<string> _tokenProvider;
        private readonly string _origin;
        private readonly object _gate = new object();

        private ClientWebSocket _socket;
        private CancellationTokenSource _cts;
        private Task _loopTask;
        private volatile bool _stopping;

        public event EventHandler<WsEventArgs> MessageReceived;
        public event EventHandler<bool> ConnectionChanged;

        public bool IsConnected
        {
            get
            {
                ClientWebSocket socket = _socket;
                return socket != null && socket.State == WebSocketState.Open;
            }
        }

        public WsClient(string origin, Func<string> tokenProvider)
        {
            _origin = origin.TrimEnd('/');
            _tokenProvider = tokenProvider;
        }

        public void Start()
        {
            lock (_gate)
            {
                if (_loopTask != null) return;
                _stopping = false;
                _cts = new CancellationTokenSource();
                _loopTask = Task.Run(() => LoopAsync(_cts.Token));
            }
        }

        public async Task StopAsync()
        {
            Task loop;
            lock (_gate)
            {
                _stopping = true;
                if (_cts != null) _cts.Cancel();
                loop = _loopTask;
                _loopTask = null;
            }

            ClientWebSocket socket = _socket;
            if (socket != null)
            {
                try
                {
                    if (socket.State == WebSocketState.Open)
                    {
                        await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None).ConfigureAwait(false);
                    }
                }
                catch { }
            }

            if (loop != null)
            {
                try { await Task.WhenAny(loop, Task.Delay(2000)).ConfigureAwait(false); } catch { }
            }
        }

        public void Dispose()
        {
            try { StopAsync().Wait(1500); } catch { }
            if (_cts != null) _cts.Dispose();
        }

        private async Task LoopAsync(CancellationToken token)
        {
            int attempt = 0;
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await ConnectAndReceiveAsync(token).ConfigureAwait(false);
                    attempt = 0;
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (Exception ex)
                {
                    if (!_stopping) Log.Warn("WS 断开：" + ex.GetType().Name + " " + ex.Message);
                }

                RaiseConnection(false);
                if (token.IsCancellationRequested) break;

                attempt++;
                int delay = Math.Min(30, (int)Math.Pow(2, Math.Min(attempt, 5)));
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(delay), token).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }

        private async Task ConnectAndReceiveAsync(CancellationToken token)
        {
            string token_ = _tokenProvider == null ? null : _tokenProvider();
            if (string.IsNullOrEmpty(token_)) throw new InvalidOperationException("缺少 access token");

            string scheme = _origin.StartsWith("https", StringComparison.OrdinalIgnoreCase) ? "wss://" : "ws://";
            string hostPart = _origin.Substring(_origin.IndexOf("://", StringComparison.Ordinal) + 3);

            var socket = new ClientWebSocket();
            socket.Options.SetRequestHeader("Authorization", "Bearer " + token_);
            socket.Options.KeepAliveInterval = TimeSpan.FromSeconds(25);

            _socket = socket;
            using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(token))
            {
                timeout.CancelAfter(TimeSpan.FromSeconds(15));
                await socket.ConnectAsync(new Uri(scheme + hostPart + "/v1/ws"), timeout.Token).ConfigureAwait(false);
            }

            Log.Info("WS 已连接");
            RaiseConnection(true);
            await ReceiveLoopAsync(socket, token).ConfigureAwait(false);
        }

        private async Task ReceiveLoopAsync(ClientWebSocket socket, CancellationToken token)
        {
            var buffer = new byte[16 * 1024];
            using (var message = new MemoryStream())
            {
                while (!token.IsCancellationRequested && socket.State == WebSocketState.Open)
                {
                    WebSocketReceiveResult result;
                    try
                    {
                        result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), token).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        return;
                    }

                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        Log.Info("WS 服务端关闭连接：" + result.CloseStatus);
                        return;
                    }

                    message.Write(buffer, 0, result.Count);
                    if (!result.EndOfMessage) continue;

                    string text = Encoding.UTF8.GetString(message.ToArray());
                    message.SetLength(0);
                    if (!string.IsNullOrWhiteSpace(text)) Dispatch(text);
                }
            }
        }

        private void Dispatch(string text)
        {
            string type;
            JToken data;
            try
            {
                var json = JObject.Parse(text);
                type = (string)json["type"];
                data = json["data"];
            }
            catch (Exception ex)
            {
                Log.Warn("WS 帧解析失败：" + ex.Message);
                return;
            }

            if (string.IsNullOrEmpty(type)) return;

            var handler = MessageReceived;
            if (handler == null) return;
            try
            {
                handler(this, new WsEventArgs(type, data));
            }
            catch (Exception ex)
            {
                Log.Error("WS 帧处理异常 type=" + type, ex);
            }
        }

        private void RaiseConnection(bool connected)
        {
            var handler = ConnectionChanged;
            if (handler == null) return;
            try
            {
                handler(this, connected);
            }
            catch (Exception ex)
            {
                Log.Error("WS 状态回调异常", ex);
            }
        }
    }
}
