using System;
using System.Threading.Tasks;
using OldChat.Core.Config;
using OldChat.Core.Net;
using OldChat.Core.Protocol;
using OldChat.Core.Services;

namespace OldChat.App
{
    /// <summary>运行时上下文：一份配置对应一套网络栈，切换服务器源就换一个实例。</summary>
    public sealed class AppSession : IDisposable
    {
        public AppConfig Config { get; private set; }
        public ApiClient Api { get; private set; }
        public AuthService Auth { get; private set; }
        public ChatService Chat { get; private set; }
        public WsClient Ws { get; private set; }

        public SelfUser Me { get { return Auth == null ? null : Auth.CurrentUser; } }
        public string MyUid { get { return Me == null ? null : Me.Uid; } }
        public string Origin { get { return Api == null ? null : Api.Origin; } }

        private AppSession(AppConfig config)
        {
            Config = config;
        }

        public static AppSession Create(AppConfig config)
        {
            var session = new AppSession(config);
            session.Api = new ApiClient(config.Origin);
            session.Api.DeviceId = config.DeviceId;
            session.Auth = new AuthService(session.Api, config);
            session.Chat = new ChatService(session.Api);
            return session;
        }

        public async Task<SelfUser> LoginAsync(string account, string password)
        {
            return await Auth.LoginAsync(account, password).ConfigureAwait(false);
        }

        public void StartRealtime()
        {
            if (Ws != null) return;
            Ws = new WsClient(Api.Origin, delegate { return Api.AccessToken; });
            Ws.Start();
        }

        public void StopRealtime()
        {
            if (Ws == null) return;
            Ws.Dispose();
            Ws = null;
        }

        public void Dispose()
        {
            StopRealtime();
            if (Api != null) Api.Dispose();
        }
    }
}
