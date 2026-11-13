using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
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

            window.SourceInitialized += delegate { HookMaximizeBounds(window); };
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

        // ---------- 最大化贴齐工作区 ----------

        private const int WM_GETMINMAXINFO = 0x0024;
        private const int MONITOR_DEFAULTTONEAREST = 2;

        [StructLayout(LayoutKind.Sequential)]
        private struct POINT
        {
            public int X;
            public int Y;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct RECT
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MINMAXINFO
        {
            public POINT ptReserved;
            public POINT ptMaxSize;
            public POINT ptMaxPosition;
            public POINT ptMinTrackSize;
            public POINT ptMaxTrackSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MONITORINFO
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork;
            public int dwFlags;
        }

        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromWindow(IntPtr hwnd, int flags);

        [DllImport("user32.dll")]
        private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO info);

        private static void HookMaximizeBounds(Window window)
        {
            IntPtr handle = new WindowInteropHelper(window).Handle;
            if (handle == IntPtr.Zero) return;
            HwndSource source = HwndSource.FromHwnd(handle);
            if (source != null) source.AddHook(MaximizeProc);
        }

        /// <summary>
        /// WindowStyle=None 时系统按显示器整屏最大化，会盖住任务栏并让四边溢出。
        /// 这里直接把最大化范围改成当前显示器的工作区。
        /// </summary>
        private static IntPtr MaximizeProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg != WM_GETMINMAXINFO) return IntPtr.Zero;

            IntPtr monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            if (monitor == IntPtr.Zero) return IntPtr.Zero;

            var info = new MONITORINFO();
            info.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
            if (!GetMonitorInfo(monitor, ref info)) return IntPtr.Zero;

            var mmi = (MINMAXINFO)Marshal.PtrToStructure(lParam, typeof(MINMAXINFO));
            int width = info.rcWork.Right - info.rcWork.Left;
            int height = info.rcWork.Bottom - info.rcWork.Top;

            mmi.ptMaxPosition.X = info.rcWork.Left - info.rcMonitor.Left;
            mmi.ptMaxPosition.Y = info.rcWork.Top - info.rcMonitor.Top;
            mmi.ptMaxSize.X = width;
            mmi.ptMaxSize.Y = height;
            mmi.ptMaxTrackSize.X = width;
            mmi.ptMaxTrackSize.Y = height;

            Marshal.StructureToPtr(mmi, lParam, true);
            handled = true;
            return IntPtr.Zero;
        }
    }
}
