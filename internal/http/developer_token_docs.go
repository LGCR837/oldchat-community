package httpapi

import (
	"io"
	"net/http"
)

func writeDeveloperMarkdown(w http.ResponseWriter, content string) {
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = io.WriteString(w, content)
}

func handleBotAPIDocument(w http.ResponseWriter, r *http.Request) {
	writeDeveloperMarkdown(w, botAPIDocument)
}

func handleChannelAPIDocument(w http.ResponseWriter, r *http.Request) {
	writeDeveloperMarkdown(w, channelAPIDocument)
}

const botAPIDocument = `# OldChat 机器人按钮消息开发手册

服务地址：` + "`https://oc.mcl0.dpdns.org`" + `

## 1. 申请与领取 Token

1. 登录官网账号后打开 [机器人 Token 申请页](/bot-apply)。
2. 选择群聊、私聊权限并说明用途。
3. 管理员在审核台审核；通过后回到申请页点击“领取 Token”。
4. Token 只显示一次，有效期 30 天。服务端只保存 SHA-256 摘要，请立即安全保存。

机器人以申请人的 OldChat 账号身份发送消息。向群聊发送前，该账号必须已加入目标群。

## 2. 发送按钮消息

` + "```bash" + `
curl -X POST 'https://oc.mcl0.dpdns.org/v2/buttons/send' \
  -H 'Authorization: Bearer YOUR_MESSAGE_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "to_type": "group",
    "to_id": "GRP-XXXXXXXX",
    "text": "请选择一个操作",
    "buttons": [
      {"text":"确认","action":"send_text","data":"我确认"},
      {"text":"官网","action":"open_url","data":"https://oc.mcl0.dpdns.org"}
    ]
  }'
` + "```" + `

字段：

- ` + "`to_type`" + `：` + "`group`" + ` 或 ` + "`direct`" + `，必须包含在申请 scope 中。
- ` + "`to_id`" + `：群 ID，或用户 UID/NCUID。
- ` + "`text`" + `：消息正文。
- ` + "`buttons`" + `：1–16 个按钮；按钮文字最多 32 字。

按钮 action：

| action | data | 行为 |
| --- | --- | --- |
| ` + "`send_text`" + ` | 预设文字 | 点击者向原会话发送文字 |
| ` + "`reply_msg`" + ` | 预设文字 | 返回引用回复内容 |
| ` + "`open_url`" + ` | HTTP(S) URL | 客户端确认后打开链接 |
| ` + "`send_image`" + ` | 图片 URL | 点击者向原会话发送图片 |
| ` + "`form`" + ` | 合法表单 JSON | 打开表单；表单 10 分钟内有效 |

成功响应包含 ` + "`msg_id`" + ` 以及带签名的按钮数据。

## 3. 拉取按钮响应

` + "```bash" + `
curl 'https://oc.mcl0.dpdns.org/v2/buttons/responses?limit=50&cursor=' \
  -H 'Authorization: Bearer YOUR_MESSAGE_TOKEN'
` + "```" + `

按 ` + "`next_cursor`" + ` 继续增量拉取。响应仅保留 10 分钟，建议每 2–5 秒轮询并持久化游标。每条记录包含点击者 UID、消息 ID、按钮序号、action 和表单结果。

## 4. 常见状态码

- ` + "`400`" + `：JSON、目标或按钮参数无效。
- ` + "`401`" + `：Token 缺失、错误或过期。
- ` + "`403`" + `：scope 不允许，或机器人账号不在目标群。
- ` + "`500`" + `：服务端发送失败。

不要把 Token 放在 URL、前端代码或公开仓库中。
`

const channelAPIDocument = `# OldChat 频道 API 开发手册

服务地址：` + "`https://oc.mcl0.dpdns.org`" + `

## 1. 申请与领取 Publisher Token

1. 登录官网账号后打开 [频道 API Token 申请页](/channel-api/apply)。
2. 按需申请 scope 并填写用途。
3. 审核通过后由申请人回到页面领取 Token。Token 只显示一次，有效期 365 天。
4. 带 ` + "`channel:manage`" + ` scope 的 Token 也可登录 [频道管理后台](/channel-manage)。

可申请 scope：` + "`channel:create`" + `、` + "`channel:post`" + `、` + "`channel:edit`" + `、` + "`channel:delete`" + `、` + "`channel:manage`" + `。

所有 API 请求使用：

` + "```http" + `
Authorization: Bearer YOUR_PUBLISHER_TOKEN
Content-Type: application/json
` + "```" + `

## 2. 创建频道

` + "```bash" + `
curl -X POST 'https://oc.mcl0.dpdns.org/v2/channel-api/channels' \
  -H 'Authorization: Bearer YOUR_PUBLISHER_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"name":"更新频道","handle":"oldchat_updates","description":"自动发布更新","visibility":"public"}'
` + "```" + `

公开频道 handle 必须为 5–32 位小写字母、数字或下划线。

## 3. 发布频道帖子

` + "```bash" + `
curl -X POST 'https://oc.mcl0.dpdns.org/v2/channel-api/posts' \
  -H 'Authorization: Bearer YOUR_PUBLISHER_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"channel_id":"CH-XXXXXXXXXX","body":"新版本已发布","msg_type":"text"}'
` + "```" + `

` + "`msg_type`" + ` 支持 ` + "`text`" + `、` + "`image`" + `、` + "`video`" + `、` + "`resource`" + `；媒体帖子可提供 ` + "`media_url`" + ` 和 ` + "`thumb_url`" + `。

## 4. 编辑与删除

编辑：

` + "```bash" + `
curl -X PATCH 'https://oc.mcl0.dpdns.org/v2/channel-api/posts' \
  -H 'Authorization: Bearer YOUR_PUBLISHER_TOKEN' -H 'Content-Type: application/json' \
  -d '{"channel_id":"CH-XXXXXXXXXX","post_id":"CP-XXXXXXXXXXXXXX","body":"修改后的内容"}'
` + "```" + `

删除：

` + "```bash" + `
curl -X DELETE 'https://oc.mcl0.dpdns.org/v2/channel-api/posts' \
  -H 'Authorization: Bearer YOUR_PUBLISHER_TOKEN' -H 'Content-Type: application/json' \
  -d '{"channel_id":"CH-XXXXXXXXXX","post_id":"CP-XXXXXXXXXXXXXX"}'
` + "```" + `

Token 只能操作其账号拥有或具有发布权限的频道。请勿在浏览器前端、URL 或公开仓库中泄露 Token。
`
