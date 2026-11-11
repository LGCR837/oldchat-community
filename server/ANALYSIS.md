# OldChat 服务端架构分析

分析时间：2026-11-11
代码基线：初始导入（commit `cac8271`）
目的：为社区版续命（自托管 fork）梳理现状、依赖与改造点。

---

## 1. 概览

| 项 | 值 |
|---|---|
| Go module | `metrochat`（go 1.25，实测 go1.26.3 可编译） |
| 代码规模 | 274 个 `.go` 文件，60,544 行 |
| 路由 | `api.go` 注册 443 处，`registerV1Routes` 双前缀挂载，运行期约 636 条模板 + 管理台 53 条 |
| 数据库 | SQLite（`modernc.org/sqlite` 纯 Go 驱动），合并去重 75 张表 |
| 存储 | 本地 `uploads/`，可选阿里云 OSS / 雨云 S3 同步 |
| 实时 | `gorilla/websocket` 单机内存 Hub |
| 前端 | 后台管理台是服务端 `html/template` 渲染，非 SPA；客户端另有 Android/Tauri/Web |
| 文档 | 10 份 md，4177 行，其中 `CLIENT_DEVELOPMENT_GUIDE.md` 2458 行是客户端行为规范 |

### 构建状态（实测）

```
GOOS=linux  GOARCH=amd64  go build ./cmd/api   → 成功，26.7 MB
GOOS=windows             go build ./cmd/api   → 失败
go vet ./...（linux 目标）                      → 通过
```

Windows 失败原因：`internal/http/monitor.go` 使用 `syscall.Getrusage` / `RUSAGE_SELF`，
`monitor_tui.go` 使用 `SYS_IOCTL` / `TIOCGWINSZ`，两个文件都没有 `//go:build` 标签做平台隔离。
结论：**当前代码只能在 Linux/macOS 上编译与跑测试，Windows 开发机无法本地起服务**。
这是社区版的第一个工程阻塞点，补两个 `_unix.go` / `_windows.go` 分文件即可解决。

---

## 2. 运行架构

### 2.1 进程模型

单进程单体：

```
cmd/api/main.go
  ├─ config.Load()            环境变量 + settings.json
  ├─ data.OpenDB()            SQLite + initSchema（自动建表/补列）
  ├─ httpapi.New(cfg, db)     构造 40+ 个 Store、路由、限流器、WS Hub
  ├─ 后台 goroutine
  │    ├─ pruneEvents()       14 天：account_events + group_events（启动 + 每 6h）
  │    ├─ 启动后一次 48h 剪裁  account_events（api.go:187）
  │    ├─ 焚毁清理            direct/group 各 2 秒一轮
  │    ├─ 表单结果清理        每分钟
  │    └─ TUI 监控面板
  └─ http.Server（Read/Write 5min，Idle 60s）
```

注意：`main.go` 注释写 14 天保留，但 `api.go:193` 在启动 30 秒后又按 **48 小时** 剪裁一次
`account_events`，实际生效保留期约 48 小时。两处常量未统一。

### 2.2 路由拓扑

```
/v1      → registerV1Routes（196 条）
/v1/v1   → registerV1Routes（同一函数再挂一次，兼容"baseURL 和 path 各带一次 /v1"的老客户端）
/v2      → registerV2Routes（97 条）+ v2SignMiddleware
根级     → 147 条：OAuth/OIDC、落地页、下载、审核台 /admins、管理 API
```

v1 与 v2 不是版本演进关系，而是**加密外壳的差异**：

| | v1 | v2 |
|---|---|---|
| 中间件 | `secureMiddleware` → `authMiddleware` | `secureMiddleware` → `authMiddleware` → `v2SignMiddleware` |
| 相同路径 | 79 条共享同一 handler | 同 |
| 独有 | 104 条（全部 auth、WS、资源广场、音乐、法院、AI、Lua……） | 11 条（gateway、unread、files/check、download） |

**新客户端仍必须走 `/v1`**：认证、WebSocket、绝大多数业务接口只在 v1 注册。

`/v2/gateway` 是一个折叠通道：把 `{m, p, q, b}` 还原成真实请求再 `dispatchV2Handler` 直接调用 handler，
绕过路由表。因此存在"表外路由"——`/v2/reports/user`、`/v2/feedback` 在网关里可达但没注册直连路径。
网关的攻击面大于路由表，安全评审不能只看 `api.go`。

