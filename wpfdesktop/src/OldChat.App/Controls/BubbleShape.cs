using System.Windows;

namespace OldChat.App.Controls
{
    /// <summary>
    /// 气泡形状。官方两套：圆角（默认，16dp，靠己方那个角留尖）与直角。
    /// 通过全局资源切换，DynamicResource 会立刻刷新已经显示的气泡。
    /// </summary>
    public static class BubbleShape
    {
        private const double Radius = 16;

        public static void Apply(bool rounded)
        {
            Application app = Application.Current;
            if (app == null) return;

            // 对方：左上角留尖；自己：右上角留尖
            CornerRadius inCorner = rounded
                ? new CornerRadius(0, Radius, Radius, Radius)
                : new CornerRadius(0);
            CornerRadius outCorner = rounded
                ? new CornerRadius(Radius, 0, Radius, Radius)
                : new CornerRadius(0);

            app.Resources["BubbleInCorner"] = inCorner;
            app.Resources["BubbleOutCorner"] = outCorner;
        }
    }
}
