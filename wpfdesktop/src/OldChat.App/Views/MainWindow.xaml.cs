using System;
using System.ComponentModel;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OldChat.App.Controls;
using OldChat.App.ViewModels;
using OldChat.Core.Logging;

namespace OldChat.App.Views
{
    public partial class MainWindow : Window
    {
        private readonly MainViewModel _vm;
        private int _activeTab;
        private bool _loadingOlder;
        private bool _initialized;

        public MainWindow()
        {
            InitializeComponent();
            ChromeHelper.Attach(this, 40);

            AppSession session = App.Session;
            if (session == null)
            {
                Close();
                return;
            }

            AvatarImage.Origin = session.Origin;
            _vm = new MainViewModel(session);
            DataContext = _vm;

            SelfAvatar.Url = _vm.MyAvatarUrl;
            SelfAvatar.FallbackText = _vm.MyDisplayName;
            SelfAvatar.ToolTip = _vm.MyDisplayName + "\n" + session.Origin;

            _vm.PropertyChanged += OnViewModelChanged;
            _vm.MessageAppended += delegate { ScrollToBottom(); };

            if (session.Ws != null)
            {
                session.Ws.ConnectionChanged += OnWsConnectionChanged;
                _vm.AttachRealtime();
            }

            Loaded += OnLoaded;
            Closing += OnClosing;
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            if (_initialized) return;
            _initialized = true;
            await _vm.InitializeAsync();
            ShowPane();
        }

        private void OnClosing(object sender, CancelEventArgs e)
        {
            try
            {
                _vm.DetachRealtime();
                if (App.Session != null && App.Session.Ws != null)
                {
                    App.Session.Ws.ConnectionChanged -= OnWsConnectionChanged;
                }
            }
            catch (Exception ex)
            {
                Log.Warn("关闭时清理失败：" + ex.Message);
            }
            Application.Current.Shutdown();
        }

        private void OnViewModelChanged(object sender, PropertyChangedEventArgs e)
        {
            if (e.PropertyName == "HasSelection") ShowPane();
            if (e.PropertyName == "Selected")
            {
                ScrollToBottom();
            }
        }

        private void ShowPane()
        {
            bool hasChat = _vm.HasSelection && _activeTab == 0;
            bool wasVisible = ChatPane.Visibility == Visibility.Visible;

            if (hasChat)
            {
                ChatPane.Visibility = Visibility.Visible;
                EmptyPane.Visibility = Visibility.Collapsed;
                if (!wasVisible) FadeIn(ChatPane);
            }
            else
            {
                ChatPane.Visibility = Visibility.Collapsed;
                EmptyPane.Visibility = Visibility.Visible;
                if (wasVisible || EmptyPane.Opacity < 1) FadeIn(EmptyPane);
            }

            if (!hasChat)
            {
                switch (_activeTab)
                {
                    case 0:
                        EmptyTitle.Text = "选择一个会话";
                        EmptyHint.Text = "左侧列表里的群聊与好友都能直接打开";
                        break;
                    case 1:
                        EmptyTitle.Text = "好友";
                        EmptyHint.Text = "好友都在左侧列表里，点一下就能聊";
                        break;
                    default:
                        EmptyTitle.Text = "发现";
                        EmptyHint.Text = "频道与小程序还没做，先把聊天踩实";
                        break;
                }
            }
        }

        /// <summary>面板切换：120ms 淡入，与消息入场同一节奏。</summary>
        private static void FadeIn(UIElement element)
        {
            var animation = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(120))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            element.BeginAnimation(OpacityProperty, animation);
        }

        private void OnWsConnectionChanged(object sender, bool connected)
        {
            Dispatcher.BeginInvoke(new Action(delegate
            {
                ConnectionText.Text = connected ? "已连接" : "重连中";
                ConnectionText.Foreground = connected
                    ? (Brush)FindResource("TextMuted")
                    : (Brush)FindResource("DangerBrush");
                StatusDot.Fill = connected
                    ? (Brush)FindResource("OnlineBrush")
                    : (Brush)FindResource("DangerBrush");
            }));
        }

        private void ScrollToBottom()
        {
            Dispatcher.BeginInvoke(new Action(delegate
            {
                MessageScroll.ScrollToEnd();
            }), System.Windows.Threading.DispatcherPriority.Background);
        }

        // ---------- 标题栏 ----------

        private void TitleBar_MouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount == 2)
            {
                ChromeHelper.ToggleMaximize(this);
                return;
            }
            if (e.ButtonState == MouseButtonState.Pressed) ChromeHelper.DragWindow(this);
        }

        private void Minimize_Click(object sender, RoutedEventArgs e)
        {
            WindowState = WindowState.Minimized;
        }

        private void Maximize_Click(object sender, RoutedEventArgs e)
        {
            ChromeHelper.ToggleMaximize(this);
        }

        private void Close_Click(object sender, RoutedEventArgs e)
        {
            Close();
        }

        private void Theme_Click(object sender, RoutedEventArgs e)
        {
            OldChat.App.Theme.ThemeManager.Toggle();
            App.SwitchTheme(OldChat.App.Theme.ThemeManager.Current);
            InvalidateVisual();
        }

        private void BubbleMode_Click(object sender, RoutedEventArgs e)
        {
            if (App.Config == null) return;
            App.Config.BubbleRounded = !App.Config.BubbleRounded;
            App.Config.Save();
            Controls.BubbleShape.Apply(App.Config.BubbleRounded);
        }

        // ---------- 导航 ----------

        private void TabMessages_Click(object sender, RoutedEventArgs e)
        {
            _activeTab = 0;
            ShowPane();
        }

        private void TabFriends_Click(object sender, RoutedEventArgs e)
        {
            _activeTab = 1;
            ShowPane();
        }

        private void TabDiscover_Click(object sender, RoutedEventArgs e)
        {
            _activeTab = 2;
            ShowPane();
        }

        private void SelfAvatar_Click(object sender, MouseButtonEventArgs e)
        {
            AppSession session = App.Session;
            if (session == null || session.Me == null) return;
            MessageBox.Show(
                session.Me.DisplayText + "\nUID " + session.Me.Uid + "\n" + session.Origin,
                "账号", MessageBoxButton.OK, MessageBoxImage.Information);
        }

        // ---------- 输入 ----------

        private void Composer_PreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Shift) == 0)
            {
                e.Handled = true;
                var ignored = SendAsync();
            }
        }

        private void Send_Click(object sender, RoutedEventArgs e)
        {
            var ignored = SendAsync();
        }

        private async Task SendAsync()
        {
            if (string.IsNullOrWhiteSpace(_vm.ComposerText)) return;
            SendButton.IsEnabled = false;
            try
            {
                await _vm.SendAsync();
            }
            finally
            {
                SendButton.IsEnabled = true;
                Composer.Focus();
            }
        }

        // ---------- 滚动加载 ----------

        private async void MessageScroll_ScrollChanged(object sender, ScrollChangedEventArgs e)
        {
            if (e.ExtentHeightChange == 0) return;
            if (MessageScroll.VerticalOffset > 4) return;
            if (_loadingOlder || e.ExtentHeight < MessageScroll.ViewportHeight) return;

            _loadingOlder = true;
            double before = MessageScroll.ExtentHeight;
            double offset = MessageScroll.VerticalOffset;
            try
            {
                await _vm.LoadOlderAsync();
                double after = MessageScroll.ExtentHeight;
                if (after > before)
                {
                    MessageScroll.ScrollToVerticalOffset(offset + (after - before));
                }
            }
            finally
            {
                _loadingOlder = false;
            }
        }
    }
}
