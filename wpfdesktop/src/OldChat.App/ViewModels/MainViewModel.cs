using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using OldChat.App.Services;
using OldChat.Core.Logging;
using OldChat.Core.Net;
using OldChat.Core.Protocol;
using OldChat.Core.Services;

namespace OldChat.App.ViewModels
{
    public sealed class MainViewModel : ObservableObject
    {
        private readonly AppSession _session;
        private readonly Dictionary<string, Dictionary<string, GroupMemberItem>> _members =
            new Dictionary<string, Dictionary<string, GroupMemberItem>>(StringComparer.Ordinal);
        private readonly ConversationCache _cache = new ConversationCache();
        private ConversationItem _selected;
        private string _searchText;
        private string _composerText;
        private bool _isLoading;
        private string _statusLine;

        public ObservableCollection<ConversationItem> Conversations { get; private set; }
        public ObservableCollection<ChatSection> Sections { get; private set; }
        public ObservableCollection<ContactSection> ContactSections { get; private set; }
        public ObservableCollection<MessageItem> Messages { get; private set; }

        public event EventHandler<MessageItem> MessageAppended;

        public MainViewModel(AppSession session)
        {
            _session = session;
            Conversations = new ObservableCollection<ConversationItem>();
            Sections = new ObservableCollection<ChatSection>();
            ContactSections = new ObservableCollection<ContactSection>();
            Messages = new ObservableCollection<MessageItem>();

            SearchText = string.Empty;
            ComposerText = string.Empty;
            _statusLine = "正在同步…";
        }

        public string MyDisplayName
        {
            get { return _session.Me == null ? string.Empty : _session.Me.DisplayText; }
        }

        public string MyAvatarUrl
        {
            get
            {
                if (_session.Me == null) return null;
                return MediaUrl.Resolve(_session.Origin, _session.Me.AvatarUrl);
            }
        }

        public string Origin { get { return _session.Origin; } }

        public string SearchText
        {
            get { return _searchText; }
            set
            {
                if (Set(ref _searchText, value)) RebuildSections();
            }
        }

        public string ComposerText
        {
            get { return _composerText; }
            set { Set(ref _composerText, value); }
        }

        public bool IsLoading
        {
            get { return _isLoading; }
            set { Set(ref _isLoading, value); }
        }

        public string StatusLine
        {
            get { return _statusLine; }
            set { Set(ref _statusLine, value); }
        }

        public ConversationItem Selected
        {
            get { return _selected; }
            set
            {
                if (_selected == value) return;
                if (_selected != null)
                {
                    _selected.Unread = 0;
                    _selected.IsSelected = false;
                }
                Set(ref _selected, value);
                Raise("HasSelection");
                Raise("HeaderTitle");
                Raise("HeaderSubtitle");
                if (value != null)
                {
                    value.Unread = 0;
                    value.IsSelected = true;
                    var ignored = LoadHistoryAsync(value);
                }
            }
        }

        /// <summary>列表行被点开。会话可能还没在 Conversations 里注册（联系人页进来的）。</summary>
        public void SelectConversation(ConversationItem conv)
        {
            if (conv == null) return;
            ConversationItem known = Conversations.FirstOrDefault(
                c => c.IsGroup == conv.IsGroup && c.ChatId == conv.ChatId);
            Selected = known ?? conv;
        }

        public bool HasSelection { get { return _selected != null; } }

        public string HeaderTitle { get { return _selected == null ? string.Empty : _selected.Title; } }

        public string HeaderSubtitle
        {
            get
            {
                if (_selected == null) return string.Empty;
                if (_selected.IsGroup) return _selected.Subtitle ?? string.Empty;
                if (_selected.Friend == null) return string.Empty;
                return _selected.Friend.IsOnline ? "在线" : "离线";
            }
        }

        // ---------- 初始化 ----------

