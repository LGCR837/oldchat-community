using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using OldChat.Core.Net;
using OldChat.Core.Protocol;

namespace OldChat.Core.Services
{
    /// <summary>好友、群、消息收发。分页统一走 /v2 的 keyset 接口。</summary>
    public sealed class ChatService
    {
        private const int DefaultPageSize = 40;

        private readonly ApiClient _api;

        public ChatService(ApiClient api)
        {
            _api = api;
        }

        public string Origin { get { return _api.Origin; } }

        public async Task<List<FriendItem>> GetFriendsAsync()
        {
            JToken resp = await _api.GetAsync("/v1/friends").ConfigureAwait(false);
            var list = new List<FriendItem>();
            if (resp == null) return list;
            AppendItems(list, resp["friends"]);
            AppendItems(list, resp["pending_users"]);
            return list;
        }

        public async Task<List<GroupItem>> GetGroupsAsync()
        {
            JToken resp = await _api.GetAsync("/v1/groups/list").ConfigureAwait(false);
            var list = new List<GroupItem>();
            if (resp == null) return list;
            AppendItems(list, resp["groups"]);
            AppendItems(list, resp["pending_groups"]);
            return list;
        }

        /// <summary>群历史消息。beforeSeq 为 0 表示取最新一页，往前翻传上一页的 NextGroupSeq。</summary>
        public async Task<MessagePage> LoadGroupHistoryAsync(string groupId, long beforeSeq, int limit = DefaultPageSize)
        {
            var query = new Dictionary<string, string>
            {
                ["group_id"] = groupId,
                ["limit"] = limit.ToString()
            };
            if (beforeSeq > 0) query["before_seq"] = beforeSeq.ToString();

            JToken resp = await _api.GetAsync("/v2/groups/messages/v2", query).ConfigureAwait(false);
            return ParsePage(resp, groupId, true);
        }

        public async Task<MessagePage> LoadDirectHistoryAsync(string peerUid, long beforeCreatedAt, string beforeId, int limit = DefaultPageSize)
        {
            var query = new Dictionary<string, string>
            {
                ["with_uid"] = peerUid,
                ["limit"] = limit.ToString()
            };
            if (beforeCreatedAt > 0) query["before_created_at"] = beforeCreatedAt.ToString();
            if (!string.IsNullOrEmpty(beforeId)) query["before_id"] = beforeId;

            JToken resp = await _api.GetAsync("/v2/direct/messages/v2", query).ConfigureAwait(false);
            return ParsePage(resp, peerUid, false);
        }

        public async Task<ChatMessage> SendGroupMessageAsync(string groupId, string text)
        {
            var body = new JObject
            {
                ["group_id"] = groupId,
                ["body"] = text ?? string.Empty,
                ["msg_type"] = "text"
            };
            JToken resp = await _api.PostAsync("/v2/groups/message/send", body).ConfigureAwait(false);
            return MapMessage(resp, groupId, true);
        }

        public async Task<ChatMessage> SendDirectMessageAsync(string peerUid, string text)
        {
            var body = new JObject
            {
                ["to_uid"] = peerUid,
                ["body"] = text ?? string.Empty,
                ["msg_type"] = "text"
            };
            JToken resp = await _api.PostAsync("/v2/direct/send", body).ConfigureAwait(false);
            return MapMessage(resp, peerUid, false);
        }

        /// <summary>群消息增量：拉取 seq 之后的新消息。</summary>
        public async Task<MessagePage> LoadGroupMessagesAfterAsync(string groupId, long afterSeq, int limit = 100)
        {
            var query = new Dictionary<string, string>
            {
                ["group_id"] = groupId,
                ["after_seq"] = afterSeq.ToString(),
                ["limit"] = limit.ToString()
            };
            JToken resp = await _api.GetAsync("/v2/groups/messages/after", query).ConfigureAwait(false);
            return ParsePage(resp, groupId, true);
        }

        public static ChatMessage MapMessage(JToken node, string chatId, bool isGroup)
        {
            if (node == null) return null;
            string msgType = (string)node["msg_type"];
            var msg = new ChatMessage
            {
                Id = (string)node["id"],
                ChatId = chatId,
                IsGroup = isGroup,
                FromUid = (string)node["from_uid"],
                FromNcuid = (string)node["from_ncuid"],
                FromName = (string)node["from_name"],
                Body = (string)node["body"],
                MsgType = string.IsNullOrEmpty(msgType) ? "text" : msgType,
                MediaUrl = (string)node["media_url"],
                ThumbUrl = (string)node["thumb_url"],
                OriginalUrl = (string)node["original_url"],
                DurationMs = (int?)node["duration_ms"] ?? 0,
                CreatedAt = (long?)node["created_at"] ?? 0,
                GroupSeq = (long?)node["group_seq"] ?? 0,
                SortSeq = (long?)node["sort_seq"] ?? 0,
                BurnAfterSeconds = (int?)node["burn_after_seconds"] ?? 0,
                LocalState = "sent"
            };
            return msg;
        }

        private static MessagePage ParsePage(JToken resp, string chatId, bool isGroup)
        {
            var page = new MessagePage();
            if (resp == null) return page;

            page.HasMore = (bool?)resp["has_more"] ?? false;
            page.NextGroupSeq = (long?)resp["next_group_seq"] ?? 0;
            page.ServerGroupSeq = (long?)resp["server_group_seq"] ?? 0;
            page.NextBeforeCreatedAt = (long?)resp["next_before_created_at"] ?? 0;
            page.NextBeforeId = (string)resp["next_before_id"];

            var arr = resp["messages"] as JArray;
            if (arr == null) return page;
            foreach (JToken node in arr)
            {
                ChatMessage msg = MapMessage(node, chatId, isGroup);
                if (msg != null && !string.IsNullOrEmpty(msg.Id)) page.Messages.Add(msg);
            }
            return page;
        }

        private static void AppendItems<T>(List<T> target, JToken array)
        {
            var arr = array as JArray;
            if (arr == null) return;
            var serializer = Newtonsoft.Json.JsonSerializer.Create(JsonSettings.Read);
            foreach (JToken node in arr)
            {
                T item = node.ToObject<T>(serializer);
                if (item != null) target.Add(item);
            }
        }
    }

    /// <summary>媒体地址可能是相对路径，服务端不保证绝对地址，这里统一补源。</summary>
    public static class MediaUrl
    {
        public static string Resolve(string origin, string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            string trimmed = url.Trim();
            if (trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                trimmed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                return trimmed;
            }
            if (string.IsNullOrEmpty(origin)) return trimmed;
            if (!trimmed.StartsWith("/")) trimmed = "/" + trimmed;
            return origin.TrimEnd('/') + trimmed;
        }
    }
}
