using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using OldChat.Core.Config;
using OldChat.Core.Logging;
using OldChat.Core.Net;
using OldChat.Core.Protocol;

namespace OldChat.Core.Services
{
    /// <summary>登录、续期、恢复登录态。持有当前用户的 access token 生命周期。</summary>
    public sealed class AuthService
    {
        private const string AppVersion = "0.1.0";

        private readonly ApiClient _api;
        private readonly AppConfig _config;

        public SelfUser CurrentUser { get; private set; }
        public string RefreshToken { get; private set; }
        public bool IsLoggedIn { get { return !string.IsNullOrEmpty(_api.AccessToken) && CurrentUser != null; } }
        public string Origin { get { return _api.Origin; } }

        public AuthService(ApiClient api, AppConfig config)
        {
            _api = api;
            _config = config;
            _api.OnAccessTokenRejected = RefreshAsync;
        }

        public async Task<SelfUser> LoginAsync(string identifier, string password)
        {
            if (string.IsNullOrWhiteSpace(identifier)) throw new ArgumentException("请输入账号", "identifier");
            if (string.IsNullOrEmpty(password)) throw new ArgumentException("请输入密码", "password");

            // 登录前先建会话，账号密码从第一个字节起就在密文里。
            await _api.EnsureHandshakeAsync().ConfigureAwait(false);

            var body = new JObject
            {
                ["identifier"] = identifier.Trim(),
                ["password"] = password,
                ["device_id"] = _config.DeviceId,
                ["device_name"] = Environment.MachineName,
                ["platform"] = "windows",
                ["app_version"] = AppVersion,
                // 空串表示不声明官方签名，服务端对空值放行（见 auth_handlers.go）。
                ["apk_signature"] = string.Empty
            };

            JToken resp = await _api.PostAsync("/v1/auth/login", body).ConfigureAwait(false);
            AuthResult result = resp.ToObject<AuthResult>(Newtonsoft.Json.JsonSerializer.Create(JsonSettings.Read));
            if (result == null || string.IsNullOrEmpty(result.AccessToken))
            {
                throw new ApiException(System.Net.HttpStatusCode.OK, "login_failed", "登录响应缺少 token");
            }

            ApplyAuth(result);
            Persist();
            Log.Info("登录成功：" + (CurrentUser == null ? "?" : CurrentUser.DisplayText));
            return CurrentUser;
        }

        /// <summary>用已保存的 refresh token 恢复登录态，失败返回 false。</summary>
        public async Task<bool> TryRestoreAsync()
        {
            SessionStore.SessionData saved = SessionStore.Load();
            if (saved == null || string.IsNullOrEmpty(saved.RefreshToken)) return false;
            if (!string.IsNullOrEmpty(saved.Origin) &&
                !string.Equals(saved.Origin.TrimEnd('/'), _api.Origin, StringComparison.OrdinalIgnoreCase))
            {
                Log.Info("保存的登录态属于其它服务器源，忽略");
                return false;
            }

            RefreshToken = saved.RefreshToken;
            _api.AccessToken = saved.AccessToken;

            try
            {
                await _api.EnsureHandshakeAsync().ConfigureAwait(false);
                bool ok = await RefreshAsync().ConfigureAwait(false);
                if (!ok) return false;
                await LoadMeAsync().ConfigureAwait(false);
                return CurrentUser != null;
            }
            catch (Exception ex)
            {
                Log.Warn("恢复登录态失败：" + ex.Message);
                return false;
            }
        }

        public async Task<bool> RefreshAsync()
        {
            if (string.IsNullOrEmpty(RefreshToken)) return false;
            try
            {
                await _api.EnsureHandshakeAsync().ConfigureAwait(false);
                var body = new JObject { ["refresh_token"] = RefreshToken };
                JToken resp = await _api.PostAsync("/v1/auth/refresh", body).ConfigureAwait(false);
                if (resp == null) return false;

                string access = (string)resp["access_token"];
                string refresh = (string)resp["refresh_token"];
                if (string.IsNullOrEmpty(access)) return false;

                _api.AccessToken = access;
                if (!string.IsNullOrEmpty(refresh)) RefreshToken = refresh;
                Persist();
                return true;
            }
            catch (ApiException ex)
            {
                Log.Warn("续期失败：" + ex.Code);
                return false;
            }
        }

        public async Task<SelfUser> LoadMeAsync()
        {
            JToken resp = await _api.GetAsync("/v1/me").ConfigureAwait(false);
            if (resp == null) return null;
            JToken userNode = resp["user"] ?? resp;
            CurrentUser = userNode.ToObject<SelfUser>(Newtonsoft.Json.JsonSerializer.Create(JsonSettings.Read));
            return CurrentUser;
        }

        public async Task LogoutAsync()
        {
            try
            {
                await _api.PostAsync("/v1/auth/logout", new JObject()).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                Log.Warn("登出请求失败：" + ex.Message);
            }
            CurrentUser = null;
            RefreshToken = null;
            _api.AccessToken = null;
            SessionStore.Clear();
        }

        private void ApplyAuth(AuthResult result)
        {
            _api.AccessToken = result.AccessToken;
            RefreshToken = result.RefreshToken;
            CurrentUser = result.User;
        }

        private void Persist()
        {
            SessionStore.Save(new SessionStore.SessionData
            {
                AccessToken = _api.AccessToken,
                RefreshToken = RefreshToken,
                Origin = _api.Origin,
                UserId = CurrentUser == null ? null : CurrentUser.Id
            });
        }
    }
}
