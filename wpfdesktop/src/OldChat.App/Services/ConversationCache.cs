using System;
using System.Collections.Generic;
using System.IO;
using System.Windows.Threading;
using Newtonsoft.Json;
using OldChat.Core.Config;
using OldChat.Core.Logging;

namespace OldChat.App.Services
{
    /// <summary>
    /// 会话最后一条消息的落盘缓存。
    /// 官方客户端的列表预览来自本地缓存，所以没打开过的会话也有文字；
    /// 这里做同一件事，顺便给列表提供排序用的时间戳。
    /// </summary>
    public sealed class ConversationCache
    {
        private sealed class Entry
        {
            [JsonProperty("p")] public string Preview { get; set; }
            [JsonProperty("t")] public long LastAt { get; set; }
            [JsonProperty("n")] public string TimeText { get; set; }
        }

        private readonly Dictionary<string, Entry> _entries =
            new Dictionary<string, Entry>(StringComparer.Ordinal);
        private readonly DispatcherTimer _saveTimer;
        private bool _dirty;

        public ConversationCache()
        {
            _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
            _saveTimer.Tick += delegate { Flush(); };
        }

        private static string FilePath
        {
            get { return Path.Combine(AppConfig.DataDir, "conversations.json"); }
        }

        public void Load()
        {
            try
            {
                if (!File.Exists(FilePath)) return;
                var map = JsonConvert.DeserializeObject<Dictionary<string, Entry>>(File.ReadAllText(FilePath));
                if (map == null) return;
                foreach (var pair in map)
                {
                    if (pair.Value == null || string.IsNullOrEmpty(pair.Value.Preview)) continue;
                    _entries[pair.Key] = pair.Value;
                }
            }
            catch (Exception ex)
            {
                Log.Warn("会话缓存读取失败：" + ex.Message);
            }
        }

        public bool TryGet(string key, out string preview, out long lastAt, out string timeText)
        {
            preview = null;
            lastAt = 0;
            timeText = null;
            Entry entry;
            if (string.IsNullOrEmpty(key) || !_entries.TryGetValue(key, out entry)) return false;
            preview = entry.Preview;
            lastAt = entry.LastAt;
            timeText = entry.TimeText;
            return !string.IsNullOrEmpty(preview);
        }

        public void Put(string key, string preview, long lastAt, string timeText)
        {
            if (string.IsNullOrEmpty(key) || string.IsNullOrEmpty(preview)) return;
            Entry entry;
            if (_entries.TryGetValue(key, out entry) && entry.LastAt > lastAt) return;

            _entries[key] = new Entry { Preview = preview, LastAt = lastAt, TimeText = timeText ?? string.Empty };
            _dirty = true;
            if (!_saveTimer.IsEnabled) _saveTimer.Start();
        }

        public void Flush()
        {
            _saveTimer.Stop();
            if (!_dirty) return;
            _dirty = false;
            try
            {
                File.WriteAllText(FilePath, JsonConvert.SerializeObject(_entries));
            }
            catch (Exception ex)
            {
                Log.Warn("会话缓存写入失败：" + ex.Message);
            }
        }
    }
}
