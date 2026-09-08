package httpapi

type SystemRouteItem struct {
	Key         string `json:"key"`
	Version     string `json:"version"`      // "v2" 或 "v1"
	Category    string `json:"category"`     // 用途分类
	Name        string `json:"name"`         // 接口名称
	Method      string `json:"method"`       // HTTP 方法
	Path        string `json:"path"`         // 路径或前缀
	Description string `json:"description"`  // 功能说明
	HasV2       bool   `json:"has_v2"`       // 客户端是否已接入 v2（已走 v2 网关）
	SafetyLevel string `json:"safety_level"` // "safe_to_disable"(已有v2可安全关), "optional"(业务级可选), "critical"(核心通道-慎关)
	Enabled     bool   `json:"enabled"`      // 当前状态
}

// allSystemRoutes 完整覆盖系统内全部 v1 与 v2 API 接口
var allSystemRoutes = []SystemRouteItem{
	// === v2 架构核心接口 ===
	{Key: "v2_gateway", Version: "v2", Category: "v2 核心网关", Name: "v2 加密主网关", Method: "POST", Path: "/v2/gateway", Description: "v2 端到端加密、请求折叠与主网关", HasV2: true, SafetyLevel: "critical"},
	{Key: "v2_updates_diff", Version: "v2", Category: "v2 核心网关", Name: "PTS 差量事件同步", Method: "GET", Path: "/v2/updates/difference", Description: "Telegram 风格 PTS 增量事件同步", HasV2: true, SafetyLevel: "critical"},
	{Key: "v2_groups_msg_after", Version: "v2", Category: "v2 群聊", Name: "群增量消息同步 (after)", Method: "GET", Path: "/v2/groups/messages/after", Description: "基于 group_seq 的高效增量消息拉取", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v2_groups_events_after", Version: "v2", Category: "v2 群聊", Name: "群事件增量同步", Method: "GET", Path: "/v2/groups/events/after", Description: "群成员进出、禁言、公告等事件增量", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v2_groups_messages_v2", Version: "v2", Category: "v2 群聊", Name: "群历史消息拉取 (v2)", Method: "GET", Path: "/v2/groups/messages/v2", Description: "v2 单群历史消息分页查询", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v2_direct_messages_v2", Version: "v2", Category: "v2 私聊", Name: "私聊历史消息拉取 (v2)", Method: "GET", Path: "/v2/direct/messages/v2", Description: "v2 点对点私聊消息分页查询", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v2_unread_groups", Version: "v2", Category: "v2 未读", Name: "群未读消息同步 (v2)", Method: "POST", Path: "/v2/unread/groups", Description: "v2 离线冷启动群消息批量同步", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v2_unread_direct", Version: "v2", Category: "v2 未读", Name: "私聊未读消息同步 (v2)", Method: "POST", Path: "/v2/unread/direct", Description: "v2 离线冷启动私聊消息批量同步", HasV2: true, SafetyLevel: "safe_to_disable"},

	// === v1 认证与账号 ===
	{Key: "v1_auth_handshake", Version: "v1", Category: "认证与账号", Name: "安全握手公钥交换", Method: "POST", Path: "/v1/auth/handshake", Description: "ECDH 密钥协商与通道建立（客户端首要依赖）", HasV2: false, SafetyLevel: "critical"},
	{Key: "v1_auth_login", Version: "v1", Category: "认证与账号", Name: "用户登录鉴权", Method: "POST", Path: "/v1/auth/login", Description: "账号密码登录与发放 Token", HasV2: false, SafetyLevel: "critical"},
	{Key: "v1_auth_refresh", Version: "v1", Category: "认证与账号", Name: "刷新 Access Token", Method: "POST", Path: "/v1/auth/refresh", Description: "使用 Refresh Token 续签凭证", HasV2: false, SafetyLevel: "critical"},
	{Key: "v1_auth_register", Version: "v1", Category: "认证与账号", Name: "新用户注册", Method: "POST", Path: "/v1/auth/register", Description: "新用户注册入口（需要暂停注册时可关）", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_auth_reset", Version: "v1", Category: "认证与账号", Name: "密码重置与找回", Method: "POST", Path: "/v1/auth/reset", Description: "邮箱验证码重置密码", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_auth_revoke", Version: "v1", Category: "认证与账号", Name: "注销/退出登录", Method: "POST", Path: "/v1/auth/revoke", Description: "撤销当前设备 Token 登录凭证", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_auth_delete_account", Version: "v1", Category: "认证与账号", Name: "账号注销删除", Method: "POST", Path: "/v1/auth/delete-account", Description: "用户自主要求彻底注销账号", HasV2: false, SafetyLevel: "optional"},

	// === v1 私聊通讯 ===
	{Key: "v1_direct_send", Version: "v1", Category: "私聊通讯", Name: "私聊发信 (v1旧通道)", Method: "POST", Path: "/v1/direct/send", Description: "客户端已全面接入 /v2/direct/send", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_direct_read", Version: "v1", Category: "私聊通讯", Name: "私聊已读确认 (v1旧通道)", Method: "POST", Path: "/v1/direct/read", Description: "客户端已全面接入 /v2/direct/read", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_direct_burn_open", Version: "v1", Category: "私聊通讯", Name: "私聊阅后即焚 (v1旧通道)", Method: "POST", Path: "/v1/direct/burn/open", Description: "客户端已全面接入 /v2/direct/burn/open", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_direct_unread", Version: "v1", Category: "私聊通讯", Name: "私聊未读同步 (v1旧通道)", Method: "POST", Path: "/v1/direct/unread", Description: "客户端已全面接入 /v2/unread/direct", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_direct_threads", Version: "v1", Category: "私聊通讯", Name: "私聊会话列表 (v1)", Method: "GET", Path: "/v1/direct/threads", Description: "获取私聊会话列表", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_direct_messages", Version: "v1", Category: "私聊通讯", Name: "私聊历史记录 (v1)", Method: "GET", Path: "/v1/direct/messages", Description: "单人私聊聊天记录翻页查询", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_direct_recall", Version: "v1", Category: "私聊通讯", Name: "私聊撤回消息 (v1)", Method: "POST", Path: "/v1/direct/recall", Description: "撤回私聊消息", HasV2: false, SafetyLevel: "optional"},

	// === v1 群组通讯 ===
	{Key: "v1_group_send", Version: "v1", Category: "群组通讯", Name: "群聊发信 (v1核心通道)", Method: "POST", Path: "/v1/groups/message/send", Description: "群消息发信通道（客户端强依赖，慎关！）", HasV2: false, SafetyLevel: "critical"},
	{Key: "v1_group_messages", Version: "v1", Category: "群组通讯", Name: "群历史消息 (v1)", Method: "GET", Path: "/v1/groups/messages", Description: "聊天窗口向上滑动加载群历史", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_group_read", Version: "v1", Category: "群组通讯", Name: "群消息已读 (v1旧通道)", Method: "POST", Path: "/v1/groups/read", Description: "客户端已全面接入 /v2/groups/read", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_unread", Version: "v1", Category: "群组通讯", Name: "群未读同步 (v1旧通道)", Method: "POST", Path: "/v1/groups/unread", Description: "客户端已全面接入 /v2/unread/groups", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_create", Version: "v1", Category: "群组通讯", Name: "创建新群聊 (v1旧通道)", Method: "POST", Path: "/v1/groups/create", Description: "客户端已全面接入 /v2/groups/create", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_join", Version: "v1", Category: "群组通讯", Name: "加入群聊 (v1旧通道)", Method: "POST", Path: "/v1/groups/join", Description: "客户端已全面接入 /v2/groups/join", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_leave", Version: "v1", Category: "群组通讯", Name: "退出群聊 (v1旧通道)", Method: "POST", Path: "/v1/groups/leave", Description: "客户端已全面接入 /v2/groups/leave", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_members", Version: "v1", Category: "群组通讯", Name: "群成员列表 (v1旧通道)", Method: "GET", Path: "/v1/groups/members", Description: "客户端已全面接入 /v2/groups/members", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_list", Version: "v1", Category: "群组通讯", Name: "我的群组列表 (v1旧通道)", Method: "GET", Path: "/v1/groups/list", Description: "客户端已全面接入 /v2/groups/list", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_group_recall", Version: "v1", Category: "群组通讯", Name: "群消息撤回 (v1)", Method: "POST", Path: "/v1/groups/recall", Description: "群主/管理/本人撤回群消息", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_group_dismiss", Version: "v1", Category: "群组通讯", Name: "解散群聊 (v1旧通道)", Method: "POST", Path: "/v1/groups/dismiss", Description: "客户端已全面接入 /v2/groups/dissolve", HasV2: true, SafetyLevel: "safe_to_disable"},

	// === v1 好友关系 ===
	{Key: "v1_friends_list", Version: "v1", Category: "好友关系", Name: "好友列表 (v1旧通道)", Method: "GET", Path: "/v1/friends", Description: "客户端已全面接入 /v2/friends", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_friends_request", Version: "v1", Category: "好友关系", Name: "发送好友申请 (v1旧通道)", Method: "POST", Path: "/v1/friends/request", Description: "客户端已全面接入 /v2/friends/request", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_friends_requests", Version: "v1", Category: "好友关系", Name: "好友申请列表 (v1旧通道)", Method: "GET", Path: "/v1/friends/requests", Description: "客户端已全面接入 /v2/friends/requests", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_friends_respond", Version: "v1", Category: "好友关系", Name: "处理好友申请 (v1旧通道)", Method: "POST", Path: "/v1/friends/respond", Description: "客户端已全面接入 /v2/friends/respond", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_friends_delete", Version: "v1", Category: "好友关系", Name: "删除好友 (v1旧通道)", Method: "POST", Path: "/v1/friends/delete", Description: "客户端已全面接入 /v2/friends/delete", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_friends_remark", Version: "v1", Category: "好友关系", Name: "修改好友备注 (v1旧通道)", Method: "POST", Path: "/v1/friends/remark", Description: "客户端已全面接入 /v2/friends/remark", HasV2: true, SafetyLevel: "safe_to_disable"},

	// === v1 多媒体与文件 ===
	{Key: "v1_upload", Version: "v1", Category: "媒体与文件", Name: "多媒体文件上传 (v1核心通道)", Method: "POST", Path: "/v1/upload", Description: "图片、语音、视频与附件上传（客户端强依赖，慎关！）", HasV2: false, SafetyLevel: "critical"},
	{Key: "v1_resources_list", Version: "v1", Category: "媒体与文件", Name: "资源广场列表", Method: "GET", Path: "/v1/resources", Description: "浏览资源广场共享文件", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_resources_post", Version: "v1", Category: "媒体与文件", Name: "资源广场发布", Method: "POST", Path: "/v1/resources", Description: "向资源广场上传发布新资源", HasV2: false, SafetyLevel: "optional"},

	// === v1 音乐广场 ===
	{Key: "v1_music_plaza", Version: "v1", Category: "音乐广场", Name: "音乐广场曲目与大厅", Method: "GET", Path: "/v1/music", Description: "音乐大厅歌曲点播、流媒体与播放", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_music_playlist", Version: "v1", Category: "音乐广场", Name: "个人歌单与收藏", Method: "ALL", Path: "/v1/music/playlist", Description: "歌单新建、收藏与播放列表管理", HasV2: false, SafetyLevel: "optional"},

	// === v1 朋友圈动态 ===
	{Key: "v1_moments_feed", Version: "v1", Category: "朋友圈动态", Name: "朋友圈动态流 (v1)", Method: "GET", Path: "/v1/moments", Description: "客户端已支持 /v2/moments/feed", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_moments_post", Version: "v1", Category: "朋友圈动态", Name: "发布朋友圈动态 (v1)", Method: "POST", Path: "/v1/moments/post", Description: "发布图文动态到朋友圈", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_moments_interact", Version: "v1", Category: "朋友圈动态", Name: "动态点赞评论 (v1旧通道)", Method: "POST", Path: "/v1/moments/like", Description: "客户端已全面接入 /v2/moments/like", HasV2: true, SafetyLevel: "safe_to_disable"},

	// === v1 红包与资产 ===
	{Key: "v1_redpacket_send", Version: "v1", Category: "红包与资产", Name: "发送红包 (v1旧通道)", Method: "POST", Path: "/v1/redpackets/send", Description: "客户端已全面接入 /v2/redpackets/send", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_redpacket_claim", Version: "v1", Category: "红包与资产", Name: "抢/领红包 (v1旧通道)", Method: "POST", Path: "/v1/redpackets/claim", Description: "客户端已全面接入 /v2/redpackets/claim", HasV2: true, SafetyLevel: "safe_to_disable"},
	{Key: "v1_checkin", Version: "v1", Category: "红包与资产", Name: "每日签到打卡 (v1旧通道)", Method: "POST", Path: "/v1/user/daily-checkin", Description: "客户端已全面接入 /v2/me/checkin", HasV2: true, SafetyLevel: "safe_to_disable"},

	// === v1 CIP 小程序与发现 ===
	{Key: "v1_cip_manifest", Version: "v1", Category: "CIP 小程序", Name: "小程序清单列表", Method: "GET", Path: "/v1/discover/lua/manifest", Description: "获取发现页全部可运行 CIP 小程序", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_cip_apps", Version: "v1", Category: "CIP 小程序", Name: "加载 CIP 小程序", Method: "GET", Path: "/v1/discover/lua/apps", Description: "在线下载与运行 Lua 脚本沙箱", HasV2: false, SafetyLevel: "optional"},

	// === v1 举报与公共法庭 ===
	{Key: "v1_reports_post", Version: "v1", Category: "举报与法庭", Name: "提交举报工单", Method: "POST", Path: "/v1/reports", Description: "举报违规用户、违规群组与资源", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_court_cases", Version: "v1", Category: "举报与法庭", Name: "公共法庭案件列表", Method: "GET", Path: "/v1/court/cases", Description: "查看陪审团审理中的法庭案件", HasV2: false, SafetyLevel: "optional"},
	{Key: "v1_court_vote", Version: "v1", Category: "举报与法庭", Name: "法庭陪审团投票", Method: "POST", Path: "/v1/court/vote", Description: "对违规案件进行封禁/保留投票", HasV2: false, SafetyLevel: "optional"},
}