### 2.3 鉴权链

普通用户：

```
POST /v1/auth/handshake   ECDH P-256 → 派生 AES-256-CBC + HMAC-SHA256 会话密钥 → sid
       ↓ X-Session / X-Auth / X-Enc: 1
POST /v1/auth/login       argon2id 校验 → HS256 JWT（access 1h）+ 旋转 refresh token（30d）
       ↓ Authorization: Bearer
authMiddleware            算法钉死 HS256 / issuer 兼容列表 / token_version 窗口 / 封禁检查
       ↓ /v2 额外
v2SignMiddleware          X-Session 必须存在；X-Sign = HMAC(MacKey, METHOD\nPATH\nTS\nNONCE)，±300s
                          X-Device-Id 需属于当前用户；两个开关默认关闭（灰度）
```

三套互相隔离的会话体系：

| 会话 | Cookie | 凭据 | 存储 | TTL |
|---|---|---|---|---|
| 管理员 | `admin_session` | 配置里的**明文密码** | 内存 map | 12h |
| 审核员 | `moderator_session` | `moderators` 表 argon2id | 内存 map | 12h |
| 频道网页 | `oldchat_channel_session` | publisher token（SHA-256） | 内存 map | 12h |
| 普通用户 | 无 | JWT + DB refresh | 无状态 | 1h / 30d |

三者都不读 `Authorization`，`authMiddleware` 也不读 cookie，隔离干净。

另有自建 OAuth 2.1 / OIDC 提供方（Ed25519 签名，强制 PKCE S256，授权码 + refresh 轮换，
无 password grant / 无 implicit / 无 DCR），client 只能由管理员创建。

### 2.4 值得注意的实现风险

| 风险 | 位置 | 影响 |
|---|---|---|
| `readThrottle` 是无锁 map，HTTP handler 并发读写 | `api.go:186`、`direct_handlers.go:806` | 并发已读会触发 Go runtime `concurrent map writes` **fatal，整个进程退出** |
| ECDH 会话永不回收：`SessionStore.PruneOlderThan` 无调用点 | `internal/secure/session_store.go:58` | 每次 handshake 永久占内存；旧 sid 永久有效 |
| `/v1/auth/handshake` 无限流 | `api.go:591` | 可被用来放大上一条 |
| 限流器 `buckets` 只增不删 | `internal/ratelimit/limiter.go` | 长跑内存增长；按 identifier 分桶的键空间可被撑爆 |
| 管理员密码默认 `admin/admin123456`，明文比对，`/v1/admin/login` 把密码当 API 密钥 | `config.go:84`、`admin_api_auth.go` | 生产误用即后台沦陷 |
| 管理写操作只靠 `SameSite=Lax` 防 CSRF，不校验 Origin | `admin_handlers.go:948` | 同站边界情况可绕 |
| token_version 容忍最近 10 代 | `auth_middleware.go:120` | 踢人/改密后旧 token 仍有最多 9 个登录周期的窗口 |

---

## 3. 数据层

### 3.1 schema 机制

两个入口，分工不同：

- `schema.sql`（391 行，29 表）：**只在库里不存在 `users` 表时**整份执行，用于全新库 bootstrap。
- `internal/data/db.go`（1180 行，59 表 + 约 90 处 `ALTER` 补列 + 85 处建索引）：已有库的增量迁移。

没有迁移版本表，没有 `PRAGMA user_version`。`ensureMessageColumns` 与 `ensureExtraSchema`
内部所有 DDL 错误都是 `_ =` 丢弃，schema 漂移静默发生；只有
`ensureIdentitySchema` / `ensureOAuthSchema` / `ensureGroupMessageSequenceSchema` 失败会拒绝启动。

由此产生两处"双写"约定：`file_assets`、`oauth_*`、`bot_permits`、`channel_api_permits`、
`ai_token_quotas`、`crash_reports` 在两处都有定义，改结构必须同时改。

### 3.2 表清单（75 张，按域）

