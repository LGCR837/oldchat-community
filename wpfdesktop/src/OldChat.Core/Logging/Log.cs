using System;
using System.IO;
using System.Text;
using System.Threading;

namespace OldChat.Core.Logging
{
    public enum LogLevel
    {
        Debug,
        Info,
        Warn,
        Error
    }

    /// <summary>极简滚动日志，只写文本文件，不引第三方日志库。</summary>
    public static class Log
    {
        private static readonly object Gate = new object();
        private static string _filePath;
        private static LogLevel _minLevel = LogLevel.Info;

        public static void Init(string directory, LogLevel minLevel)
        {
            if (string.IsNullOrEmpty(directory)) return;
            Directory.CreateDirectory(directory);
            _filePath = Path.Combine(directory, "oldchat-" + DateTime.Now.ToString("yyyyMMdd") + ".log");
            _minLevel = minLevel;
            TrimOldFiles(directory);
        }

        private static void TrimOldFiles(string directory)
        {
            try
            {
                var files = new DirectoryInfo(directory).GetFiles("oldchat-*.log");
                if (files.Length <= 7) return;
                Array.Sort(files, delegate (FileInfo a, FileInfo b) { return b.LastWriteTime.CompareTo(a.LastWriteTime); });
                for (int i = 7; i < files.Length; i++)
                {
                    try { files[i].Delete(); } catch { }
                }
            }
            catch { }
        }

        public static void Debug(string message) { Write(LogLevel.Debug, message); }
        public static void Info(string message) { Write(LogLevel.Info, message); }
        public static void Warn(string message) { Write(LogLevel.Warn, message); }

        public static void Error(string message, Exception ex = null)
        {
            Write(LogLevel.Error, ex == null ? message : message + " | " + ex.GetType().Name + ": " + ex.Message);
            if (ex != null) Write(LogLevel.Error, ex.StackTrace ?? string.Empty);
        }

        private static void Write(LogLevel level, string message)
        {
            if (level < _minLevel) return;
            string line = DateTime.Now.ToString("HH:mm:ss.fff") + " [" + level.ToString().ToUpperInvariant() + "] " + message;
            lock (Gate)
            {
                System.Diagnostics.Debug.WriteLine(line);
                if (_filePath == null) return;
                try
                {
                    File.AppendAllText(_filePath, line + Environment.NewLine, Encoding.UTF8);
                }
                catch
                {
                    // 日志失败不能影响主流程
                }
            }
        }
    }
}
