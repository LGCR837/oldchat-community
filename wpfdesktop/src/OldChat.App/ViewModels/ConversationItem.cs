using System;
using OldChat.Core.Protocol;

namespace OldChat.App.ViewModels
{
    /// <summary>会话列表一行。群聊与私聊共用。</summary>
    public sealed class ConversationItem : ObservableObject
    {
        private string _preview;
        private string _timeText;
        private int _unread;
        private long _lastAt;
        private bool _isOnline;
        private bool _isSelected;

        public string ChatId { get; private set; }
        public bool IsGroup { get; private set; }
        public string Title { get; set; }
        public string AvatarUrl { get; set; }
        public string Subtitle { get; set; }
        public GroupItem Group { get; private set; }
        public FriendItem Friend { get; private set; }

        public ConversationItem(string chatId, bool isGroup, string title, string avatarUrl)
        {
            ChatId = chatId;
            IsGroup = isGroup;
            Title = title;
            AvatarUrl = avatarUrl;
            _preview = string.Empty;
            _timeText = string.Empty;
        }

        public static ConversationItem FromGroup(GroupItem group)
        {
            return new ConversationItem(group.GroupId, true, group.Name, group.AvatarUrl)
            {
                Group = group,
                Subtitle = group.MemberCount + " 人"
            };
        }

        public static ConversationItem FromFriend(FriendItem friend)
        {
            return new ConversationItem(friend.Uid, false, friend.ShowName, friend.AvatarUrl)
            {
                Friend = friend,
                IsOnline = friend.IsOnline
            };
        }

        public string Preview
        {
            get { return _preview; }
            set
            {
                if (Set(ref _preview, value)) Raise("HasPreview");
            }
        }

        public string TimeText
        {
            get { return _timeText; }
            set { Set(ref _timeText, value); }
        }

        public int Unread
        {
            get { return _unread; }
            set
            {
                if (Set(ref _unread, value)) Raise("HasUnread");
            }
        }

        public bool HasUnread { get { return _unread > 0; } }

        public long LastAt
        {
            get { return _lastAt; }
            set { Set(ref _lastAt, value); }
        }

        public bool IsOnline
        {
            get { return _isOnline; }
            set { Set(ref _isOnline, value); }
        }

        /// <summary>行是否处于打开状态。选中背景由行自己画，不依赖 ListBox。</summary>
        public bool IsSelected
        {
            get { return _isSelected; }
            set { Set(ref _isSelected, value); }
        }

        public bool HasPreview { get { return !string.IsNullOrEmpty(_preview); } }

        /// <summary>可以用缓存里的旧预览先占位，避免没打开过的会话一片空白。</summary>
        public void ApplyCached(string preview, long lastAt, string timeText)
        {
            if (string.IsNullOrEmpty(preview) || lastAt <= 0) return;
            Preview = preview;
            LastAt = lastAt;
            TimeText = timeText ?? string.Empty;
        }

        /// <summary>记录一条消息作为最新预览。只有比现有更新才覆盖。</summary>
        public bool Touch(ChatMessage message)
        {
            if (message == null) return false;
            if (message.CreatedAt < _lastAt) return false;
            Preview = PreviewOf(message);
            LastAt = message.CreatedAt;
            TimeText = ChatTime.Friendly(message.LocalTime);
            Raise("HasPreview");
            return true;
        }

        public static string PreviewOf(ChatMessage message)
        {
            if (message == null) return string.Empty;
            MessageBody body = MessageBody.Parse(message.Body);
            string prefix = string.Empty;
            if (message.IsGroup && !string.IsNullOrEmpty(message.FromName))
            {
                prefix = message.FromName + "：";
            }

            switch (message.MsgType)
            {
                case "image": return prefix + "[图片]";
                case "voice": return prefix + "[语音]";
                case "video": return prefix + "[视频]";
                case "file": return prefix + "[文件]";
                default:
                    string text = body.Text ?? string.Empty;
                    text = text.Replace("\r", " ").Replace("\n", " ");
                    if (text.Length > 60) text = text.Substring(0, 60) + "…";
                    return prefix + text;
            }
        }
    }
}
