using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using OldChat.App.Controls;
using OldChat.Core.Config;
using OldChat.Core.Logging;
using OldChat.Core.Net;

namespace OldChat.App.Views
{
    public partial class LoginWindow : Window
    {
        private bool _busy;

        public LoginWindow()
        {
            InitializeComponent();
            ChromeHelper.Attach(this, 36);
            OriginBox.Text = App.Config != null ? App.Config.Origin : AppConfig.DefaultOrigin;
            OriginBox.LostFocus += delegate { RememberOrigin(); };
            Loaded += delegate
            {
                AccountBox.Focus();
                // 自检专用：环境变量给出账号时自动登录，且绝不动配置文件里的服务器地址
                string account = Environment.GetEnvironmentVariable("OLDCHAT_AUTOLOGIN_ACCOUNT");
                if (string.IsNullOrEmpty(account)) return;
                if (App.Config != null) App.Config.EphemeralOrigin = true;
                string envOrigin = Environment.GetEnvironmentVariable("OLDCHAT_AUTOLOGIN_ORIGIN");
                if (!string.IsNullOrEmpty(envOrigin)) OriginBox.Text = AppConfig.NormalizeOrigin(envOrigin);
                AccountBox.Text = account;
                PasswordInput.Password = Environment.GetEnvironmentVariable("OLDCHAT_AUTOLOGIN_PASSWORD") ?? string.Empty;
                Login_Click(null, null);
            };
        }

        /// <summary>手填/改过的服务器立刻落盘，下次打开就是这个地址。</summary>
        private void RememberOrigin()
        {
            AppConfig config = App.Config;
            if (config == null || config.EphemeralOrigin) return;

            string origin = AppConfig.NormalizeOrigin(OriginBox.Text);
            if (string.Equals(origin, config.Origin, StringComparison.OrdinalIgnoreCase)) return;

            config.Origin = origin;
            config.Save();
        }

        private void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ButtonState == MouseButtonState.Pressed) ChromeHelper.DragWindow(this);
        }

        private void Close_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void Password_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) Login_Click(sender, e);
        }

        private async void Login_Click(object sender, RoutedEventArgs e)
        {
            if (_busy) return;

            string origin = AppConfig.NormalizeOrigin(OriginBox.Text);
            string account = (AccountBox.Text ?? string.Empty).Trim();
            string password = PasswordInput.Password;

            OriginBox.Text = origin;

            if (string.IsNullOrEmpty(origin))
            {
                ShowError("请填写服务器地址");
                return;
            }
            if (!origin.StartsWith("http", StringComparison.OrdinalIgnoreCase))
            {
                ShowError("服务器地址要以 http:// 或 https:// 开头");
                return;
            }
            if (string.IsNullOrEmpty(account) || string.IsNullOrEmpty(password))
            {
                ShowError("请填写账号和密码");
                return;
            }

            SetBusy(true, "正在建立加密会话并登录…");
            HideError();

            try
            {
                AppConfig config = App.Config ?? AppConfig.Load();
                config.Origin = origin;
                // 临时指定的服务器只在本次进程内生效，不覆盖已经记住的那个
                if (!config.EphemeralOrigin) config.Save();

                AppSession session = AppSession.Create(config);
                await session.LoginAsync(account, password);

                App.SetSession(session);
                App.SetConfig(config);

                var main = new MainWindow();
                Application.Current.MainWindow = main;
                main.Show();
                Close();
                Log.Info("登录完成，进入主界面");
            }
            catch (ApiException ex)
            {
                ShowError(ex.FriendlyMessage);
                SetBusy(false, string.Empty);
                PasswordInput.SelectAll();
                PasswordInput.Focus();
            }
            catch (Exception ex)
            {
                Log.Error("登录异常", ex);
                ShowError("连接失败：" + ex.Message);
                SetBusy(false, string.Empty);
            }
        }

        private void SetBusy(bool busy, string status)
        {
            _busy = busy;
            LoginButton.IsEnabled = !busy;
            LoginButton.Content = busy ? "登录中…" : "登录";
            StatusText.Text = status ?? string.Empty;
        }

        private void ShowError(string message)
        {
            ErrorText.Text = message;
            ErrorText.Visibility = Visibility.Visible;
        }

        private void HideError()
        {
            ErrorText.Visibility = Visibility.Collapsed;
        }
    }
}
