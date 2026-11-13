using System;
using System.Collections.Generic;
using Newtonsoft.Json;

namespace OldChat.Core.Protocol
{
    public sealed class SelfUser
    {
        [JsonProperty("id")] public string Id { get; set; }
        [JsonProperty("uid")] public string Uid { get; set; }
        [JsonProperty("ncuid")] public string Ncuid { get; set; }
        [JsonProperty("username")] public string Username { get; set; }
        [JsonProperty("display_name")] public string DisplayName { get; set; }
        [JsonProperty("user_title")] public string UserTitle { get; set; }
        [JsonProperty("avatar_url")] public string AvatarUrl { get; set; }
        [JsonProperty("signature")] public string Signature { get; set; }
        [JsonProperty("cover_url")] public string CoverUrl { get; set; }
        [JsonProperty("coin_balance")] public int CoinBalance { get; set; }
        [JsonProperty("reputation_score")] public int ReputationScore { get; set; }
        [JsonProperty("ban_count")] public int BanCount { get; set; }

        [JsonIgnore]
        public string DisplayText
        {
            get
            {
                if (!string.IsNullOrEmpty(DisplayName)) return DisplayName;
                if (!string.IsNullOrEmpty(Username)) return Username;
                return Uid ?? string.Empty;
            }
        }
    }

    public sealed class AuthResult
    {
        [JsonProperty("access_token")] public string AccessToken { get; set; }
        [JsonProperty("refresh_token")] public string RefreshToken { get; set; }
        [JsonProperty("user")] public SelfUser User { get; set; }
    }

    public sealed class FriendItem
    {
        [JsonProperty("id")] public string Id { get; set; }
        [JsonProperty("uid")] public string Uid { get; set; }
        [JsonProperty("ncuid")] public string Ncuid { get; set; }
        [JsonProperty("username")] public string Username { get; set; }
        [JsonProperty("display_name")] public string DisplayName { get; set; }
        [JsonProperty("remark_name")] public string RemarkName { get; set; }
        [JsonProperty("user_title")] public string UserTitle { get; set; }
        [JsonProperty("avatar_url")] public string AvatarUrl { get; set; }
        [JsonProperty("is_online")] public bool IsOnline { get; set; }
        [JsonProperty("presence_status")] public string PresenceStatus { get; set; }
        [JsonProperty("friend_added_at")] public long FriendAddedAt { get; set; }
        [JsonProperty("migrated")] public bool Migrated { get; set; }

        [JsonIgnore]
        public string ShowName
        {
            get
            {
                if (!string.IsNullOrEmpty(RemarkName)) return RemarkName;
                if (!string.IsNullOrEmpty(DisplayName)) return DisplayName;
                if (!string.IsNullOrEmpty(Username)) return Username;
                return Uid ?? string.Empty;
            }
        }
    }

    public sealed class GroupItem
    {
        [JsonProperty("group_id")] public string GroupId { get; set; }
        [JsonProperty("name")] public string Name { get; set; }
        [JsonProperty("avatar_url")] public string AvatarUrl { get; set; }
        [JsonProperty("member_count")] public int MemberCount { get; set; }
        [JsonProperty("message_seq")] public long MessageSeq { get; set; }
        [JsonProperty("role")] public short Role { get; set; }
        [JsonProperty("global_mute")] public bool GlobalMute { get; set; }
        [JsonProperty("announcement")] public string Announcement { get; set; }
        [JsonProperty("migrated")] public bool Migrated { get; set; }
    }

    /// <summary>统一的消息模型，群聊与私聊共用，群聊有 GroupSeq，私聊没有。</summary>
    public sealed class ChatMessage
    {
        public string Id { get; set; }
        public string ChatId { get; set; }
        public bool IsGroup { get; set; }
        public string FromUid { get; set; }
        public string FromNcuid { get; set; }
        public string FromName { get; set; }
        public string Body { get; set; }
        public string MsgType { get; set; }
        public string MediaUrl { get; set; }
        public string ThumbUrl { get; set; }
        public string OriginalUrl { get; set; }
        public int DurationMs { get; set; }
        public long CreatedAt { get; set; }
        public long GroupSeq { get; set; }
        public long SortSeq { get; set; }
        public int BurnAfterSeconds { get; set; }

        /// <summary>本地状态：sending / sent / failed，仅用于界面。</summary>
        public string LocalState { get; set; }
        public string LocalError { get; set; }

        [JsonIgnore]
        public DateTime LocalTime
        {
            get { return ChatTime.FromUnix(CreatedAt); }
        }
    }

    public sealed class MessagePage
    {
        public List<ChatMessage> Messages { get; set; }
        public bool HasMore { get; set; }
        public long NextBeforeCreatedAt { get; set; }
        public string NextBeforeId { get; set; }
        public long NextGroupSeq { get; set; }
        public long ServerGroupSeq { get; set; }

        public MessagePage()
        {
            Messages = new List<ChatMessage>();
        }
    }

    public static class ChatTime
    {
        private static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

        public static DateTime FromUnix(long seconds)
        {
            if (seconds <= 0) return DateTime.Now;
            return Epoch.AddSeconds(seconds).ToLocalTime();
        }

        public static long ToUnix(DateTime time)
        {
            return (long)(time.ToUniversalTime() - Epoch).TotalSeconds;
        }

        /// <summary>会话列表用的相对时间。</summary>
        public static string Friendly(DateTime time)
        {
            DateTime now = DateTime.Now;
            if (time.Date == now.Date) return time.ToString("HH:mm");
            if (time.Date == now.Date.AddDays(-1)) return "昨天";
            if ((now.Date - time.Date).TotalDays < 7) return WeekdayName(time.DayOfWeek);
            if (time.Year == now.Year) return time.ToString("MM-dd");
            return time.ToString("yyyy-MM-dd");
        }

        private static string WeekdayName(DayOfWeek day)
        {
            switch (day)
            {
                case DayOfWeek.Monday: return "周一";
                case DayOfWeek.Tuesday: return "周二";
                case DayOfWeek.Wednesday: return "周三";
                case DayOfWeek.Thursday: return "周四";
                case DayOfWeek.Friday: return "周五";
                case DayOfWeek.Saturday: return "周六";
                default: return "周日";
            }
        }
    }

    /// <summary>群成员。消息本身不带头像，成员表是拿头像和昵称的唯一来源。</summary>
    public sealed class GroupMemberItem
    {
        [JsonProperty("uid")] public string Uid { get; set; }
        [JsonProperty("display_name")] public string DisplayName { get; set; }
        [JsonProperty("username")] public string Username { get; set; }
        [JsonProperty("avatar_url")] public string AvatarUrl { get; set; }
        [JsonProperty("role")] public short Role { get; set; }

        public string ShowName
        {
            get
            {
                if (!string.IsNullOrEmpty(DisplayName)) return DisplayName;
                if (!string.IsNullOrEmpty(Username)) return Username;
                return Uid ?? string.Empty;
            }
        }
    }
}
