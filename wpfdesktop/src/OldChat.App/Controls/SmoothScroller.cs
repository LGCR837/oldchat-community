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
        }

        /// <summary>滚轮每单位 delta 对应的位移，120 一格约 66px。</summary>
        public double StepPerNotch { get; set; }

        /// <summary>60fps 下每帧向目标逼近的比例，0.3 约 200ms 到位。</summary>
        public double Ease { get; set; }

        public bool IsAnimating { get { return _running; } }

        /// <summary>内容高度变化或被代码强行滚动后，让目标跟上真实位置。</summary>
        public void Sync()
        {
            _target = _view.VerticalOffset;
        }

        private void OnScrollChanged(object sender, ScrollChangedEventArgs e)
        {
            if (!_running) _target = _view.VerticalOffset;
        }

        private void OnMouseDown(object sender, MouseButtonEventArgs e)
        {
            // 拖滚动条 / 点内容时中断缓动，免得两边抢位置
            if (_running) Stop();
        }

        private void OnWheel(object sender, MouseWheelEventArgs e)
        {
            if (e.Handled) return;
            if (_view.ScrollableHeight <= 0) return;
            e.Handled = true;

            if (!_running) _target = _view.VerticalOffset;
            _target -= e.Delta * StepPerNotch;
            Clamp();

            if (_running) return;
            _running = true;
            _lastTick = _clock.ElapsedTicks;
            CompositionTarget.Rendering += OnTick;
        }

        private void Clamp()
        {
            double max = Math.Max(0, _view.ScrollableHeight);
            if (_target < 0) _target = 0;
            if (_target > max) _target = max;
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

            double offset = _view.VerticalOffset;
            double diff = _target - offset;

            if (Math.Abs(diff) < 0.5)
            {
                _view.ScrollToVerticalOffset(_target);
                Stop();
                return;
            }

            _view.ScrollToVerticalOffset(offset + diff * factor);
        }

        public void Stop()
        {
            if (!_running) return;
            _running = false;
            CompositionTarget.Rendering -= OnTick;
        }

        public void JumpToEnd()
        {
            Stop();
            _view.ScrollToEnd();
            _target = _view.VerticalOffset;
        }
    }
}
