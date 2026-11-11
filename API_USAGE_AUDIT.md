# Oldchat 服务端 API 使用审计

本审计将 `internal/http/api.go` 的实际路由与当前 Android 客户端、WebApp、桌面客户端、CIP 动态请求和仓库内兼容文档交叉比对。

> “当前 Android 未引用”不等于“可以删除”。旧 APK、WebApp、桌面端、管理员后台、外部支付/消息集成和 CIP 动态路径必须分别统计。

## 一、当前 Android 未使用，但明确属于旧版兼容的接口

| 方法 | 路径 | 当前 Android 替代路径 | 判断 |
|---|---|---|---|
| GET | `/v1/direct/messages` | `/v1/direct/messages/v2` | 旧分页接口，保留旧 APK 兼容 |
| GET | `/v1/groups/messages` | `/v1/groups/messages/v2`、`/after` | 旧分页接口，保留旧 APK 兼容 |
| GET | `/v1/moments` | `/v1/moments/v2` | 旧动态分页接口 |
| POST | `/v1/me/devices/cleanup` | 当前使用 `/cleanup-others` | 同处理器别名 |
| GET | `/v1/discover/lua/assets/*` | 当前清单生成 `/lua-assets/*` | 旧/备用 Lua 资源入口 |
| 任意 | `/v1/v1/*` | 当前使用 `/v1/*` | 全量双 `v1` 兼容根路径 |

这些接口不能仅凭当前 1.3.46 客户端未调用而删除。

## 二、当前 Android 未使用，且需要确认是否仍有业务调用

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/auth/direct-create` | 管理员/测试直接创建账号并返回令牌，生产暴露面较大 |
| POST | `/v1/auth/logout` | 当前客户端仅清本地数据，没有撤销 refresh token；应接入客户端而不是直接删除 |
| GET | `/v1/me/bug-reports` | 当前进度页调用 `/reports/bug` |
| GET | `/v1/me/user-reports` | 当前进度页调用 `/reports/user` |
| GET | `/v1/me/group-reports` | 当前 Android 没有对应 GET 调用 |
| GET | `/v1/me/resource-reports` | 当前资源进度页误调不存在的 `/reports/resource` |
| GET | `/v1/reports/group` | 当前 Android 只使用同路径的 POST 举报，不使用 GET |
| POST | `/v1/music/plaza/lyrics` | 当前音乐上传/编辑代码没有调用 |
| GET | `/v1/public-court/cases/{id}/votes` | 当前详情页通过案件详情接口获取数据 |
| GET | `/v1/public-court/cases/{id}/discussions` | 当前详情页通过案件详情接口获取数据 |
| POST | `/v1/chat/completions` | 默认客户端使用 `/ai/chat/completions`；这是 OpenAI 兼容别名，可能被外部 SDK 使用 |

## 三、不能按 Android 未引用判定为旧 API

以下路由服务于其他调用方，应单独管理：

- `/admins/*`：管理员控制面。
- `/external/*`、`/shop/*`、`/coin-tool`：外部消息及金币业务。
- `/app`、`/app-assets/*`：WebApp。
- `/update/*`：客户端动态读取更新 JSON 与 APK 名称。
- `/uploads/*`、`/v1/uploads/*`、`/music/cover/*`：服务端响应动态返回的媒体 URL。
- `/lua-assets/*`：CIP `app.asset()` 动态构造。
- `/landing`、`/report`、`/igotbanned`：网页、举报和封禁申诉流程。

CIP 的 `app.http_get("/path")` 还能动态调用 `/v1` 下任意 GET 路径，因此静态 Java 搜索不是完整使用证据。

## 四、交叉核对发现并已修复的路径不一致

以下问题已经修复：

| 原 Android 请求 | 修复方式 |
|---|---|
| `GET /me/resource-quota` | 客户端改用 `GET /me/resources/quota` |
| `GET /reports/resource` | 客户端改用个人进度接口 `GET /me/resource-reports` |
| `POST /direct/messages/{id}/voice/transcribe` | 客户端改用 `POST /direct/messages/{id}/transcribe` |
| `POST /groups/messages/{id}/voice/transcribe` | 客户端改用 `POST /groups/messages/{id}/transcribe` |
| `POST multipart /music/plaza/update` | 服务端新增所有者限定的名称/封面更新接口 |

仍有一项需确认产品语义：当前普通用户进度页请求 `/reports/bug` 和 `/reports/user`，服务端处理器返回全站最近记录，而 `/me/bug-reports`、`/me/user-reports` 才只返回当前用户记录。

## 五、安全下线前置条件

当前生产主机没有可查询的 journal 请求日志，无法证明任何候选接口为零调用。下线前应：

1. 按“HTTP 方法 + 完整路由模板 + `/v1` 或 `/v1/v1` 前缀”记录调用量。
2. 记录匿名化用户、平台、客户端版本和调用方类型，不记录 token 或请求体。
3. 连续观察至少 90 天和两个客户端发布周期。
4. 对旧 APK、WebApp、桌面端、外部金币/消息集成和管理员后台分别回归。
5. 先增加弃用指标和响应头，再灰度拒绝，最后删除。
