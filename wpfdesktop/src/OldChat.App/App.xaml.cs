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
                MessageBox.Show("出现了一个错误：" + args.Exception.Message, "OldChat", MessageBoxButton.OK, MessageBoxImage.Warning);
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
            Theme.ThemeManager.Apply(Config.Theme);

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
    }
}
