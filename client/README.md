# OldChat（旧聊）

兼容 **Android 2.3+** 的即时通讯系统：Android 客户端 + Go 服务端（HTTP API + WebSocket，默认 SQLite）。

## 目录结构

```
├── app/            Android 客户端（Java + android.support，未迁移 AndroidX）
├── server/         Go 后端（cmd/api 主服务 + internal 模块）
│   ├── mail_project/   Python 邮件发送工具（SMTP 凭据走环境变量）
│   └── lua_apps/       服务端 Lua 应用示例
├── build.gradle / settings.gradle / gradlew   Gradle 工程文件
```

## 核心能力

- 私聊、群聊、媒体消息、引用、转发、撤回、搜索与本地消息缓存
- 账号级 `pts` 增量同步；每群唯一 `group_seq` 消息水位；群成员 `member_version`
- WebSocket 实时推送、断线补偿、未读校准（含 Android 2.3 Keep-Alive 兼容处理）
- 频道、动态、机器人按钮消息（message-token 审核机制）
- 更新双通道：`update.json`（正式版）+ `dev_update.json`（开发模式测试版）

## 客户端构建

```bash
./gradlew :app:assembleDebug          # Debug
./gradlew :app:assembleRelease        # Release（无 keystore.properties 时输出未签名 APK）
```

要求：JDK 17+ / AGP 8（代码保持 Java 7 语法，由 D8 desugar 支持 minSdk 9）。

可选签名：在项目根目录创建 `keystore.properties`：

```properties
storeFile=keystore/release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

## 服务端构建与运行

```bash
cd server
./build_amd64.sh          # 默认 linux/amd64；GOOS/GOARCH 可覆盖
./run_server.sh           # 本地启动（默认 :8080，SQLite）
```

首次运行前用 `schema.sql` 初始化数据库，或直接启动由服务自动建表。

### 环境变量（敏感凭据一律走环境变量，源码中无任何内置密钥）

| 变量 | 说明 |
|---|---|
| `PORT` | 监听端口（默认 8080） |
| `DATABASE_URL` | SQLite 路径（默认 `server/metrochat.db`） |
| `JWT_SECRET` | JWT 签名密钥（≥32 字符，未设置时自动生成并持久化） |
| `OSS_ENABLED` / `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` / `OSS_BUCKET_NAME` | 阿里云 OSS 对象存储（未配置时 OSS 功能自动关闭） |
| `SILICONFLOW_API_KEY` | 语音转文字（ASR）API Key（未配置时 ASR 功能关闭） |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_AUTH_CODE` | 邮件发送（mail_project 使用） |
| `FILES_BASE_URL` | 文件服务基础地址 |

## 邮件工具

```bash
cd server/mail_project
SMTP_USER=你的邮箱 SMTP_AUTH_CODE=你的授权码 python3 send_code.py <email> <subject> <content>
```

## 协议与二次开发文档

见 `server/` 下：`API_STANDARD.md`、`WS_PROTOCOL.md`、`CLIENT_DEVELOPMENT_GUIDE.md`、`AUTH_LOGIN_EXTERNAL_API.md`、`NON_ADMIN_ROUTES.md`、`LUA_CIP_DEVELOPMENT_GUIDE.md`、`OSS_INTEGRATION.md` 等。
