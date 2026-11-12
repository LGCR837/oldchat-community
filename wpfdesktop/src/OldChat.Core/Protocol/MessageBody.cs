using System;
using System.Collections.Generic;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace OldChat.Core.Protocol
{
    /// <summary>
    /// 消息体有两种形态：纯文本就是裸字符串；带引用、@、转发时是
    /// {"v":2,"text":"...","quote":{...},"mentions":[...]}。
    /// 结构定义见服务端 internal/http/message_payload_guard.go。
    /// </summary>
    public sealed class MessageBody
    {
        public bool IsStructured { get; private set; }
        public string Text { get; private set; }
        public string MediaKind { get; private set; }
        public string VoiceText { get; private set; }
        public QuoteRef Quote { get; private set; }
        public List<MentionRef> Mentions { get; private set; }

        private MessageBody()
        {
            Text = string.Empty;
            Mentions = new List<MentionRef>();
        }

        public sealed class QuoteRef
        {
            public string Id { get; set; }
            public string FromUid { get; set; }
            public string FromName { get; set; }
            public string Type { get; set; }
            public string Text { get; set; }
        }

        public sealed class MentionRef
        {
            public string Uid { get; set; }
            public string Ncuid { get; set; }
            public string Name { get; set; }
        }

        public static MessageBody Parse(string raw)
        {
            var body = new MessageBody();
            if (string.IsNullOrEmpty(raw)) return body;

            string trimmed = raw.TrimStart();
            if (!trimmed.StartsWith("{", StringComparison.Ordinal))
            {
                body.Text = raw;
                return body;
            }

            JObject json;
            try
            {
                json = JObject.Parse(trimmed);
            }
            catch (JsonException)
            {
                body.Text = raw;
                return body;
            }

            if (json["text"] == null && json["v"] == null && json["quote"] == null)
            {
                // 用户自己发的 JSON 文本，按纯文本处理。
                body.Text = raw;
                return body;
            }

            body.IsStructured = true;
            body.Text = (string)json["text"] ?? string.Empty;
            body.MediaKind = (string)json["media_kind"];
            body.VoiceText = (string)json["voice_text"];

            var quote = json["quote"] as JObject;
            if (quote != null)
            {
                body.Quote = new QuoteRef
                {
                    Id = (string)quote["id"],
                    FromUid = (string)quote["from_uid"],
                    FromName = (string)quote["from_name"],
                    Type = (string)quote["type"],
                    Text = (string)quote["text"]
                };
            }

            var mentions = json["mentions"] as JArray;
            if (mentions != null)
            {
                foreach (JToken node in mentions)
                {
                    body.Mentions.Add(new MentionRef
                    {
                        Uid = (string)node["uid"],
                        Ncuid = (string)node["ncuid"],
                        Name = (string)node["name"]
                    });
                }
            }

            return body;
        }

        public bool MentionsMe(string uid, string ncuid)
        {
            foreach (MentionRef m in Mentions)
            {
                if (!string.IsNullOrEmpty(uid) && m.Uid == uid) return true;
                if (!string.IsNullOrEmpty(ncuid) && m.Ncuid == ncuid) return true;
            }
            return false;
        }

        /// <summary>发引用消息时构造 v2 结构体。</summary>
        public static string BuildQuoted(string text, ChatMessage quoted, string selfName)
        {
            if (quoted == null) return text ?? string.Empty;

            MessageBody quotedBody = Parse(quoted.Body);
            var payload = new JObject
            {
                ["v"] = 2,
                ["text"] = text ?? string.Empty,
                ["quote"] = new JObject
                {
                    ["id"] = quoted.Id,
                    ["from_uid"] = quoted.FromUid,
                    ["from_ncuid"] = quoted.FromNcuid,
                    ["from_name"] = string.IsNullOrEmpty(quoted.FromName) ? selfName : quoted.FromName,
                    ["type"] = quoted.MsgType ?? "text",
                    ["text"] = Shorten(quotedBody.Text, 96)
                }
            };
            return payload.ToString(Formatting.None);
        }

        private static string Shorten(string value, int limit)
        {
            if (string.IsNullOrEmpty(value)) return string.Empty;
            return value.Length <= limit ? value : value.Substring(0, limit);
        }
    }
}