        public async Task InitializeAsync()
        {
            IsLoading = true;
            StatusLine = "正在同步会话…";
            try
            {
                _cache.Load();
                List<GroupItem> groups = await _session.Chat.GetGroupsAsync().ConfigureAwait(true);
                List<FriendItem> friends = await _session.Chat.GetFriendsAsync().ConfigureAwait(true);

                Conversations.Clear();
                foreach (GroupItem group in groups) Conversations.Add(ConversationItem.FromGroup(group));
                foreach (FriendItem friend in friends) Conversations.Add(ConversationItem.FromFriend(friend));

                // 缓存先铺一层，没打开过的会话也有预览与时间，列表排序才有意义
                foreach (ConversationItem conv in Conversations)
                {
                    string preview, timeText;
                    long lastAt;
                    if (_cache.TryGet(CacheKey(conv), out preview, out lastAt, out timeText))
                    {
                        conv.ApplyCached(preview, lastAt, timeText);
                    }
                }

                RebuildSections();
                BuildContacts(groups, friends);

                StatusLine = "群 " + groups.Count + " 个 · 好友 " + friends.Count + " 个";
                Log.Info("会话同步完成 groups=" + groups.Count + " friends=" + friends.Count);

                ConversationItem first = Sections.SelectMany(s => s.Items).FirstOrDefault();
                if (first != null) Selected = first;
            }
            catch (ApiException ex)
            {
                StatusLine = "同步失败：" + ex.FriendlyMessage;
            }
            catch (Exception ex)
            {
                Log.Error("同步失败", ex);
                StatusLine = "同步失败：" + ex.Message;
            }
            finally
            {
                IsLoading = false;
            }
        }

        private static string CacheKey(ConversationItem conv)
        {
            return (conv.IsGroup ? "g:" : "u:") + conv.ChatId;
        }

        // ---------- 列表分节 ----------

        /// <summary>
        /// 参考官方客户端：未读单独一节顶在最上面，其余按群聊/私聊分开，节内按最后消息时间倒序。
        /// 搜索时不分组，避免一堆只有一个结果的标题。
        /// </summary>
        private void RebuildSections()
        {
            string needle = (_searchText ?? string.Empty).Trim();
            IEnumerable<ConversationItem> source = Conversations;
            if (needle.Length > 0)
            {
                source = Conversations.Where(c =>
                    (c.Title ?? string.Empty).IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0
                    || (c.Preview ?? string.Empty).IndexOf(needle, StringComparison.OrdinalIgnoreCase) >= 0);
            }

            List<ConversationItem> matched = source.ToList();
            Sections.Clear();

            if (needle.Length > 0)
            {
                ChatSection hit = new ChatSection("搜索结果");
                foreach (ConversationItem conv in matched.OrderByDescending(c => c.LastAt)) hit.Items.Add(conv);
                if (hit.Items.Count > 0) Sections.Add(hit);
                return;
            }

            AddSection("未读",
                matched.Where(c => c.Unread > 0).OrderByDescending(c => c.LastAt));
            AddSection("群聊",
                matched.Where(c => c.Unread <= 0 && c.IsGroup).OrderByDescending(c => c.LastAt));
            AddSection("私聊",
                matched.Where(c => c.Unread <= 0 && !c.IsGroup).OrderByDescending(c => c.LastAt));
        }

        private void AddSection(string title, IEnumerable<ConversationItem> items)
        {
            var section = new ChatSection(title);
            foreach (ConversationItem item in items) section.Items.Add(item);
            if (section.Items.Count > 0) Sections.Add(section);
        }

        // ---------- 联系人 ----------

        private void BuildContacts(List<GroupItem> groups, List<FriendItem> friends)
        {
            ContactSections.Clear();
            StringComparer byName = StringComparer.Create(new System.Globalization.CultureInfo("zh-CN"), true);

            // 服务端会给迁移占位账号打 migrated=false。整批都没这个标记时说明服务端没给，按正常处理。
            bool hasPendingMark = groups.Any(g => g.Migrated) || friends.Any(f => f.Migrated);

            var groupSection = new ContactSection("群聊", 0);
            foreach (GroupItem group in groups.Where(g => !hasPendingMark || g.Migrated).OrderBy(g => g.Name ?? string.Empty, byName))
            {
                groupSection.Items.Add(new ContactItem
                {
                    Id = group.GroupId,
                    IsGroup = true,
                    Name = group.Name,
                    AvatarUrl = MediaUrl.Resolve(_session.Origin, group.AvatarUrl),
                    Subtitle = group.MemberCount > 0 ? group.MemberCount + " 人" : "群聊"
                });
            }
            if (groupSection.Items.Count > 0) ContactSections.Add(groupSection);

            List<FriendItem> known = friends.Where(f => !hasPendingMark || f.Migrated).ToList();

            var recentSection = new ContactSection("最新添加", 0);
            foreach (FriendItem friend in known
                .Where(f => f.FriendAddedAt > 0)
                .OrderByDescending(f => f.FriendAddedAt)
                .Take(8))
            {
                recentSection.Items.Add(ToContact(friend));
            }
            if (recentSection.Items.Count > 0) ContactSections.Add(recentSection);

            var friendSection = new ContactSection("全部好友", 0);
            foreach (FriendItem friend in known.OrderBy(f => f.ShowName ?? string.Empty, byName))
            {
                friendSection.Items.Add(ToContact(friend));
            }
            if (friendSection.Items.Count > 0) ContactSections.Add(friendSection);

            var pendingSection = new ContactSection("待迁移", 0);
            if (hasPendingMark)
            {
                foreach (GroupItem group in groups.Where(g => !g.Migrated))
                {
                    pendingSection.Items.Add(new ContactItem
                    {
                        Id = group.GroupId,
                        IsGroup = true,
                        IsPending = true,
                        Name = group.Name,
                        AvatarUrl = MediaUrl.Resolve(_session.Origin, group.AvatarUrl),
                        Subtitle = "未迁移"
                    });
                }
                foreach (FriendItem friend in friends.Where(f => !f.Migrated))
                {
                    ContactItem item = ToContact(friend);
                    item.IsPending = true;
                    item.Subtitle = "未迁移";
                    pendingSection.Items.Add(item);
                }
            }
            if (pendingSection.Items.Count > 0) ContactSections.Add(pendingSection);
        }

