package httpapi

// 首页未读同步只用于恢复最近会话摘要，不应把老账户几十万条群消息一次性推给客户端。
// 进入具体聊天后仍可通过历史消息分页接口继续读取，不会丢失服务端数据。
const unreadBootstrapMaxMessages = 500
