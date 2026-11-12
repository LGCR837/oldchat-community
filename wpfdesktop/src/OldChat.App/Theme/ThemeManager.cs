using System;
using System.Windows;

namespace OldChat.App.Theme
{
    /// <summary>主题切换：只换第 0 个合并字典，控件样式始终在最上层。</summary>
    public static class ThemeManager
    {
        private const string DarkPath = "Themes/Dark.xaml";
        private const string LightPath = "Themes/Light.xaml";

        public static bool IsDark
        {
            get { return string.Equals(Current, "dark", StringComparison.OrdinalIgnoreCase); }
        }

        public static string Current { get; private set; }

        public static void Apply(string theme)
        {
            string normalized = string.Equals(theme, "light", StringComparison.OrdinalIgnoreCase) ? "light" : "dark";
            if (Application.Current == null) return;

            var dict = new ResourceDictionary
            {
                Source = new Uri(normalized == "light" ? LightPath : DarkPath, UriKind.Relative)
            };

            var merged = Application.Current.Resources.MergedDictionaries;
            if (merged.Count == 0)
            {
                merged.Add(dict);
            }
            else
            {
                merged[0] = dict;
            }
            Current = normalized;
        }

        public static void Toggle()
        {
            Apply(IsDark ? "light" : "dark");
        }
    }
}
