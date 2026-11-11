# OldChat Community

[https://github.com/mcl0z/OldChat-IM](旧聊 Oldchat) 社区维护的分支

## 目录

```
client/   Android 客户端
server/   Go 服务端
```

## 客户端

要求 JDK 17 及以上与 Android SDK，兼容 Android 2.3+


## 服务端

要求 Go 1.25 及以上。Windows 与 Linux 均可编译运行。位于`./cmd/api`。

```
cd server
go build -o server_bin ./cmd/api
./server_bin
```

默认监听 `8082`，可通过 `PORT` 环境变量或 `server/settings.json` 的 `port` 覆盖。

首次启动前在 `server/` 下创建 `settings.json`，该文件含管理员密码与 JWT 密钥

```json
{
  "admin_user": "admin",
  "admin_password": "自行设置",
  "port": "8082",
  "oss_enabled": false,
  "oauth_enabled": false
}
```

未填 `jwt_secret` 时服务端会自动生成并写回该文件

## 社区版与官方的差异

- 不接对象存储，媒体文件以 `server/uploads/` 为权威存储，地址跟随 `public_base_url`，未配置时输出同源相对路径。
- OAuth 2.1 / OIDC 提供方默认关闭，`/oauth/*` 不注册。需要时设 `OAUTH_ENABLED=1` 与 `OAUTH_ISSUER` 后重启。
- 注册不限邮箱域名，仅校验格式
- 下载源只有源服务器自身，不再回退 CDN
