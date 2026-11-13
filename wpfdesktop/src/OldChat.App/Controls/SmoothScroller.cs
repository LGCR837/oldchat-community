using System;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace OldChat.App.Controls
{
    /// <summary>滚轮逐帧缓动：把一格一跳换成约 200ms 收敛的连续位移。</summary>
    public sealed class SmoothScroller
    {
        private readonly ScrollViewer _view;
        private double _target;
        private bool _running;

        public SmoothScroller(ScrollViewer view)
        {
            if (view == null) throw new ArgumentNullException("view");
            _view = view;
            StepPerNotch = 0.55;
            Ease = 0.3;
            _view.PreviewMouseWheel += OnWheel;
            _view.ScrollChanged += OnScrollChanged;
        }

        /// <summary>滚轮每单位 delta 对应的位移，120 一格约 66px。</summary>
        public double StepPerNotch { get; set; }

        /// <summary>每帧向目标逼近的比例，0.3 约 200ms 到位。</summary>
        public double Ease { get; set; }

        public bool IsAnimating { get { return _running; } }

        private void OnScrollChanged(object sender, ScrollChangedEventArgs e)
        {
            if (!_running) _target = _view.VerticalOffset;
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
            double diff = _target - _view.VerticalOffset;
            if (Math.Abs(diff) < 0.6)
            {
                _view.ScrollToVerticalOffset(_target);
                Stop();
                return;
            }
            _view.ScrollToVerticalOffset(_view.VerticalOffset + diff * Ease);
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