| 域 | 表 |
|---|---|
| 账号身份（8） | users, refresh_tokens, oauth_clients, oauth_authorization_requests, oauth_authorization_codes, oauth_grants, oauth_access_tokens, oauth_refresh_tokens |
| 设备封禁（7） | user_devices, user_login_devices, banned_devices, banned_users, banned_groups, user_ban_logs, ban_appeals |
| 社交（2） | friend_requests, friends（双向两行） |
| 私聊（2） | direct_threads, direct_messages |
| 群聊（7） | groups, group_members, group_invitations, group_join_requests, group_messages, group_message_sequences, group_events |
| 频道（7） | channels, channel_sequences, channel_subscriptions, channel_posts, channel_post_reactions, channel_events, channel_api_permits |
| 动态（3） | moments, moment_likes, moment_comments |
| 资源（5） | resource_sections, resource_items, resource_likes, resource_comments, resource_reports |
| 表情/音乐/收藏（8） | emoji_plaza_items, music_plaza_items/likes/comments/play_logs, user_favorites, user_playlists, user_playlist_songs |
| 经济（7） | external_coin_transfers, red_packets, red_packet_claims, user_daily_checkins, user_daily_scratches, title_catalog, ai_token_quotas |
| 治理（10） | user_reports, group_reports, public_court_cases/votes/statements/discussions, moderators, moderator_action_logs, bug_reports, crash_reports |
| 机器人（3） | bot_permits, button_callbacks, button_responses |
| 通知事件（3） | system_notifications, account_events, user_pts_cursor |
| 文件/插件（2） | file_assets, cip_store_items |
| 注销（1） | deleted_account_cooldowns |

### 3.3 缺失与错引

| 问题 | 位置 | 后果 |
|---|---|---|
| `daily_checkin_wall_posts / _likes / _comments` 三表**全仓无 DDL**，但打卡墙接口全量读写 | `checkin_wall_store.go`、`checkin_wall_interaction_store.go` | `/me/checkin/wall*` 全部 500 |
| `EnsureSystemUser` 写 `users.verified` 列，但 schema 与迁移都没有该列 | `db.go:1165`，错误被 `_ =` 吞 | 新库 SYSTEM 用户建不出来 → 欢迎语私聊 FK 失败 |
| `CountFriends` 查 `friendships` 表，实际表名是 `friends` | `user_store.go:798` | 管理端好友数恒为 0 |
| `ai_token_quotas` schema 默认 500 万，代码显式插 0 | `ai_quota_store.go` | 新用户 AI 额度为 0 |

### 3.4 SQLite 配置与瓶颈

```
file:<db>?_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)
         &_pragma=synchronous(NORMAL)&_pragma=foreign_keys(ON)&_pragma=temp_store(MEMORY)
MaxOpenConns = MaxIdleConns = 16，ConnMaxLifetime 30min
```

风险点：

1. **单写者**：所有写请求串行化在一把写锁上，`busy_timeout=10s` 意味一次长事务可让发消息挂 10 秒。
2. **未设 `_txlock=immediate`**：DEFERRED 事务读后升级写锁时会直接得到 `SQLITE_BUSY_SNAPSHOT`，busy handler 不生效。
   只有 `direct` / `group_message` / `group` 三个 store 做了 busy 重试，`public_court` / `oauth` / `red_packet` / `channel` 没做。
3. **无 checkpoint / optimize / ANALYZE**：WAL 可能持续增长，统计信息缺失。
4. **启动写放大**：每次启动跑多条全表 UPDATE（AI 额度、coin_balance、reputation、ban_count、群发言限制）。
5. **时间戳语义混用**：DB 列多为 UTC 文本（`CURRENT_TIMESTAMP`），但部分写入传 `time.Time`
   （现代驱动绑 `t.String()` 带 `+0800 CST`），在 +08 时区部署下封禁/冷却会偏移 8 小时。
6. **分页**：v1 历史接口仍用 `LIMIT/OFFSET`，深页 O(n)；消息搜索用 `LIKE '%kw%'` 全扫。

---

## 4. 实时推送与增量同步

设计取向：**WS 只负责低延迟通知，HTTP 游标负责最终一致**。三条互相正交的增量总线：

| 总线 | 游标 | 作用域 | 每条消息存储成本 |
|---|---|---|---|
| 账号级 PTS | `user_pts_cursor.current_pts` | 单账号私聊 | 1 行 account_events / 接收者 |
| 群级 group_seq | `group_message_sequences.last_seq` | 单群共享 | 1 行 / 群（不按成员扇出） |
| 频道 post_seq + event_seq | `channel_sequences` | 单频道共享 | 1 行 / 频道 |

