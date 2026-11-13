using System;
using System.Collections.ObjectModel;

namespace OldChat.App.ViewModels
{
    /// <summary>会话列表里的一段。官方客户端就是按段渲染（未读/普通/折叠）。</summary>
    public sealed class ChatSection
    {
        public ChatSection(string title)
        {
            Title = title;
            Items = new ObservableCollection<ConversationItem>();
        }

        public string Title { get; private set; }
        public ObservableCollection<ConversationItem> Items { get; private set; }
    }

    /// <summary>联系人宫格里的一格。群聊与好友共用。</summary>
    public sealed class ContactItem : ObservableObject
    {
        private bool _isOnline;

        public string Id { get; set; }
        public bool IsGroup { get; set; }
        public string Name { get; set; }
        public string AvatarUrl { get; set; }
        public string Subtitle { get; set; }
        public bool IsPending { get; set; }

        public bool IsOnline
        {
            get { return _isOnline; }
            set { Set(ref _isOnline, value); }
        }

        public string Initial
        {
            get
            {
                if (string.IsNullOrEmpty(Name)) return "?";
                return Name.Substring(0, 1);
            }
        }
    }

    public sealed class ContactSection
    {
        public ContactSection(string title, int count)
        {
            Title = title;
            Count = count;
            Items = new ObservableCollection<ContactItem>();
        }

        public string Title { get; private set; }
        public int Count { get; private set; }
        public ObservableCollection<ContactItem> Items { get; private set; }
    }
}
