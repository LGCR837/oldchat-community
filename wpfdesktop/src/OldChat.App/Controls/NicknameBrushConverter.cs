using System;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;

namespace OldChat.App.Controls
{
    /// <summary>
    /// 昵称取色：按名字哈希落到官方 8 色板之一，同名恒定。
    /// 色值从主题字典取，浅色与夜间各一套。
    /// </summary>
    public sealed class NicknameBrushConverter : IValueConverter
    {
        private static readonly string[] Keys =
        {
            "Nick1", "Nick2", "Nick3", "Nick4", "Nick5", "Nick6", "Nick7", "Nick8"
        };

        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            string name = value as string;
            if (string.IsNullOrEmpty(name)) return new SolidColorBrush(Color.FromRgb(0x6B, 0x72, 0x80));

            int hash = 0;
            for (int i = 0; i < name.Length; i++)
            {
                hash = (hash * 31 + name[i]) & 0x7FFFFFFF;
            }

            string key = Keys[hash % Keys.Length];
            var app = Application.Current;
            if (app != null)
            {
                object brush = app.TryFindResource(key);
                if (brush is Brush) return brush;
            }
            return new SolidColorBrush(Color.FromRgb(0x6B, 0x72, 0x80));
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture)
        {
            throw new NotSupportedException();
        }
    }
}
