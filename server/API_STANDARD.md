# Oldchat API 开发文档与接口规范

Oldchat 提供基于 HTTP/JSON 的客户端与第三方接口。当前系统主推 `/v2` 接口（支持加密网关传输），同时兼容 `/v1` 传统接口。

---

## 1. 基础规范

### 1.1 数据格式与请求头

请求与响应默认使用 UTF-8 编码的 JSON 格式：

```http
Content-Type: application/json; charset=utf-8
Accept: application/json
```

字段命名统一使用小写下划线（snake_case），例如 `created_at`、`from_uid`、`group_id`。

### 1.2 认证方式

需要登录态的接口在请求头中携带 Bearer Token：

```http
Authorization: Bearer <access_token>
```

### 1.3 错误响应结构

请求发生错误时，服务端返回如下 JSON 结构：

```json
{
  "error": "error_code",
  "code": "error_code",
  "message": "具体错误原因说明"
}
```

常见状态码：
- `200`：请求成功
- `400`：参数缺失或格式不合法
- `401`：未登录、Token 无效或已过期
- `403`：权限不足（如非群成员、被封禁或禁言）
- `404`：目标用户/群组/消息不存在
- `413`：上传内容超出大小限制
- `429`：请求过于频繁（限流）
- `500`：服务端处理异常

---

## 2. /v2 网关与加密请求

新版 Android 客户端默认将业务请求封装并通过 `POST /v2/gateway` 发送，以减少多端点暴露并支持统一链路加密。

### 2.1 网关请求参数

```json
{
  "m": "POST",
  "p": "/v2/groups/message/send",
  "q": "group_id=GRP-123456",
  "b": {
    "group_id": "GRP-123456",
    "body": "消息内容",
    "msg_type": "text"
  }
}
```

- `m`：真实 HTTP 方法（`GET` / `POST` / `PUT` / `DELETE`）
- `p`：内部业务路径（如 `/v2/direct/send`）
- `q`：URL Query 参数字符串（可选）
- `b`：POST/PUT 的请求 Body（JSON 对象）

### 2.2 网关响应格式

网关外层 HTTP 状态码为 200，内部实际响应状态码和内容封装在返回体中：

```json
{
  "code": 200,
  "body": {
    "id": "msg_123456",
    "group_id": "GRP-123456",
    "created_at": 1786556788
  }
}
```

---

## 3. 常用接口列表

### 3.1 账号与认证

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v1/auth/register` | `POST` | 用户注册 |
| `/v1/auth/login` | `POST` | 登录，获取 `access_token` 与 `refresh_token` |
| `/v1/auth/refresh` | `POST` | 刷新登录令牌 |
| `/v1/auth/logout` | `POST` | 退出登录 |
| `/v1/auth/password/reset` | `POST` | 邮箱验证码重置密码 |

### 3.2 私聊与群聊消息

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/direct/send` | `POST` | 发送私聊消息（文字、图片、语音、红包等） |
| `/v2/direct/messages/v2` | `GET` | 拉取私聊历史消息（游标分页） |
| `/v2/direct/read` | `POST` | 上报私聊已读状态 |
| `/v2/groups/message/send` | `POST` | 发送群聊消息 |
| `/v2/groups/messages/v2` | `GET` | 拉取群聊历史消息 |
| `/v2/groups/messages/after` | `GET` | 增量拉取指定 seq 之后的群消息 |
| `/v2/groups/read` | `POST` | 同步群聊已读进度 |
| `/v2/updates/difference` | `POST`/`GET` | 增量游标同步（离线消息与未读状态） |

