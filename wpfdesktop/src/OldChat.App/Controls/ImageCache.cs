using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OldChat.Core.Config;
using OldChat.Core.Logging;

namespace OldChat.App.Controls
{
    /// <summary>
    /// 图片两级缓存：内存 + 磁盘。头像与聊天图片共用同一套。
    /// 上传目录里的媒体是公开可读的，不需要带 token。
    /// </summary>
    public static class ImageCache
    {
        private static readonly Dictionary<string, ImageSource> Memory = new Dictionary<string, ImageSource>(StringComparer.Ordinal);
        private static readonly HashSet<string> Pending = new HashSet<string>(StringComparer.Ordinal);
        private static readonly object Gate = new object();
        private static readonly HttpClient Http = CreateClient();

        private static HttpClient CreateClient()
        {
            var client = new HttpClient();
            client.Timeout = TimeSpan.FromSeconds(30);
            client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "OldChat-Desktop/0.1");
            return client;
        }

        private static string CacheDir
        {
            get
            {
                string dir = Path.Combine(AppConfig.DataDir, "cache", "img");
                Directory.CreateDirectory(dir);
                return dir;
            }
        }

        /// <summary>命中缓存直接返回；否则返回 null 并在后台加载后回调（回调在 UI 线程）。</summary>
        public static ImageSource Get(string url, double decodeWidth, Action<ImageSource> onLoaded)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            string key = url + "|" + (int)decodeWidth;

            lock (Gate)
            {
                ImageSource cached;
                if (Memory.TryGetValue(key, out cached)) return cached;
                if (Pending.Contains(key)) return null;
                Pending.Add(key);
            }

            string file = CachePath(url, decodeWidth);
            ImageSource fromDisk = TryLoadFile(file, decodeWidth);
            if (fromDisk != null)
            {
                Store(key, fromDisk);
                if (onLoaded != null) onLoaded(fromDisk);
                return fromDisk;
            }

            System.Threading.Tasks.Task loadTask = System.Threading.Tasks.Task.Run(async delegate
            {
                ImageSource loaded = null;
                try
                {
                    byte[] data = await Http.GetByteArrayAsync(url).ConfigureAwait(false);
                    loaded = Decode(data, decodeWidth);
                    if (loaded != null)
                    {
                        try { File.WriteAllBytes(file, data); } catch { }
                    }
                }
                catch (Exception ex)
                {
                    Log.Warn("图片加载失败 " + url + " " + ex.GetType().Name);
                }
                finally
                {
                    lock (Gate) { Pending.Remove(key); }
                }

                if (loaded == null) return;
                Store(key, loaded);

                if (onLoaded != null)
                {
                    DispatcherOperation ignored = Application.Current.Dispatcher.BeginInvoke(
                        new Action(delegate { onLoaded(loaded); }), DispatcherPriority.Background);
                }
            });

            return null;
        }

        private static void Store(string key, ImageSource source)
        {
            lock (Gate)
            {
                if (Memory.Count > 400) Memory.Clear();
                Memory[key] = source;
            }
        }

        private static string CachePath(string url, double decodeWidth)
        {
            using (var sha = SHA1.Create())
            {
                byte[] hash = sha.ComputeHash(Encoding.UTF8.GetBytes(url + "|" + (int)decodeWidth));
                var sb = new StringBuilder();
                for (int i = 0; i < 12; i++) sb.Append(hash[i].ToString("x2"));
                return Path.Combine(CacheDir, sb + ".img");
            }
        }

        private static ImageSource TryLoadFile(string path, double decodeWidth)
        {
            try
            {
                if (!File.Exists(path)) return null;
                return Decode(File.ReadAllBytes(path), decodeWidth);
            }
            catch
            {
                return null;
            }
        }

        public static ImageSource Decode(byte[] data, double decodeWidth)
        {
            try
            {
                using (var stream = new MemoryStream(data))
                {
                    var image = new BitmapImage();
                    image.BeginInit();
                    image.CacheOption = BitmapCacheOption.OnLoad;
                    if (decodeWidth > 0) image.DecodePixelWidth = (int)decodeWidth;
                    image.StreamSource = stream;
                    image.EndInit();
                    image.Freeze();
                    return image;
                }
            }
            catch (Exception ex)
            {
                Log.Warn("图片解码失败 " + ex.GetType().Name);
                return null;
            }
        }
    }
}
