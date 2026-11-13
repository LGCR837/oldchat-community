using System;
using System.ComponentModel;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
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
        private bool _noMoreHistory;
        private bool _olderArmed;
        private bool _initialized;
        private bool _userTouched;
        private SmoothScroller _msgScroll;
        private SmoothScroller _convScroll;
        private SmoothScroller _contactScroll;
        private ScrollViewer _msgView;

        public MainWindow()
        {
            InitializeComponent();
            ChromeHelper.Attach(this, 0);

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
            _vm.MessageAppended += delegate { FollowNewMessage(); };

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

            MessageScroll.ApplyTemplate();
            _msgView = MessageScroll;
            _msgScroll = new SmoothScroller(_msgView);
            _msgScroll.UserScrolled += OnMessageWheel;
            _convScroll = new SmoothScroller(ConversationScroll) { StepPerNotch = 0.42 };
            _contactScroll = new SmoothScroller(ContactScroll) { StepPerNotch = 0.5 };

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

        private static T FindChild<T>(DependencyObject root) where T : DependencyObject
        {
            if (root == null) return null;
            int count = VisualTreeHelper.GetChildrenCount(root);
            for (int i = 0; i < count; i++)
            {
                DependencyObject child = VisualTreeHelper.GetChild(root, i);
                var match = child as T;
                if (match != null) return match;
                T deeper = FindChild<T>(child);
                if (deeper != null) return deeper;
            }
            return null;
        }

        private void OnViewModelChanged(object sender, PropertyChangedEventArgs e)
        {
            if (e.PropertyName == "HasSelection") ShowPane();
            if (e.PropertyName == "Selected")
            {
                _noMoreHistory = false;
                _olderArmed = false;
                _userTouched = false;
                ScrollToBottom();
            }
        }

        private void ShowPane()
        {
            bool contacts = _activeTab == 1;
            bool hasChat = _activeTab == 0 && _vm.HasSelection;
            bool wasVisible = ChatPane.Visibility == Visibility.Visible;

            // 联系人是一整页，不保留会话侧边栏
            ListColumn.Width = contacts ? new GridLength(0) : new GridLength(300);
            SidePane.Visibility = contacts ? Visibility.Collapsed : Visibility.Visible;

            BrandPanel.Visibility = (contacts || !hasChat) ? Visibility.Visible : Visibility.Collapsed;
            ChatHeaderPanel.Visibility = hasChat ? Visibility.Visible : Visibility.Collapsed;
            ContactsPane.Visibility = contacts ? Visibility.Visible : Visibility.Collapsed;
            BrandTitle.Text = contacts ? "联系人" : "旧聊";

            if (hasChat)
            {
                ChatPane.Visibility = Visibility.Visible;
                EmptyPane.Visibility = Visibility.Collapsed;
                if (!wasVisible) FadeIn(ChatPane);
            }
            else
            {
                ChatPane.Visibility = Visibility.Collapsed;
                EmptyPane.Visibility = contacts ? Visibility.Collapsed : Visibility.Visible;
                if (!contacts && (wasVisible || EmptyPane.Opacity < 1)) FadeIn(EmptyPane);
            }

            if (!hasChat && !contacts)
            {
                switch (_activeTab)
                {
                    case 0:
                        EmptyTitle.Text = "选择一个会话";
                        EmptyHint.Text = "左侧列表里的群聊与好友都能直接打开";
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

                // 标题区平时只占一行，断线时才补第二行
                ChatConnectionText.Text = connected ? string.Empty : "断开连接，正在重连";
                ChatConnectionText.Visibility = connected ? Visibility.Collapsed : Visibility.Visible;
            }));
        }

        private void ScrollToBottom()
        {
            Dispatcher.BeginInvoke(new Action(delegate
            {
                if (_msgView == null) return;
                _msgView.UpdateLayout();
                JumpToEnd();

                // 消息还在陆续上屏时贴底会被忽略，等这一轮布局结束再确认一次。
                // 用户已经自己滚过就不要拽回去，那是抢操作。
                Dispatcher.BeginInvoke(new Action(delegate
                {
                    if (_msgView == null || _userTouched) return;
                    if (_msgView.ScrollableHeight - _msgView.VerticalOffset > 1) JumpToEnd();
                }), System.Windows.Threading.DispatcherPriority.ContextIdle);
            }), System.Windows.Threading.DispatcherPriority.Loaded);
        }

        private void JumpToEnd()
        {
            if (_msgScroll != null) _msgScroll.JumpToEnd();
            else _msgView.ScrollToEnd();
        }

        /// <summary>收到新消息时跟随到底，但用户正在往上翻历史就不打扰。</summary>
        private void FollowNewMessage()
        {
            Dispatcher.BeginInvoke(new Action(delegate
            {
                if (_msgView == null) return;

                double max = Math.Max(0, _msgView.ScrollableHeight);
                if (max - _msgView.VerticalOffset > 90) return;

                if (_msgScroll != null) _msgScroll.JumpToEnd();
                else _msgView.ScrollToEnd();
            }), System.Windows.Threading.DispatcherPriority.Background);
        }

        // ---------- 标题栏 ----------

        private void TopBar_MouseDown(object sender, MouseButtonEventArgs e)
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

        // ---------- 设置菜单 ----------

        private void Menu_Click(object sender, RoutedEventArgs e)
        {
            var button = sender as Button;
            if (button == null || button.ContextMenu == null) return;
            button.ContextMenu.PlacementTarget = button;
            button.ContextMenu.Placement = PlacementMode.Bottom;
            button.ContextMenu.IsOpen = true;
        }

        private void Menu_Opened(object sender, RoutedEventArgs e)
        {
            bool rounded = App.Config == null || App.Config.BubbleRounded;
            MenuRound.IsChecked = rounded;
            MenuSquare.IsChecked = !rounded;

            bool dark = Theme.ThemeManager.IsDark;
            MenuDark.IsChecked = dark;
            MenuLight.IsChecked = !dark;
        }

        private void MenuRound_Click(object sender, RoutedEventArgs e)
        {
            SetBubbleRounded(true);
        }

        private void MenuSquare_Click(object sender, RoutedEventArgs e)
        {
            SetBubbleRounded(false);
        }

        private void SetBubbleRounded(bool rounded)
        {
            if (App.Config == null) return;
            if (App.Config.BubbleRounded == rounded) return;
            App.Config.BubbleRounded = rounded;
            App.Config.Save();
            BubbleShape.Apply(rounded);
        }

        private void MenuLight_Click(object sender, RoutedEventArgs e)
        {
            SetTheme("light");
        }

        private void MenuDark_Click(object sender, RoutedEventArgs e)
        {
            SetTheme("dark");
        }

        private void SetTheme(string theme)
        {
            if (string.Equals(Theme.ThemeManager.Current, theme, StringComparison.OrdinalIgnoreCase)) return;
            Theme.ThemeManager.Apply(theme);
            App.SwitchTheme(theme);
            AvatarImage.RefreshTheme();
            InvalidateVisual();
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

        private void ConversationRow_Click(object sender, MouseButtonEventArgs e)
        {
            var row = sender as FrameworkElement;
            var conv = row == null ? null : row.DataContext as ConversationItem;
            if (conv == null) return;
            OpenConversation(conv);
        }

        private void ContactTile_Click(object sender, MouseButtonEventArgs e)
        {
            var tile = sender as FrameworkElement;
            var contact = tile == null ? null : tile.DataContext as ContactItem;
            if (contact == null) return;

            ConversationItem conv = _vm.FindConversation(contact.IsGroup, contact.Id);
            if (conv == null) return;
            OpenConversation(conv);
        }

        /// <summary>点列表或联系人宫格都走这里：选中会话并切回消息页。</summary>
        private void OpenConversation(ConversationItem conv)
        {
            _vm.SelectConversation(conv);
            if (_activeTab != 0)
            {
                _activeTab = 0;
                TabMessages.IsChecked = true;
            }
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

        /// <summary>只有用户自己往上拨滚轮才算“要翻历史”，其余情况一律不加载。</summary>
        private void OnMessageWheel(double pixels)
        {
            _userTouched = true;
            if (pixels < 0) _olderArmed = true;
            if (_olderArmed && _msgView != null && _msgView.VerticalOffset <= 2) TryLoadOlder();
        }

        private void MessageScroll_ScrollChanged(object sender, ScrollChangedEventArgs e)
        {
            ScrollViewer view = sender as ScrollViewer;
            if (view == null) return;

            // 内容变高（切会话、贴底、刚加载完一批）不算用户意图，不触发加载
            if (e.ExtentHeightChange != 0) return;
            if (!_olderArmed || view.VerticalOffset > 2) return;

            TryLoadOlder();
        }

        private async void TryLoadOlder()
        {
            ScrollViewer view = _msgView;
            if (view == null || _loadingOlder || _noMoreHistory) return;
            if (view.ScrollableHeight <= 0) return;

            _loadingOlder = true;
            _olderArmed = false;
            double before = view.ExtentHeight;
            double offset = view.VerticalOffset;
            int countBefore = _vm.Messages.Count;
            try
            {
                await _vm.LoadOlderAsync();

                if (_vm.Messages.Count == countBefore)
                {
                    _noMoreHistory = true;
                    return;
                }

                // 等这一轮布局跑完再量高度，否则补的是上一次的尺寸
                await System.Windows.Threading.Dispatcher.Yield(System.Windows.Threading.DispatcherPriority.Loaded);

                double after = view.ExtentHeight;
                double restored = offset + (after - before);
                view.ScrollToVerticalOffset(restored);

                // 图片和头像晚一拍才撑开高度，再对齐一次，避免落点偏一小段看着像回弹
                await System.Windows.Threading.Dispatcher.Yield(System.Windows.Threading.DispatcherPriority.Background);
                double settled = view.ExtentHeight;
                if (Math.Abs(settled - after) > 0.5)
                {
                    view.ScrollToVerticalOffset(restored + (settled - after));
                }
            }
            catch (Exception ex)
            {
                Log.Warn("加载更早消息失败：" + ex.Message);
            }
            finally
            {
                _loadingOlder = false;
            }
        }
    }
}
