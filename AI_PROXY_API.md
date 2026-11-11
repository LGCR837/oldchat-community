# Oldchat AI 代理 API

## 安全模型

- 上游固定为 `https://opencode.ai/zen/go/v1/chat/completions`。
- 模型固定为 `deepseek-v4-flash`，忽略客户端传入的其他模型。
- 上游密钥仅从服务端环境变量 `OPENCODE_GO_API_KEY` 读取，禁止写入源码、APK、响应或日志。
- 所有端点要求 Oldchat Access Token：`Authorization: Bearer <token>`。
- 数据库只保存额度数字，不保存提示词或模型回答。

## 查询额度

`GET /v1/ai/quota`

返回：

```json
{
  "token_limit": 5000000,
  "used_tokens": 42,
  "reserved_tokens": 0,
  "remaining_tokens": 99999958,
  "model": "deepseek-v4-flash",
  "enabled": true
}
```

## Chat Completions

`POST /v1/ai/chat/completions`

兼容别名：`POST /v1/chat/completions`

请求兼容 OpenAI Chat Completions，支持 `messages`、`tools`、`tool_choice` 和 SSE `stream`。服务端强制开启流式 usage 返回，并根据上游 `usage.total_tokens` 结算。

限制：

- 每个账号初始总额度：100,000,000 tokens
- 请求体：最大 1 MiB
- 消息：最多 256 条
- 单次最大输出：16,384 tokens
- 上游失败不扣额度
- 额度不足返回 HTTP 402，错误码 `ai_quota_exhausted`

## 服务端配置

```sh
OPENCODE_GO_API_KEY=服务端私有密钥
```

修改环境后重启 `oldchat` 服务。生产密钥文件权限应为 root-only（`0600`），并避免通过命令行参数传递。
