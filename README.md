# OldChat 社区版

旧聊（OldChat）社区维护的单仓，包含 Android 客户端、桌面端与服务端。
上游初始版本来自开源首发提交，社区版在其之上做去官方化与自部署适配。

## 目录

```
client/   Android 客户端（Gradle 根工程，:app 为主模块）
          desktop/   桌面端 JavaFX 工程（独立 Gradle 构建）
server/   Go 服务端，含 webapp/ 网页版与后台静态资源
```

## 客户端

要求 JDK 17 及以上与 Android SDK。首次构建前在 `client/` 下创建 `local.properties`：

```
sdk.dir=<Android SDK 路径>
```

```
cd client
./gradlew assembleDebug
```

桌面端独立构建：

```
cd client/desktop
./gradlew build
```

## 服务端

要求 Go 1.25 及以上。Windows 与 Linux 均可编译运行。

```
cd server
go build -o server_bin ./cmd/api
./server_bin
```

默认监听 `8082`（`PORT` 环境变量或 `server/settings.json` 的 `port` 可覆盖）。

首次启动前在 `server/` 下创建 `settings.json`，该文件含管理员密码与 JWT 密钥，已被忽略，不要提交：

```json
{
  "admin_user": "admin",
  "admin_password": "自行设置",
  "port": "8082",
  "oss_enabled": false,
  "oauth_enabled": false
}
```

未填 `jwt_secret` 时服务端会自动生成并写回该文件。

## 社区版与官方的差异

- 不接对象存储，媒体文件以 `server/uploads/` 为权威存储，地址跟随 `public_base_url`，未配置时输出同源相对路径。
- OAuth 2.1 / OIDC 提供方默认关闭，`/oauth/*` 不注册。需要时设 `OAUTH_ENABLED=1` 与 `OAUTH_ISSUER`（绝对 HTTPS）后重启。
- 注册不限邮箱域名，仅校验格式。
- 下载源只有源服务器自身，不再回退官方 CDN 与雨云 S3。

## 仓库历史

`client/` 与 `server/` 各自来自独立仓库，已完整并入本仓历史：

- `client-final` 指向客户端并入前的最后一次提交
- `server-final` 指向服务端并入前的最后一次提交

两个旧仓库不再使用，后续提交统一在本仓进行。