        private ContactItem ToContact(FriendItem friend)
        {
            return new ContactItem
            {
                Id = friend.Uid,
                IsGroup = false,
                Name = friend.ShowName,
                AvatarUrl = MediaUrl.Resolve(_session.Origin, friend.AvatarUrl),
                Subtitle = friend.IsOnline ? "在线" : "离线",
                IsOnline = friend.IsOnline
            };
        }

        /// <summary>联系人宫格里点一格，直接打开对应会话。</summary>
        public void OpenContact(ContactItem contact)
        {
            if (contact == null) return;
            ConversationItem conv = FindConversation(contact.IsGroup, contact.Id);
            if (conv != null) Selected = conv;
        }

        public ConversationItem FindConversation(bool isGroup, string id)
        {
            if (string.IsNullOrEmpty(id)) return null;
            return Conversations.FirstOrDefault(c => c.IsGroup == isGroup && c.ChatId == id);
        }

        // ---------- 历史消息 ----------

        public async Task LoadHistoryAsync(ConversationItem conv)
        {
            if (conv == null) return;
            Messages.Clear();
            IsLoading = true;
            try
            {
                // 成员表与历史并行拉，成员表晚到也能回填头像
                Task memberTask = EnsureMembersAsync(conv);
                Task<MessagePage> pageTask = conv.IsGroup
                    ? _session.Chat.LoadGroupHistoryAsync(conv.ChatId, 0)
                    : _session.Chat.LoadDirectHistoryAsync(conv.ChatId, 0, null);

                await Task.WhenAll(pageTask, memberTask).ConfigureAwait(true);
                MessagePage page = pageTask.Result;

                AppendPage(page, conv, prepend: false);

                // 服务端返回顺序不保证，取集合末尾（已按时间升序整理）才是真正的最后一条
                MessageItem latest = Messages.Count > 0 ? Messages[Messages.Count - 1] : null;
                if (latest != null && conv.Touch(latest.Source))
                {
                    _cache.Put(CacheKey(conv), conv.Preview, conv.LastAt, conv.TimeText);
                }

                StatusLine = page.HasMore ? "已加载最近 " + page.Messages.Count + " 条" : "已到最早一条";
                Log.Info("历史加载完成 " + conv.Title + " group=" + conv.IsGroup + " count=" + page.Messages.Count);
            }
            catch (ApiException ex)
            {
                StatusLine = "加载消息失败：" + ex.FriendlyMessage;
            }
            catch (Exception ex)
            {
                Log.Error("加载消息异常", ex);
                StatusLine = "加载消息失败：" + ex.Message;
            }
            finally
            {
                IsLoading = false;
            }
        }

        /// <summary>群成员表按群缓存，消息头像与昵称都从这里补。</summary>
        private async Task EnsureMembersAsync(ConversationItem conv)
        {
            if (conv == null || !conv.IsGroup) return;
            if (_members.ContainsKey(conv.ChatId)) return;

            Dictionary<string, GroupMemberItem> map;
            try
            {
                map = await _session.Chat.GetGroupMembersAsync(conv.ChatId).ConfigureAwait(true);
            }
            catch (Exception ex)
            {
                Log.Warn("群成员表加载失败 " + conv.Title + " " + ex.Message);
                return;
            }

            _members[conv.ChatId] = map;
            Log.Info("群成员表 " + conv.Title + " " + map.Count + " 人");

            foreach (MessageItem item in Messages)
            {
                if (item.IsMine || !item.IsGroup) continue;
                GroupMemberItem member;
                if (!map.TryGetValue(item.Source.FromUid, out member)) continue;
                item.ApplyMember(member.ShowName, MediaUrl.Resolve(_session.Origin, member.AvatarUrl));
            }
        }