群消息不进 PTS 是刻意的边界：5000 人群若按成员写事件就是 5000 行，故群走单一序号线，
消息本体在 `group_messages`，非新增事件（目前只有撤回）在 `group_events`，两者共用同一个计数器，
客户端通过 `/groups/events/after` 双表归并获得连续流。

WS Hub 是**纯单机内存态**：`map[userID]map[*Client]` + `map[channelID]map[*Client]`。
慢客户端队列（64）满时静默丢帧，靠客户端 HTTP 补偿。因此：
- 不能多实例部署（会话密钥、typing、presence、nonce 全在进程内）；
- 客户端若不实现 HTTP 补偿就会真丢消息。

心跳：服务端每 54s 发 Ping，读超时 60s 只由 Pong 续期，所以客户端必须回 Pong。

### 已知同步缺口

1. `account_events` 实际只留 48h，而 reset 判定用"差 10000 条"，两个坐标系不匹配 →
   高频用户正常在线也可能被 reset（全量重建）。
2. `group_events` 留 14 天，但群撤回是**物理 DELETE** 消息行 →
   离线超过 14 天的用户会永久保留一条服务端已不存在、也没有 tombstone 的消息。
3. `channel_events` **完全没有裁剪**，无限增长。
4. `PtsStore.Emit` / `GroupEventStore.EmitGroupEvent` 没有 busy 重试：
   写锁竞争时事件落库失败，但 WS 帧已发，离线补齐出现空洞（只能靠 reset 兜底）。
5. 服务端从不推送未读计数，只返回"最多 500 条未读消息本体"，客户端自行推算红点。

---

## 5. 功能域矩阵

| 域 | 核心文件 | 要点 |
|---|---|---|
| 私聊 | `direct_handlers.go`(1009), `direct_store.go` | 必须互为好友；thread 懒创建；`(created_at, id)` 复合游标；阅后即焚（软焚毁墓碑） |
| 群聊 | `group_message_handlers.go`(869), `group_handlers.go`, `group_extra_handlers.go` | 每群唯一 `group_seq`；全员禁言；每日发言上限（仅信誉分 ≤120 生效） |
| 频道 | `channel_handlers.go`, `channel_manage_v2.go`, `channel_web_handlers.go` | 双序列；订阅/免打扰/表情回应；媒体走签名 URL；独立 `/channel-manage` 网页发布后台 |
| 朋友圈 | `moment_handlers.go`(785) | 好友时间线；图片 URL 白名单；发动态按 IP 5s 限流 |
| 资源广场 | `resource_handlers.go`(927) | 分区+条目；按上传者累计配额（默认 10GB）；`RESOURCE_SQUARE_ENABLED` 默认**关闭** |
| 音乐广场 | `music_plaza_handlers.go`(1302) | 歌曲 ≤20MB；封面代理压缩到 1080px 缓存 7 天；周榜/总榜 |
| 表情广场 | `emoji_plaza_handlers.go`(749) | 单图或 ZIP 包（≤30MB / ≤500 项） |
| 红包 | `red_packet_handlers.go`(766) | 群/定向；二倍均值法；事务扣款；领取幂等 |
| 打卡 | `me_checkin_handlers.go`, `checkin_wall_*` | 每日一次；金币奖励当前为 0，声望 +10；打卡墙缺表（见 3.3） |
| 刮刮乐 | `scratch_handlers.go` | 每日 5 槽；权重 40/30/15/10/5 |
| 公共法庭 | `public_court_handlers.go`(900), `public_court_store.go`(1333) | 举报可直接立案；投票需信誉 ≥50；满 10 票锁定 → 管理员二审 → 自动封禁 + 信誉加减 |
| 举报申诉 | `report_handlers.go`, `ban_appeal_handlers.go`(562) | 申诉走 `/igotbanned` 页面，需账密校验 |
| 机器人 | `bot_handlers.go`(925) | 申请→审核→一次性领取 token（30d，只存哈希）；按钮 payload v3，RSA 签 tid |
| 频道开放 API | `channel_api_handlers.go` | publisher token（365d）+ scope 校验 |
| Lua 小程序 | `lua_app_handlers.go`, `cip_store.go` | **服务端没有 Lua 运行时**：只做清单下发、脚本原文 + sha256、assets 静态托管、CIP 包校验与审核。Lua 在 Android 客户端执行（LuaJ 风格） |
| AI 代理 | `ai_proxy_handlers.go` | 上游与模型写死；按 token 预扣 + 结算；SSE 透传 |
| 金币/称号 | `external_coin_handlers.go`, `title_shop_*` | 新用户 10 币；称号全局唯一，购买退旧称号并抵扣 |
| 收藏/歌单 | `favorite_handlers.go`, `playlist_handlers.go` | 歌单云同步，每人 ≤5 个、每单 ≤200 首 |
| 审核后台 | `admin_handlers.go`(3173), `moderation_page.go`(1730) | 服务端模板渲染；11 个审核 tab；可拉取聊天上下文取证 |

