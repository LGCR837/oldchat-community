using System;
using System.Diagnostics;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace OldChat.App.Controls
{
    /// <summary>滚轮逐帧缓动：把一格一跳换成约 200ms 收敛的连续位移。</summary>
    public sealed class SmoothScroller
    {
        private readonly ScrollViewer _view;
        private readonly Stopwatch _clock = Stopwatch.StartNew();

        // 每帧都从 ScrollViewer 读回真实位置，不维护影子状态。
        // 影子状态会和外部改动（拖动、内容变化、系统滚动）打架，表现就是凭空多滚一下。
        private double _target;
        private bool _running;
        private long _lastTick;

        public SmoothScroller(ScrollViewer view)
        {
            if (view == null) throw new ArgumentNullException("view");
            _view = view;
            StepPerNotch = 0.55;
            Ease = 0.3;
            _view.PreviewMouseWheel += OnWheel;
            _view.ScrollChanged += OnScrollChanged;
            _view.PreviewMouseDown += OnMouseDown;
            _view.LostMouseCapture += delegate { Stop(); };
        }

        /// <summary>滚轮每单位 delta 对应的位移，120 一格约 66px。</summary>
        public double StepPerNotch { get; set; }

        /// <summary>60fps 下每帧向目标逼近的比例，0.3 约 200ms 到位。</summary>
        public double Ease { get; set; }

        public bool IsAnimating { get { return _running; } }

        /// <summary>用户自己拨滚轮时触发，参数是请求的像素位移（正数向下）。</summary>
        public event Action<double> UserScrolled;

        private void OnScrollChanged(object sender, ScrollChangedEventArgs e)
        {
            // 没在动的时候位置被别人改了（拖动、贴底、加载历史），直接认下来
            if (!_running) _target = _view.VerticalOffset;
        }

        private void OnMouseDown(object sender, MouseButtonEventArgs e)
        {
            if (_running) Stop();
        }

        private void OnWheel(object sender, MouseWheelEventArgs e)
        {
            if (e.Handled) return;
            e.Handled = true;
            ScrollBy(-e.Delta * StepPerNotch);

            Action<double> handler = UserScrolled;
            if (handler != null) handler(-e.Delta * StepPerNotch);
        }

        /// <summary>按像素位移滚动，正数向下。已经到头就不再起动画，免得来回弹。</summary>
        public void ScrollBy(double pixels)
        {
            double max = Math.Max(0, _view.ScrollableHeight);
            if (max <= 0) return;

            if (!_running) _target = _view.VerticalOffset;
            _target += pixels;
            if (_target < 0) _target = 0;
            if (_target > max) _target = max;

            if (!_running)
            {
                double current = _view.VerticalOffset;
                if (Math.Abs(_target - current) < 0.6)
                {
                    _target = current;
                    return;
                }
                _running = true;
                _lastTick = _clock.ElapsedTicks;
                CompositionTarget.Rendering += OnTick;
            }
        }

        private void OnTick(object sender, EventArgs e)
        {
            long now = _clock.ElapsedTicks;
            double dt = (now - _lastTick) / (double)Stopwatch.Frequency;
            _lastTick = now;

            // 帧间隔不稳时按实际 dt 补偿，60fps 以下不会变慢也不会顿
            if (dt <= 0) dt = 1.0 / 60.0;
            if (dt > 0.2) dt = 0.2;
            double factor = 1.0 - Math.Pow(1.0 - Ease, dt * 60.0);

            double current = _view.VerticalOffset;
            double max = Math.Max(0, _view.ScrollableHeight);
            if (_target > max) _target = max;

            double diff = _target - current;
            if (Math.Abs(diff) < 0.6)
            {
                _view.ScrollToVerticalOffset(_target);
                Stop();
                return;
            }

            _view.ScrollToVerticalOffset(current + diff * factor);
        }

        public void Stop()
        {
            if (!_running) return;
            _running = false;
            CompositionTarget.Rendering -= OnTick;
            _target = _view.VerticalOffset;
        }

        public void JumpToEnd()
        {
            Stop();
            _view.ScrollToEnd();
            _target = _view.VerticalOffset;
        }
    }
}
