using System;
using System.Windows;
using OldChat.Core.Config;
using OldChat.Core.Logging;

namespace OldChat.App
{
    public partial class App : Application
    {
        public static AppSession Session { get; private set; }
        public static AppConfig Config { get; private set; }

        private static int _errorPopups;

        public static void SetConfig(AppConfig config)
        {
            Config = config;
        }

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            Log.Init(System.IO.Path.Combine(AppConfig.DataDir, "logs"), LogLevel.Info);
            Log.Info("进程启动 args=" + string.Join(" ", e.Args));

            DispatcherUnhandledException += delegate (object s, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs args)
            {
                Log.Error("界面线程未处理异常", args.Exception);
                // 模板渲染异常会逐帧重复抛，弹窗只给前两次，其余只进日志
                if (System.Threading.Interlocked.Increment(ref _errorPopups) <= 2)
                {
                    MessageBox.Show("出现了一个错误：" + args.Exception.Message, "旧聊",
                        MessageBoxButton.OK, MessageBoxImage.Warning);
                }
                args.Handled = true;
            };
            AppDomain.CurrentDomain.UnhandledException += delegate (object s, UnhandledExceptionEventArgs args)
            {
                Log.Error("后台未处理异常", args.ExceptionObject as Exception);
            };
            System.Threading.Tasks.TaskScheduler.UnobservedTaskException += delegate (object s, System.Threading.Tasks.UnobservedTaskExceptionEventArgs args)
            {
                Log.Error("任务异常", args.Exception);
                args.SetObserved();
            };

            Config = AppConfig.Load();

            // --origin=http://... 临时换源，只在本次进程生效，不覆盖配置里记住的服务器
            string cliOrigin = CommandLine.Value(e.Args, "--origin");
            if (!string.IsNullOrWhiteSpace(cliOrigin))
            {
                Config.Origin = AppConfig.NormalizeOrigin(cliOrigin);
                Config.EphemeralOrigin = true;
            }

            Theme.ThemeManager.Apply(Config.Theme);
            Controls.BubbleShape.Apply(Config.BubbleRounded);

            if (CommandLine.HasFlag(e.Args, "--reset"))
            {
                SessionStore.Clear();
            }

            var login = new Views.LoginWindow();
            MainWindow = login;
            login.Show();
        }

        public static void SetSession(AppSession session)
        {
            if (Session != null) Session.Dispose();
            Session = session;
        }

        public static void SwitchTheme(string theme)
        {
            if (Config == null) return;
            Config.Theme = theme;
            Config.Save();
            Theme.ThemeManager.Apply(theme);
        }
    }

    internal static class CommandLine
    {
        public static bool HasFlag(string[] args, string flag)
        {
            if (args == null) return false;
            foreach (string arg in args)
            {
                if (string.Equals(arg, flag, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        /// <summary>取 --name=value 形式参数的值，没有就返回 null。</summary>
        public static string Value(string[] args, string name)
        {
            if (args == null) return null;
            string prefix = name + "=";
            foreach (string arg in args)
            {
                if (arg != null && arg.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                {
                    return arg.Substring(prefix.Length);
                }
            }
            return null;
        }
    }
}