---

## 6. 外部依赖与降级

| 依赖 | 作用 | 关闭方式 | 不可用后果 |
|---|---|---|---|
| 阿里云 OSS | 媒体备份 + 直链 + 签名下载 | `OSS_ENABLED=false` 或不配 AK/SK | 全走本地 `uploads/`，功能完整，只是没 CDN |
| 雨云 S3 | 资源广场同步 | 同上 | **上传同步是死代码**（`pushResourceToRainOSS` 无调用者），只有下架删除生效 |
| SMTP 邮件 | 注册/找回验证码、token 审核通知 | 不配 `SMTP_USER` | **注册与找回不可用**；且强依赖宿主机 `python3` 执行 `mail_project/*.py` |
| GeeTest 4.0 | 注册页人机验证 | **不可配**，ID/KEY 硬编码 | 无凭据则注册/找回全挂 |
| Cloudflare Turnstile | 次级人机验证 | `TURNSTILE_SECRET` 为空则 fail-closed | 极验通过则不校验 |
| OpenCode Go AI | `/v1/ai/chat/completions` 唯一上游 | 不配 key | 返回 503，其余功能不受影响 |
| SiliconFlow ASR | 语音转文字 | 不配 key | 503，语音消息本身正常 |
| data_server | 文件推送到独立文件服务 | `DATA_SERVER_BASE_URL` 空 | 仅本地存储。**该模块源码不在本仓库**，构建脚本里的 `../data_server` 也不存在 |
| Google Fonts | 后台字体 | — | 境内加载慢 |

结论：**除"注册/找回密码"和 AI/ASR 外，其余功能都可以零外部依赖跑通**，只是没有对象存储加速。

---

## 7. 去官方化改造清单

### 7.1 阻断级（不改则注册/登录不可用）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 1 | 注册邮箱白名单只允许 `@qq.com` / `@vip.qq.com`（后端 + 注册页 JS 两处） | `auth_handlers.go:726`、`verify_handlers.go`、`oauth_account_pages.go`、`register_page.go:182` | 改为可配置域名列表或全放行 |
| 2 | 极验 CaptchaID/Key 明文硬编码，社区无凭据 | `verify/geetest.go:36-37`、`register_page.go:116`、`forgot_password_page.go:75` | 改环境变量，或换 Turnstile，或允许关闭 |
| 3 | 登录上报的 APK 签名必须是官方证书哈希 | `auth_handlers.go:55-61`、调用点 `:362` | 仅在 `apkSig != ""` 时校验，旧客户端空串放行；自建客户端**不要上报签名**即可绕过，但仍是硬编码门槛 |
| 4 | 邮件发送依赖宿主机 `python3` + `mail_project/` | `internal/mail/sender.go` | 改用 `gomail`（已在 go.mod）原生 SMTP |
| 5 | `FILES_BASE_URL` 换域名后频道媒体引用前缀白名单失效 | `channel_media_handlers.go:136-148` | 改为从 `cfg.FilesBaseURL` 动态生成 |

### 7.2 指向官方站/官方基础设施

