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
            Loaded += delegate { AccountBox.Focus(); };
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

            string origin = (OriginBox.Text ?? string.Empty).Trim();
            string account = (AccountBox.Text ?? string.Empty).Trim();
            string password = PasswordInput.Password;

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
                config.Save();

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
