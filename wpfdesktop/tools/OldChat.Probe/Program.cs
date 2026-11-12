using System;
using System.Collections.Generic;
using System.Text;
using System.Threading.Tasks;
using OldChat.Core.Config;
using OldChat.Core.Logging;
using OldChat.Core.Net;
using OldChat.Core.Protocol;
using OldChat.Core.Services;

namespace OldChat.Probe
{
    /// <summary>
    /// 协议自检工具。界面出问题之前先在这里把协议跑通，避免靠点界面调试。
    /// 用法：
    ///   OldChat.Probe login &lt;账号&gt; &lt;密码&gt; [origin]
    ///   OldChat.Probe ws &lt;账号&gt; &lt;密码&gt; [origin]
    /// </summary>
    internal static class Program
    {
        private static int Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            string command = args.Length > 0 ? args[0].ToLowerInvariant() : string.Empty;
            string account = args.Length > 1 ? args[1] : null;
            string password = args.Length > 2 ? args[2] : null;
            string origin = args.Length > 3 ? args[3] : AppConfig.DefaultOrigin;

            if (command == "handshake" ? (args.Length < 1) : (args.Length < 3))
            {
                Console.WriteLine("用法: OldChat.Probe <login|ws|handshake> <账号> <密码> [origin]");
                return 2;
            }

            Log.Init(System.IO.Path.Combine(AppConfig.DataDir, "logs"), LogLevel.Debug);
            try
            {
                switch (command)
                {
                    case "handshake":
                        return RunHandshake(origin).GetAwaiter().GetResult();
                    case "login":
                        return RunLogin(origin, account, password, false).GetAwaiter().GetResult();
                    case "ws":
                        return RunLogin(origin, account, password, true).GetAwaiter().GetResult();
                    default:
                        Console.WriteLine("未知命令: " + command);
                        return 2;
                }
            }
            catch (ApiException ex)
            {
                Console.WriteLine("接口失败 [" + (int)ex.StatusCode + " " + ex.Code + "] " + ex.FriendlyMessage);
                return 1;
            }
            catch (Exception ex)
            {
                Console.WriteLine("异常: " + ex.GetType().Name + " " + ex.Message);
                Console.WriteLine(ex.StackTrace);
                return 1;
            }
        }

        private static async Task<int> RunHandshake(string origin)
        {
            using (var api = new ApiClient(origin))
            {
                await api.EnsureHandshakeAsync().ConfigureAwait(false);
                Console.WriteLine("握手成功 origin=" + origin + " session=" + api.Channel.SessionId);
                Console.WriteLine("加密回环自检: " + (SelfCheck(api) ? "通过" : "失败"));
                return 0;
            }
        }

        private static bool SelfCheck(ApiClient api)
        {
            const string sample = "{\"hello\":\"世界\"}";
            string sealedText = api.Channel.SealText(sample);
            string plain = Encoding.UTF8.GetString(api.Channel.Unseal(sealedText));
            return plain == sample;
        }

        private static async Task<int> RunLogin(string origin, string account, string password, bool subscribe)
        {
            var config = new AppConfig { Origin = origin, DeviceId = "probe-" + Guid.NewGuid().ToString("N"), Theme = "dark" };
            using (var api = new ApiClient(origin))
            {
                var auth = new AuthService(api, config);
                SelfUser user = await auth.LoginAsync(account, password).ConfigureAwait(false);
                Console.WriteLine("登录成功: " + user.DisplayText + " uid=" + user.Uid + " ncuid=" + user.Ncuid);
                Console.WriteLine("加密会话: " + (api.Channel != null ? api.Channel.SessionId : "无"));

                SelfUser me = await auth.LoadMeAsync().ConfigureAwait(false);
                Console.WriteLine("/v1/me: " + (me == null ? "空" : me.DisplayText + " 信誉 " + me.ReputationScore));

                var chat = new ChatService(api);
                List<FriendItem> friends = await chat.GetFriendsAsync().ConfigureAwait(false);
                Console.WriteLine("/v1/friends: " + friends.Count + " 个");
                List<GroupItem> groups = await chat.GetGroupsAsync().ConfigureAwait(false);
                Console.WriteLine("/v1/groups/list: " + groups.Count + " 个");

                if (groups.Count > 0)
                {
                    GroupItem first = groups[0];
                    MessagePage page = await chat.LoadGroupHistoryAsync(first.GroupId, 0).ConfigureAwait(false);
                    Console.WriteLine("群 " + first.Name + " (" + first.GroupId + ") 历史: " + page.Messages.Count
                                      + " 条 has_more=" + page.HasMore + " next_seq=" + page.NextGroupSeq);
                    if (page.Messages.Count > 0)
                    {
                        ChatMessage last = page.Messages[page.Messages.Count - 1];
                        MessageBody body = MessageBody.Parse(last.Body);
                        Console.WriteLine("  末条 " + last.FromName + " [" + last.MsgType + "] " + Preview(body.Text));
                    }
                }

                if (!subscribe) return 0;

                var ws = new WsClient(origin, delegate { return api.AccessToken; });
                ws.ConnectionChanged += delegate (object s, bool connected)
                {
                    Console.WriteLine("[WS] " + (connected ? "已连接" : "已断开"));
                };
                ws.MessageReceived += delegate (object s, WsEventArgs e)
                {
                    Console.WriteLine("[WS] " + e.Type + " " + Shorten(e.Data == null ? "" : e.Data.ToString()));
                };
                ws.Start();

                Console.WriteLine("开始监听 60 秒，可用另一台设备发消息验证实时推送…");
                await Task.Delay(TimeSpan.FromSeconds(60)).ConfigureAwait(false);
                await ws.StopAsync().ConfigureAwait(false);
                ws.Dispose();
                return 0;
            }
        }

        private static string Preview(string text)
        {
            if (string.IsNullOrEmpty(text)) return "(空)";
            return Shorten(text.Replace("\n", " "));
        }

        private static string Shorten(string text)
        {
            if (text == null) return string.Empty;
            return text.Length <= 80 ? text : text.Substring(0, 80) + "…";
        }
    }
}