| # | 位置 | 内容 |
|---|---|---|
| 6 | `config.go:71,73,176,177` | `files.mcl0.dpdns.org`、`oc.mcl0.dpdns.org`、`cn-sy1.rains3.com/oldchat` 作为默认值 |
| 7 | `file_upload_handlers.go:287,305,331` | 三处 `http://files.mcl0.dpdns.org` 字面量兜底 |
| 8 | `legacy_register_handler.go:16-17` | 旧注册接口返回的引导链接 |
| 9 | `bot_handlers.go:263`、`channel_api_handlers.go:353`、`moderation_handlers.go:1209-1211` | 审核邮件里的领取地址 |
| 10 | `developer_token_docs.go` 9 处 | `/bot-api.md`、`/channel-api.md` 文档中的服务地址与 curl 示例 |
| 11 | `admin_handlers.go:2590,2592` | 后台占位符泄露官方源站 IP |
| 12 | `config.go:97-98` | AI 上游与模型写死，不可配置 |
| 13 | `api.go:334` | 落地页静态目录名写死且拼写错误 `ooldchat-web/assets` |

### 7.3 安全项（社区上线前必须处理）

| 项 | 位置 |
|---|---|
| `JWT_SECRET` 默认值 `default-secret-at-least-32-characters-long`，启动只对 admin 打 WARN | `config.go:222` |
| 默认管理员 `admin/admin123456`，明文、不落库、可用作 API 密钥 | `config.go:84` |
| 极验 secret 明文入源码 | `verify/geetest.go:37` |
| `/v1/external/*` 每请求传账号+密码明文鉴权，无 API Key/签名 | `external_handlers.go:469` |
| `/v2` 签名与设备绑定默认关闭 | `config.go:103-104` |
| 管理员 cookie 部分入口缺 `Secure` | `admin_api_auth.go:30`、`admin_spa_routes.go:75` |
| WS 支持 `?token=` 传 token，会进反代日志 | `ws_handler.go:76` |

---

## 8. 社区版部署形态建议

最小可用（单机、零成本取向）：

```
1 台 Linux VPS（1C1G 可跑小规模）
├─ server_linux_amd64        单二进制
├─ metrochat.db              SQLite
├─ schema.sql                全新库必须随包（OpenDB 会读相对路径或二进制同目录）
├─ settings.json             首次启动自动写 jwt_secret
├─ uploads/                  媒体、密钥、CIP 包
└─ update/                   客户端更新包
```

必需环境变量：

```
PORT=8080
DATABASE_URL=/data/metrochat.db
JWT_SECRET=<32 字节以上随机>
JWT_ISSUER=metrochat
ADMIN_USER / ADMIN_PASSWORD        必须改
OAUTH_ISSUER=https://<自己的域名>   必须是 https，否则配置校验直接失败启动不了
OAUTH_SIGNING_KEY_PATH=oauth_ed25519.key
FILES_BASE_URL=https://<自己的域名> 让文件走主服务
PUBLIC_BASE_URL=https://<自己的域名>
```

需注意的部署约束：

1. `OAUTH_ISSUER` 强制 https 且不能带 userinfo/query/fragment，**没有域名 + TLS 就起不来**（除非改代码放宽）。
2. `schema.sql` 必须与二进制同目录或在 CWD，否则全新库启动失败。
3. 只能用单实例（WS Hub、会话密钥、typing、presence、nonce 全在内存）。
4. 客户端侧要改 baseURL，Android 包需重签名；重签后**不要上报 `apk_signature`**。
5. 缺 `data_server` 模块不影响主服务，文件由主服务的 `/v2/files/download/{id}` 承载。

---

## 9. 建议的后续动作（按投入产出排序）

1. **补 Windows/Unix 构建标签**：`monitor.go` / `monitor_tui.go` 拆平台文件，让开发机可本地起服务、跑测试。
2. **修 3 个必现故障**：打卡墙三表补 DDL、`users.verified` 补列、`friendships` → `friends`。
3. **修 `readThrottle` 竞态**：加 `sync.Mutex` 或换 `sync.Map`，这是会崩进程的问题。
4. **做配置化去官方化**：邮箱域名、极验/Turnstile 开关、文件基址、AI 上游、所有 `oc.mcl0.dpdns.org` 字面量集中到配置。
5. **邮件改原生 SMTP**：去掉 `python3` 依赖，这是低成本高收益的部署简化。
6. **统一事件裁剪窗口**：48h/14d 收敛为单常量，并把 reset 判定改为"时间 + 条数"双条件。
7. **补 `channel_events` 裁剪**、给 `moderator_action_logs` / `bug_reports` / `groups(owner_id)` 补索引。
8. **引入 `schema_migrations` 版本表**：当前静默 DDL 失败的模式在社区多人改动下会持续累积漂移。
