using System;
using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace OldChat.App.Controls
{
    /// <summary>圆形头像：有图显示图，无图显示名字首字，右下角可挂在线点。</summary>
    public sealed class AvatarImage : FrameworkElement
    {
        public static readonly DependencyProperty UrlProperty = DependencyProperty.Register(
            "Url", typeof(string), typeof(AvatarImage),
            new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.AffectsRender, OnUrlChanged));

        public static readonly DependencyProperty FallbackTextProperty = DependencyProperty.Register(
            "FallbackText", typeof(string), typeof(AvatarImage),
            new FrameworkPropertyMetadata("?", FrameworkPropertyMetadataOptions.AffectsRender));

        public static readonly DependencyProperty IsOnlineProperty = DependencyProperty.Register(
            "IsOnline", typeof(bool), typeof(AvatarImage),
            new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

        public static readonly DependencyProperty ShowOnlineProperty = DependencyProperty.Register(
            "ShowOnline", typeof(bool), typeof(AvatarImage),
            new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

        private ImageSource _image;
        private string _loadedUrl;

        /// <summary>相对头像路径统一补成绝对地址，界面层就不用到处拼 origin。</summary>
        public static string Origin { get; set; }

        public static string Resolve(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return url;
            string trimmed = url.Trim();
            if (trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return trimmed;
            if (string.IsNullOrEmpty(Origin)) return trimmed;
            if (!trimmed.StartsWith("/")) trimmed = "/" + trimmed;
            return Origin.TrimEnd('/') + trimmed;
        }

        public string Url
        {
            get { return (string)GetValue(UrlProperty); }
            set { SetValue(UrlProperty, value); }
        }

        public string FallbackText
        {
            get { return (string)GetValue(FallbackTextProperty); }
            set { SetValue(FallbackTextProperty, value); }
        }

        public bool IsOnline
        {
            get { return (bool)GetValue(IsOnlineProperty); }
            set { SetValue(IsOnlineProperty, value); }
        }

        public bool ShowOnline
        {
            get { return (bool)GetValue(ShowOnlineProperty); }
            set { SetValue(ShowOnlineProperty, value); }
        }

        private static void OnUrlChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            var avatar = d as AvatarImage;
            if (avatar == null) return;
            avatar._image = null;
            avatar._loadedUrl = null;
            avatar.Reload();
        }

        private void Reload()
        {
            string url = Resolve(Url);
            if (string.IsNullOrEmpty(url)) return;

            double size = Math.Max(ActualWidth, ActualHeight);
            if (size <= 0) size = 40;
            double decode = Math.Max(64, size * 2);

            AvatarImage self = this;
            string requested = url;
            ImageSource source = ImageCache.Get(requested, decode, delegate (ImageSource loaded)
            {
                if (self._loadedUrl == requested && loaded != null)
                {
                    self._image = loaded;
                    self.InvalidateVisual();
                }
            });

            if (source != null)
            {
                _image = source;
                _loadedUrl = url;
            }
        }

        protected override void OnRenderSizeChanged(SizeChangedInfo info)
        {
            base.OnRenderSizeChanged(info);
            if (_image == null) Reload();
            InvalidateVisual();
        }

        protected override void OnRender(DrawingContext dc)
        {
            double size = Math.Min(ActualWidth, ActualHeight);
            if (size <= 0) return;

            var center = new Point(ActualWidth / 2, ActualHeight / 2);
            double radius = size / 2;
            var clip = new EllipseGeometry(center, radius, radius);
            dc.PushClip(clip);

            Brush background = ResolveBrush("AvatarPlaceholderBg", Color.FromRgb(0x23, 0x2A, 0x31));
            dc.DrawEllipse(background, null, center, radius, radius);

            if (_image != null)
            {
                dc.DrawImage(_image, new Rect(0, 0, ActualWidth, ActualHeight));
            }
            else
            {
                string letter = string.IsNullOrEmpty(FallbackText) ? "?" : FallbackText.Substring(0, 1).ToUpperInvariant();
                FormattedText text = MakeText(letter, radius);
                dc.DrawText(text, new Point(center.X - text.Width / 2, center.Y - text.Height / 2));
            }

            dc.Pop();

            if (ShowOnline && IsOnline && size >= 24)
            {
                double dot = Math.Max(8, size * 0.24);
                var dotCenter = new Point(ActualWidth - dot / 2 - size * 0.02, ActualHeight - dot / 2 - size * 0.02);
                Brush online = ResolveBrush("OnlineBrush", Color.FromRgb(0x3F, 0xBF, 0x7F));
                Brush windowBg = ResolveBrush("PanelBg", Color.FromRgb(0x14, 0x17, 0x1A));
                dc.DrawEllipse(windowBg, null, dotCenter, dot / 2 + 1.5, dot / 2 + 1.5);
                dc.DrawEllipse(online, null, dotCenter, dot / 2, dot / 2);
            }
        }

        private FormattedText MakeText(string letter, double radius)
        {
            Brush foreground = ResolveBrush("TextSecondary", Color.FromRgb(0x9B, 0xA3, 0xAB));
            double pixelsPerDip = VisualTreeHelper.GetDpi(this).PixelsPerDip;
            var text = new FormattedText(
                letter,
                CultureInfo.CurrentUICulture,
                FlowDirection.LeftToRight,
                new Typeface(new FontFamily("Microsoft YaHei UI, Segoe UI"), FontStyles.Normal, FontWeights.Medium, FontStretches.Normal),
                Math.Max(12, radius * 0.85),
                foreground,
                pixelsPerDip);
            return text;
        }

        private Brush ResolveBrush(string key, Color fallback)
        {
            object found = TryFindResource(key);
            var brush = found as Brush;
            return brush ?? new SolidColorBrush(fallback);
        }
    }
}
