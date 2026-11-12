using System;
using System.Net;

namespace OldChat.Core.Net
{
    /// <summary>
    /// 服务端错误体固定为 {"code":"...","error":"..."}，这里统一承载。
    /// </summary>
    public class ApiException : Exception
    {
        public HttpStatusCode StatusCode { get; private set; }
        public string Code { get; private set; }

        public ApiException(HttpStatusCode statusCode, string code, string message)
            : base(message)
        {
            StatusCode = statusCode;
            Code = code ?? string.Empty;
        }

        public bool IsAuthFailure
        {
            get
            {
                return StatusCode == HttpStatusCode.Unauthorized || StatusCode == HttpStatusCode.Forbidden;
            }
        }

        public bool IsSessionInvalid
        {
            get
            {
                return Code == "invalid_session" || Code == "missing_session";
            }
        }

        /// <summary>把服务端 code 翻成一句人能看懂的提示，界面直接显示。</summary>
        public string FriendlyMessage
        {
            get
            {
                switch (Code)
                {
                    case "invalid_credentials": return "账号或密码不正确";
                    case "rate_limited": return "请求太频繁，稍后再试";
                    case "unauthorized_client": return "客户端未获授权";
                    case "user_banned": return "该账号已被封禁";
                    case "device_banned": return "该设备已被封禁";
                    case "invalid_session": return "会话已失效，正在重新握手";
                    case "missing_session": return "缺少会话";
                    case "bad_signature": return "请求签名无效";
                    case "missing_signature": return "缺少请求签名";
                    case "device_mismatch": return "设备与账号不匹配";
                    case "invalid_input": return "输入不合法";
                    case "not_found": return "内容不存在";
                    case "db_error": return "服务端内部错误";
                    default: return string.IsNullOrEmpty(Message) ? "请求失败" : Message;
                }
            }
        }
    }
}
