using System;
using OldChat.Core.Protocol;

namespace OldChat.App.ViewModels
{
    /// <summary>消息列表一项。把 ChatMessage 转成界面直接可绑定的形态。</summary>
    public sealed class MessageItem : ObservableObject
    {
        private string _localState;
        private string _localError;

        public ChatMessage Source { get; private set; }
        public bool IsMine { get; private set; }
        public bool IsGroup { get; private set; }
        public string SenderName { get; private set; }
        public string AvatarUrl { get; set; }
        public string AvatarLetter { get; private set; }
        public string DisplayText { get; private set; }
        public string QuoteText { get; private set; }
        public bool HasQuote { get; private set; }
        public string TimeText { get; private set; }
        public string MsgType { get; private set; }
        public string MediaUrl { get; private set; }
        public bool IsMediaMessage { get; private set; }
        public bool ShowTimeHeader { get; set; }
        public string TimeHeaderText { get; set; }
        public bool ShowSenderName { get; private set; }

        /// <summary>官方布局：私聊不显示消息头像，群聊才显示。</summary>
        public bool ShowAvatar
        {
            get { return IsGroup && !IsMine; }
        }

        public string LocalState
        {
            get { return _localState; }
            set
            {
                if (Set(ref _localState, value)) RaiseStatus();
            }
        }

        public string LocalError
        {
            get { return _localError; }
            set
            {
                if (Set(ref _localError, value)) RaiseStatus();
            }
        }

        public bool HasStatus
        {
            get { return !string.IsNullOrEmpty(StatusText); }
        }

        private void RaiseStatus()
        {
            Raise("StatusText");
            Raise("HasStatus");
        }

        public string StatusText
        {
            get
            {
                if (_localState == "sending") return "发送中";
                if (_localState == "failed") return string.IsNullOrEmpty(_localError) ? "发送失败" : "发送失败：" + _localError;
                return string.Empty;
            }
        }

        public MessageItem(ChatMessage message, bool isMine, string senderName, string avatarUrl)
        {
            Source = message;
            IsMine = isMine;
            IsGroup = message.IsGroup;
            MsgType = message.MsgType;
            SenderName = senderName;
            AvatarUrl = avatarUrl;
            AvatarLetter = Initial(senderName);
            _localState = message.LocalState;
            _localError = message.LocalError;

            MessageBody body = MessageBody.Parse(message.Body);
            DisplayText = body.Text;
            if (body.Quote != null)
            {
                HasQuote = true;
                QuoteText = string.IsNullOrEmpty(body.Quote.FromName)
                    ? body.Quote.Text
                    : body.Quote.FromName + "：" + body.Quote.Text;
            }

            ShowSenderName = IsGroup && !isMine;
            TimeText = message.LocalTime.ToString("HH:mm");
            MediaUrl = message.ThumbUrl ?? message.MediaUrl;
            IsMediaMessage = message.MsgType == "image" && !string.IsNullOrEmpty(MediaUrl);

            if (string.IsNullOrEmpty(DisplayText) && IsMediaMessage) DisplayText = "[图片]";
            else if (string.IsNullOrEmpty(DisplayText) && message.MsgType == "voice") DisplayText = "[语音]";
            else if (string.IsNullOrEmpty(DisplayText) && message.MsgType == "file") DisplayText = "[文件]";
        }

        private static string Initial(string name)
        {
            if (string.IsNullOrEmpty(name)) return "?";
            return name.Substring(0, 1).ToUpperInvariant();
        }
    }
}