### 3.3 好友管理

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/friends` | `GET` | 获取好友列表与在线状态 |
| `/v2/friends/request` | `POST` | 发送添加好友申请 |
| `/v2/friends/requests` | `GET` | 获取收到的好友申请 |
| `/v2/friends/respond` | `POST` | 同意或拒绝好友申请 |
| `/v2/friends/remark` | `POST` | 设置好友备注 |
| `/v2/friends/delete` | `POST` | 删除好友 |

### 3.4 群组管理

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/groups/list` | `GET` | 获取我加入的群聊列表 |
| `/v2/groups/create` | `POST` | 创建新群聊 |
| `/v2/groups/members` | `GET` | 查看群成员列表 |
| `/v2/groups/join` | `POST` | 申请加入群聊 |
| `/v2/groups/invite` | `POST` | 邀请用户入群 |
| `/v2/groups/settings` | `POST` | 修改群配置（禁言、验证方式等） |
| `/v2/groups/announcement` | `POST` | 发布群公告 |
| `/v2/groups/kick` | `POST` | 移出群成员（仅群主/管理员） |
| `/v2/groups/leave` | `POST` | 主动退出群聊 |
| `/v2/groups/dissolve` | `POST` | 解散群聊（仅群主） |

### 3.5 频道功能

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/channels/discover` | `GET` | 浏览公开频道 |
| `/v2/channels/subscribe` | `POST` | 订阅频道 |
| `/v2/channels/unsubscribe` | `POST` | 取消订阅 |
| `/v2/channels/posts/after` | `GET` | 拉取频道新帖子 |
| `/v2/channels/posts/send` | `POST` | 发布频道帖子（需发布权限） |
| `/v2/channels/read` | `POST` | 上报频道阅读进度 |

### 3.6 个人动态 (Moments)

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/moments/feed` | `GET` | 获取好友动态时间线 |
| `/v2/moments/user` | `GET` | 查看指定用户的动态主页 |
| `/v2/moments` | `POST` | 发布新动态 |
| `/v2/moments/delete` | `POST` | 删除动态 |
| `/v2/moments/like` | `POST` | 点赞动态 |
| `/v2/moments/comment` | `POST` | 发表评论 |

### 3.7 个人中心与日常功能

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/users/profile` | `GET` | 查看用户公开资料 |
| `/v2/me/profile` | `POST` | 修改昵称、个性签名 |
| `/v2/me/avatar` | `POST` | 更换头像 |
| `/v2/me/cover` | `POST` | 更换主页背景图 |
| `/v2/me/checkin` | `POST` | 每日签到 |
| `/v2/me/scratch` | `GET` | 查询每日刮刮乐状态 |
| `/v2/me/scratch` | `POST` | 参与每日刮刮乐开奖 |
| `/v2/me/presence` | `POST` | 心跳上报在线状态 |

### 3.8 媒体与文件下载

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v2/files/check` | `POST` | 大文件秒传检查（基于 SHA-256） |
| `/v2/files/upload` | `POST` | 上传聊天文件与视频 |
| `/v2/files/download/{fileID}` | `GET` | 下载文件（支持 Range 断点续传） |
| `/v2/resources/upload` | `POST` | 上传文件至资源大厅 |
| `/v2/resources/download/{itemID}` | `GET` | 下载资源大厅文件 |
| `/v1/music/plaza` | `GET` | 浏览音乐广场曲库 |

---

## 4. WebSocket 实时连接 (`/v1/ws`)

### 4.1 连接地址

```http
GET /v1/ws?token=<access_token>&device_id=<device_id>
Upgrade: websocket
Connection: Upgrade
```

### 4.2 下行事件格式

当有新消息或状态变化时，服务端通过 WebSocket 下发 JSON 事件：

```json
{
  "type": "new_message",
  "pts": 10842,
  "data": {
    "id": "msg_123456",
    "chat_type": "group",
    "group_id": "GRP-123456",
    "from_uid": "USR-888888",
    "body": "消息内容",
    "created_at": 1786556788
  }
}
```

- **心跳维持**：客户端建议每 25 秒发送 `{"type":"ping"}`，服务端会回复 `{"type":"pong"}`；
- **重连同步**：网络断开重连后，客户端带上本地最新的 `pts` 调用 `POST /v2/updates/difference` 拉取离线期间的所有增量消息。