        private GroupMemberItem FindMember(string groupId, string uid)
        {
            Dictionary<string, GroupMemberItem> map;
            if (string.IsNullOrEmpty(uid) || !_members.TryGetValue(groupId, out map)) return null;
            GroupMemberItem member;
            return map.TryGetValue(uid, out member) ? member : null;
        }

        public async Task LoadOlderAsync()
        {
            ConversationItem conv = Selected;
            if (conv == null || Messages.Count == 0) return;

            MessageItem oldest = Messages[0];
            try
            {
                MessagePage page = conv.IsGroup
                    ? await _session.Chat.LoadGroupHistoryAsync(conv.ChatId, oldest.Source.GroupSeq).ConfigureAwait(true)
                    : await _session.Chat.LoadDirectHistoryAsync(conv.ChatId, oldest.Source.CreatedAt, oldest.Source.Id).ConfigureAwait(true);

                if (page.Messages.Count == 0)
                {
                    StatusLine = "已到最早一条";
                    return;
                }
                AppendPage(page, conv, prepend: true);
            }
            catch (Exception ex)
            {
                Log.Warn("加载更早消息失败：" + ex.Message);
            }
        }

        private void AppendPage(MessagePage page, ConversationItem conv, bool prepend)
        {
            var built = new List<MessageItem>(page.Messages.Count);
            foreach (ChatMessage msg in page.Messages)
            {
                if (Messages.Any(m => m.Source.Id == msg.Id)) continue;
                built.Add(BuildItem(msg, conv));
            }
            built.Sort((a, b) => a.Source.CreatedAt.CompareTo(b.Source.CreatedAt));
            ApplyTimeHeaders(built);

            if (prepend)
            {
                for (int i = built.Count - 1; i >= 0; i--) Messages.Insert(0, built[i]);
            }
            else
            {
                foreach (MessageItem item in built) Messages.Add(item);
            }
        }

        private static void ApplyTimeHeaders(List<MessageItem> items)
        {
            long previous = 0;
            foreach (MessageItem item in items)
            {
                bool show = previous == 0 || item.Source.CreatedAt - previous > 300;
                item.ShowTimeHeader = show;
                item.TimeHeaderText = FriendlyTimestamp(item.Source.LocalTime);
                previous = item.Source.CreatedAt;
            }
        }

        private static string FriendlyTimestamp(DateTime time)
        {
            DateTime now = DateTime.Now;
            if (time.Date == now.Date) return time.ToString("HH:mm");
            if (time.Date == now.Date.AddDays(-1)) return "昨天 " + time.ToString("HH:mm");
            if (time.Year == now.Year) return time.ToString("M月d日 HH:mm");
            return time.ToString("yyyy年M月d日 HH:mm");
        }

        private MessageItem BuildItem(ChatMessage msg, ConversationItem conv)
        {
            bool mine = string.Equals(msg.FromUid, _session.MyUid, StringComparison.Ordinal);
            GroupMemberItem member = conv.IsGroup && !mine ? FindMember(conv.ChatId, msg.FromUid) : null;

            string name;
            if (mine) name = MyDisplayName;
            else if (member != null) name = member.ShowName;
            else if (!string.IsNullOrEmpty(msg.FromName)) name = msg.FromName;
            else if (conv.Friend != null) name = conv.Friend.ShowName;
            else name = msg.FromUid;

            string avatar;
            if (mine) avatar = MyAvatarUrl;
            else if (member != null) avatar = MediaUrl.Resolve(_session.Origin, member.AvatarUrl);
            else if (conv.Friend != null) avatar = MediaUrl.Resolve(_session.Origin, conv.Friend.AvatarUrl);
            else avatar = null;

            return new MessageItem(msg, mine, name, avatar);
        }

        // ---------- 发送 ----------

