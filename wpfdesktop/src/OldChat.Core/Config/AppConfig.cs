using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using Newtonsoft.Json;
using OldChat.Core.Logging;

namespace OldChat.Core.Config
{
    /// <summary>本地设置：服务器源、设备标识、界面偏好。存放在 %APPDATA%\OldChat。</summary>
    public sealed class AppConfig
    {
        public const string DefaultOrigin = "http://octest.reverie.dpdns.org";

        [JsonProperty("origin")] public string Origin { get; set; }
        [JsonProperty("device_id")] public string DeviceId { get; set; }
        [JsonProperty("theme")] public string Theme { get; set; }
        [JsonProperty("last_chat")] public string LastChat { get; set; }

        public static string DataDir
        {
            get
            {
                string dir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "OldChat");
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        private static string ConfigPath
        {
            get { return Path.Combine(DataDir, "config.json"); }
        }

        public static AppConfig Load()
        {
            AppConfig cfg = null;
            try
            {
                if (File.Exists(ConfigPath))
                {
                    cfg = JsonConvert.DeserializeObject<AppConfig>(File.ReadAllText(ConfigPath, Encoding.UTF8));
                }
            }
            catch (Exception ex)
            {
                Log.Error("读取配置失败，使用默认值", ex);
            }

            if (cfg == null) cfg = new AppConfig();
            if (string.IsNullOrWhiteSpace(cfg.Origin)) cfg.Origin = DefaultOrigin;
            if (string.IsNullOrWhiteSpace(cfg.DeviceId)) cfg.DeviceId = "win-" + Guid.NewGuid().ToString("N");
            if (string.IsNullOrWhiteSpace(cfg.Theme)) cfg.Theme = "dark";
            return cfg;
        }

        public void Save()
        {
            try
            {
                File.WriteAllText(ConfigPath, JsonConvert.SerializeObject(this, Formatting.Indented), Encoding.UTF8);
            }
            catch (Exception ex)
            {
                Log.Error("保存配置失败", ex);
            }
        }
    }

    /// <summary>
    /// 登录态落盘。用 DPAPI 按当前用户加密，别的账户或别的机器复制走都解不开。
    /// </summary>
    public sealed class SessionStore
    {
        private const string FileName = "session.dat";
        private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("oldchat.desktop.session");

        private static string SessionPath
        {
            get { return Path.Combine(AppConfig.DataDir, FileName); }
        }

        public sealed class SessionData
        {
            [JsonProperty("access_token")] public string AccessToken { get; set; }
            [JsonProperty("refresh_token")] public string RefreshToken { get; set; }
            [JsonProperty("origin")] public string Origin { get; set; }
            [JsonProperty("user_id")] public string UserId { get; set; }
        }

        public static SessionData Load()
        {
            try
            {
                if (!File.Exists(SessionPath)) return null;
                byte[] protectedBytes = File.ReadAllBytes(SessionPath);
                byte[] plain = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
                return JsonConvert.DeserializeObject<SessionData>(Encoding.UTF8.GetString(plain));
            }
            catch (Exception ex)
            {
                Log.Warn("登录态读取失败：" + ex.GetType().Name);
                return null;
            }
        }

        public static void Save(SessionData data)
        {
            try
            {
                byte[] plain = Encoding.UTF8.GetBytes(JsonConvert.SerializeObject(data));
                byte[] protectedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
                File.WriteAllBytes(SessionPath, protectedBytes);
            }
            catch (Exception ex)
            {
                Log.Error("登录态保存失败", ex);
            }
        }

        public static void Clear()
        {
            try
            {
                if (File.Exists(SessionPath)) File.Delete(SessionPath);
            }
            catch { }
        }
    }
}
