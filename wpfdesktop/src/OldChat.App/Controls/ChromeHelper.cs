using System;
using System.Windows;
using System.Windows.Shell;

namespace OldChat.App.Controls
{
    /// <summary>无边框窗口的统一处理：保留系统缩放与阴影，标题区可拖动。</summary>
    public static class ChromeHelper
    {
        public static void Attach(Window window, double captionHeight)
        {
            var chrome = new WindowChrome
            {
                CaptionHeight = captionHeight,
                ResizeBorderThickness = new Thickness(6),
                CornerRadius = new CornerRadius(0),
                GlassFrameThickness = new Thickness(0),
                UseAeroCaptionButtons = false
            };
            WindowChrome.SetWindowChrome(window, chrome);
        }

        public static void DragWindow(Window window)
        {
            if (window == null) return;
            if (window.WindowState == WindowState.Maximized) return;
            try
            {
                window.DragMove();
            }
            catch (InvalidOperationException)
            {
                // 鼠标已松开时会抛，忽略
            }
        }

        public static void ToggleMaximize(Window window)
        {
            if (window == null) return;
            window.WindowState = window.WindowState == WindowState.Maximized
                ? WindowState.Normal
                : WindowState.Maximized;
        }
    }
}