        public async Task<bool> SendAsync()
        {
            ConversationItem conv = Selected;
            string text = (ComposerText ?? string.Empty).Trim();
            if (conv == null || text.Length == 0) return false;

            ComposerText = string.Empty;

            var optimistic = new ChatMessage
            {
                Id = "local-" + Guid.NewGuid().ToString("N"),
                ChatId = conv.ChatId,
                IsGroup = conv.IsGroup,
                FromUid = _session.MyUid,
                FromName = MyDisplayName,
                Body = text,
                MsgType = "text",
                CreatedAt = ChatTime.ToUnix(DateTime.Now),
                LocalState = "sending"
            };

            MessageItem pending = BuildItem(optimistic, conv);
            ApplyTimeHeaders(new List<MessageItem> { pending });
            Messages.Add(pending);
            RaiseMessageAppended(pending);

            try
            {
                ChatMessage sent = conv.IsGroup
                    ? await _session.Chat.SendGroupMessageAsync(conv.ChatId, text).ConfigureAwait(true)
                    : await _session.Chat.SendDirectMessageAsync(conv.ChatId, text).ConfigureAwait(true);

                if (sent == null) throw new ApiException(System.Net.HttpStatusCode.OK, "empty_response", "服务端未返回消息");

                pending.LocalState = "sent";
                pending.Source.Id = sent.Id;
                pending.Source.GroupSeq = sent.GroupSeq;
                pending.Source.SortSeq = sent.SortSeq;
                pending.Source.CreatedAt = sent.CreatedAt > 0 ? sent.CreatedAt : pending.Source.CreatedAt;
                if (conv.Touch(pending.Source))
                {
                    _cache.Put(CacheKey(conv), conv.Preview, conv.LastAt, conv.TimeText);
                }
                return true;
            }
            catch (ApiException ex)
            {
                pending.LocalState = "failed";
                pending.LocalError = ex.FriendlyMessage;
                StatusLine = "发送失败：" + ex.FriendlyMessage;
                return false;
            }
            catch (Exception ex)
            {
                Log.Error("发送消息异常", ex);
                pending.LocalState = "failed";
                pending.LocalError = ex.Message;
                return false;
            }
        }

        // ---------- 实时推送 ----------

        public void AttachRealtime()
        {
            if (_session.Ws == null) return;
            _session.Ws.MessageReceived += OnWsMessage;
        }

        public void DetachRealtime()
        {
            if (_session.Ws == null) return;
            _session.Ws.MessageReceived -= OnWsMessage;
        }

        private void OnWsMessage(object sender, WsEventArgs e)
        {
            if (e.Data == null) return;
            switch (e.Type)
            {
                case "group_message":
                    HandleIncoming(e.Data, true);
                    break;
                case "direct_message":
                    HandleIncoming(e.Data, false);
                    break;
                case "presence":
                    HandlePresence(e.Data);
                    break;
            }
        }

        private void HandleIncoming(Newtonsoft.Json.Linq.JToken data, bool isGroup)
        {
            string chatId = isGroup ? (string)data["group_id"] : (string)data["from_uid"];
            if (string.IsNullOrEmpty(chatId)) return;

            ChatMessage msg = ChatService.MapMessage(data, chatId, isGroup);
            if (msg == null || string.IsNullOrEmpty(msg.Id)) return;

            if (_selected != null && _selected.ChatId == chatId)
            {
                if (Messages.Any(m => m.Source.Id == msg.Id)) return;
                MessageItem item = BuildItem(msg, _selected);
                List<MessageItem> tail = Messages.Count > 0
                    ? new List<MessageItem> { Messages[Messages.Count - 1], item }
                    : new List<MessageItem> { item };
                ApplyTimeHeaders(tail);
                Messages.Add(item);
                RaiseMessageAppended(item);
            }
            else
            {
                ConversationItem conv = Conversations.FirstOrDefault(c => c.ChatId == chatId);
                if (conv != null)
                {
                    if (conv.Touch(msg))
                    {
                        conv.Unread = conv.Unread + 1;
                        RebuildSections();
                        _cache.Put(CacheKey(conv), conv.Preview, conv.LastAt, conv.TimeText);
                    }
                }
            }
        }

        private void HandlePresence(Newtonsoft.Json.Linq.JToken data)
        {
            string uid = (string)data["uid"];
            bool online = (bool?)data["is_online"] ?? false;
            if (string.IsNullOrEmpty(uid)) return;
            foreach (ConversationItem conv in Conversations)
            {
                if (!conv.IsGroup && conv.ChatId == uid) conv.IsOnline = online;
            }
            if (_selected != null && !_selected.IsGroup && _selected.ChatId == uid) Raise("HeaderSubtitle");
        }

        private void RaiseMessageAppended(MessageItem item)
        {
            var handler = MessageAppended;
            if (handler != null) handler(this, item);
        }
    }
}
