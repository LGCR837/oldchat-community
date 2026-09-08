# Oldchat 阿里云 OSS 集成

## 当前配置

- Bucket：`ocf`
- Endpoint：`https://oss-cn-shanghai.aliyuncs.com`（华东2 · 上海）
- 对象地址前缀：`https://ocf.oss-cn-shanghai.aliyuncs.com`
- Bucket 已允许公共读，客户端直连 OSS 域名访问资源。
- AccessKey 不写入客户端、源码或日志；生产服务端 EXE 可通过 `go build -ldflags -X` 注入，环境变量可覆盖。

## 资源链路

### 新客户端

1. API 和数据库继续保存 `/v1/uploads/...` 相对路径，保持协议兼容。
2. 客户端优先访问 `https://ocf.oss-cn-shanghai.aliyuncs.com/{对象路径}`（OSS 直连）。
3. 首选地址失败后，依次尝试 `http://60.205.94.101:8080/v1/uploads/...` 和 `https://oc.mcl0.dpdns.org/v1/uploads/...`。
4. 服务端同时保留 `/oss/...` 签名入口，供运维验证和兼容扩展使用。
5. 历史文件只在主服务器回源访问时后台补传，不执行全量迁移；新上传文件会立即保留本地副本并同步一份到 OSS。

### 旧客户端

旧客户端继续读取 `/uploads/...` 或 `/v1/uploads/...`，由服务器本地文件提供，不依赖 OSS 签名接口。

### 上传

1. 文件先原子地落到服务器本地目录，保留旧客户端和故障回源能力。
2. 图片、缩略图、头像、封面、表情、资源和音乐等上传后同步到 OSS。
3. 聊天媒体在 API 返回前尽力完成 OSS 上传；OSS 临时失败不会阻止本地上传成功。
4. 未迁移的旧资源在主服务器被访问时后台补传 OSS，并使用 `.oss` 标记避免重复上传。

## 配置方式

环境变量优先于 EXE 内的构建注入值：

```bash
OSS_ENABLED=true
OSS_ENDPOINT=https://oss-cn-shanghai.aliyuncs.com
OSS_ACCESS_KEY_ID=<仅保存在受限环境文件中>
OSS_ACCESS_KEY_SECRET=<仅保存在受限环境文件中>
OSS_BUCKET_NAME=ocf
OSS_URL_PREFIX=https://ocf.oss-cn-shanghai.aliyuncs.com
```

环境文件必须设置为 `0600`，不得提交到仓库。

## 验证

```bash
cd server
set -a
. /etc/oldchat-oss.env
set +a
go run ./cmd/test_oss
```

测试程序会创建临时对象、生成签名 URL、验证下载成功，最后删除临时对象；不会输出 AccessKey。

## 兼容与安全

- minSdk 9 客户端若无法完成 OSS HTTPS 握手，会自动回退主服务器。
- `ocf.oss-cn-shanghai.aliyuncs.com` 仅承载资源对象，不存放服务端 AccessKey。
- 服务端仍保留本地副本，因此 OSS 中断不会造成历史资源不可用。
- Cloudflare 不应长期缓存 OSS 的 404 响应，否则首次按需补传后可能短时间继续命中旧 404。
- 当前旧 AccessKey 曾在历史源码中出现，建议后续在阿里云控制台轮换。
