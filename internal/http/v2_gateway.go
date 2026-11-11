package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
)

// gatewayRequest 是 /v2/gateway 的加密 body 明文结构。
// 所有 /v2 请求都折叠为 POST /v2/gateway，真实 method/path/query 在密文里，
// 抓包者看不到任何 API 语义（路径、群 ID、token 全部隐藏）。
type gatewayRequest struct {
	M string          `json:"m"` // 真实 method，如 GET
	P string          `json:"p"` // 真实路径，如 /v2/groups/messages/v2
	Q string          `json:"q"` // 真实 query，如 group_id=GRP-1&limit=20
	B json.RawMessage `json:"b"` // 原始业务 body（POST 参数，如发消息的 group_id/body），透传给内部 handler
}

// gatewayResponse 包装真实 handler 的响应。
type gatewayResponse struct {
	Code int             `json:"code"`
	Body json.RawMessage `json:"body"`
}

// v2GatewayHandler 是 /v2 路由的网关入口：
//  1. secureMiddleware 已把加密 body 解密为 gatewayRequest JSON。
//  2. authMiddleware 已注入 claims（token 校验）。
//  3. 本 handler 把 {m,p,q} 恢复到克隆请求上，内部路由到现有 v2 handler。
//
// 注意：gateway 请求本身不挂 v2SignMiddleware（签名校验在路由外层统一做），
// 但必须挂在 /v2 路由组内以复用 secure+auth。实际注册见 registerV2Routes。
func (a *API) handleV2Gateway(w http.ResponseWriter, r *http.Request) {
	// 解析加密 body（secureMiddleware 已解密，body 是 JSON）
	// 注意：必须先完整读取并保留内容，因为下面要把 body 透传给内部 handler。
	rawBody, readErr := io.ReadAll(r.Body)
	if readErr != nil {
		writeError(w, http.StatusBadRequest, "bad_gateway_body", "invalid gateway body")
		return
	}
	var gw gatewayRequest
	if err := json.Unmarshal(rawBody, &gw); err != nil {
		writeError(w, http.StatusBadRequest, "bad_gateway_body", "invalid gateway body")
		return
	}
	if gw.M == "" || gw.P == "" {
		writeError(w, http.StatusBadRequest, "bad_gateway_body", "missing method or path")
		return
	}
	// 只允许 /v2/ 路径（防 gateway 变成任意路径代理）
	if !strings.HasPrefix(gw.P, "/v2/") {
		writeError(w, http.StatusBadRequest, "bad_gateway_path", "path must start with /v2/")
		return
	}
	// method 白名单：只允许读操作（/v2 都是 GET）
	switch gw.M {
	case "GET", "POST", "PUT", "DELETE":
	default:
		writeError(w, http.StatusBadRequest, "bad_gateway_method", "unsupported method")
		return
	}

	// 克隆请求，恢复真实 method/path/query
	r2 := r.Clone(r.Context())
	r2.Method = gw.M
	r2.URL.Path = gw.P
	r2.URL.RawPath = ""
	r2.URL.RawQuery = gw.Q
	if gw.Q != "" {
		r2.URL.RawQuery = gw.Q
	}
	// 清空 body（GET 无 body）；POST 透传业务 body（gw.B，客户端塞的真实参数）。
	if gw.M == "GET" || gw.M == "DELETE" {
		r2.Body = http.NoBody
		r2.ContentLength = 0
	} else if len(gw.B) > 0 {
		r2.Body = io.NopCloser(bytes.NewReader(gw.B))
		r2.ContentLength = int64(len(gw.B))
	} else {
		// 兼容旧客户端（无 b 字段）：透传原始 body（{m,p,q}，空操作但可用）。
		r2.Body = io.NopCloser(bytes.NewReader(rawBody))
		r2.ContentLength = int64(len(rawBody))
	}

	// 用 bufferWriter 捕获内部 handler 输出（避免直接写客户端，统一包装）
	rec := newBufferWriter()
	// 内部路由：直接复用 /v2 路由组（含 v2SignMiddleware 会再次校验签名，
	// 但网关请求已带签名头，内部克隆保留了这些头，可以正常通过）。
	// 注意：为避免签名 nonce 重放（网关已消耗一次），内部路由要跳过 v2SignMiddleware。
	// 方案：直接调用 handler 而非走完整路由链。
	routeErr := a.dispatchV2Handler(rec, r2)
	if routeErr != nil {
		writeError(w, http.StatusInternalServerError, "gateway_error", "internal routing error")
		return
	}
	// 诊断日志：记录 gateway 折叠的内部路径与状态码（排查发消息 400 等）。
	internalStatus := rec.status
	if internalStatus == 0 {
		internalStatus = http.StatusOK
	}
	log.Printf("v2 gateway: m=%s p=%s q=%s body=%dB -> internal %d", gw.M, gw.P, gw.Q, len(rawBody), internalStatus)

	// 捕获到的状态码
	status := rec.status
	if status == 0 {
		status = http.StatusOK
	}
	// 内部 handler 的响应（含错误 JSON）统一包装进 {code, body}，
	// 客户端只认 HTTP 200 + 包装格式，内部状态码在 body.code 里。
	respBody := rec.buf.Bytes()
	out, err := json.Marshal(gatewayResponse{Code: status, Body: respBody})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "encode_failed", "internal error")
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(out)
}

// dispatchV2Handler 把 gateway 请求分发到对应 v2 handler（跳过中间件）。
// 返回错误仅表示内部路由失败；handler 的业务错误会通过 rec 状态码体现。
func (a *API) dispatchV2Handler(w http.ResponseWriter, r *http.Request) error {
	path := r.URL.Path
	switch path {
	case "/v2/updates/difference":
		a.handleUpdatesDifference(w, r)
	case "/v2/groups/events/after":
		a.handleGroupEventsAfter(w, r)
	case "/v2/groups/messages/after":
		a.handleGroupMessagesAfterSeq(w, r)
	case "/v2/groups/messages/v2":
		a.handleGroupMessagesV2(w, r)
	case "/v2/direct/messages/v2":
		a.handleDirectMessagesV2(w, r)
	case "/v2/unread/direct":
		a.handleDirectUnread(w, r)
	case "/v2/unread/groups":
		a.handleGroupUnread(w, r)
	// --- v2 写接口（复用 v1 handler，走 gateway 加密）---
	case "/v2/groups/message/send":
		a.handleGroupMessageSend(w, r)
	case "/v2/groups/read":
		a.handleGroupRead(w, r)
	case "/v2/groups/burn/open":
		a.handleGroupBurnOpen(w, r)
	case "/v2/groups/typing":
		a.handleGroupTyping(w, r)
	case "/v2/direct/send":
		a.handleDirectSend(w, r)
	case "/v2/direct/read":
		a.handleDirectRead(w, r)
	case "/v2/direct/burn/open":
		a.handleDirectBurnOpen(w, r)
	case "/v2/chats/typing":
		a.handleChatTyping(w, r)
	case "/v2/redpackets/send":
		a.handleRedPacketSend(w, r)
	case "/v2/redpackets/claim":
		a.handleRedPacketClaim(w, r)
	case "/v2/buttons/callback":
		a.handleBotCallback(w, r)
	// --- v2 好友接口 ---
	case "/v2/friends/requests":
		a.handleFriendRequests(w, r)
	case "/v2/friends/request":
		a.handleFriendRequest(w, r)
	case "/v2/friends/respond":
		a.handleFriendRespond(w, r)
	case "/v2/friends/remark":
		a.handleFriendRemark(w, r)
	case "/v2/friends/delete":
		a.handleFriendDelete(w, r)
	// --- v2 群管理接口 ---
	case "/v2/groups/create":
		a.handleGroupCreate(w, r)
	case "/v2/groups/join":
		a.handleGroupJoin(w, r)
	case "/v2/groups/list":
		a.handleGroupList(w, r)
	case "/v2/groups/members":
		a.handleGroupMembers(w, r)
	case "/v2/groups/members/lookup":
		a.handleGroupMembersLookup(w, r)
	case "/v2/groups/requests":
		a.handleGroupJoinRequests(w, r)
	case "/v2/groups/leave":
		a.handleGroupLeave(w, r)
	// --- v2 频道同步与未读 ---
	case "/v2/channels/discover":
		a.handleChannelDiscover(w, r)
	case "/v2/channels/posts/send":
		a.handleChannelPostSend(w, r)
	case "/v2/channels/subscribe":
		a.handleChannelSubscribe(w, r)
	case "/v2/channels/unsubscribe":
		a.handleChannelUnsubscribe(w, r)
	case "/v2/channels/state":
		a.handleChannelState(w, r)
	case "/v2/channels/states":
		a.handleChannelStates(w, r)
	case "/v2/channels/posts/after":
		a.handleChannelPostsAfter(w, r)
	case "/v2/channels/events/after":
		a.handleChannelEventsAfter(w, r)
	case "/v2/channels/read":
		a.handleChannelRead(w, r)
	case "/v2/channels/notifications":
		a.handleChannelNotifications(w, r)
	case "/v2/channels/reactions/toggle":
		a.handleChannelReactionToggle(w, r)
	case "/v2/channel-api/apply":
		a.handleChannelAPIApply(w, r)
	case "/v2/channel-api/status":
		a.handleChannelAPIStatus(w, r)
	// --- v2 个人中心 ---
	case "/v2/me/uid":
		a.handleUpdateUID(w, r)
	case "/v2/me/profile":
		a.handleUpdateProfile(w, r)
	case "/v2/me/password":
		a.handleUpdatePassword(w, r)
	case "/v2/me/delete/email/send":
		a.handleSendDeleteAccountEmailCode(w, r)
	case "/v2/me/delete":
		a.handleDeleteAccount(w, r)
	case "/v2/me/group-invite-preference":
		if r.Method == http.MethodGet {
			a.handleGetGroupInvitePreference(w, r)
		} else {
			a.handleSetGroupInvitePreference(w, r)
		}
	case "/v2/me/avatar":
		a.handleAvatarUpload(w, r)
	case "/v2/me/cover":
		a.handleCoverUpload(w, r)
	case "/v2/users/profile":
		a.handleUserProfile(w, r)
	case "/v2/friends":
		a.handleFriendList(w, r)
	// --- v2 群管理剩余 ---
	case "/v2/groups/approve":
		a.handleGroupApprove(w, r)
	case "/v2/groups/invite":
		a.handleGroupInvite(w, r)
	case "/v2/groups/invitations":
		a.handleGroupInvitations(w, r)
	case "/v2/groups/invitations/respond":
		a.handleGroupInvitationRespond(w, r)
	case "/v2/groups/admin":
		a.handleGroupAdmin(w, r)
	case "/v2/groups/avatar":
		a.handleGroupAvatar(w, r)
	case "/v2/groups/kick":
		a.handleGroupKick(w, r)
	case "/v2/groups/name":
		a.handleGroupRename(w, r)
	case "/v2/groups/settings":
		a.handleGroupSettings(w, r)
	case "/v2/groups/announcement":
		a.handleGroupAnnouncement(w, r)
	case "/v2/groups/announcement/read":
		a.handleGroupAnnouncementRead(w, r)
	case "/v2/groups/dissolve":
		a.handleGroupDissolve(w, r)
	// --- v2 签到/在线/设备 ---
	case "/v2/me/checkin":
		a.handleMeCheckIn(w, r)
	case "/v2/me/scratch":
		if r.Method == http.MethodGet {
			a.handleMeScratchStatus(w, r)
		} else {
			a.handleMeScratch(w, r)
		}
	case "/v2/me/presence":
		a.handleUpdatePresence(w, r)
	case "/v2/me/devices":
		a.handleMeDevices(w, r)
	case "/v2/me/devices/cleanup":
		a.handleMeDevicesCleanupOthers(w, r)
	case "/v2/me/bug-reports":
		a.handleMeBugReports(w, r)
	case "/v2/me/user-reports":
		a.handleMeUserReports(w, r)
	case "/v2/me/group-reports":
		a.handleMeGroupReports(w, r)
	case "/v2/notifications":
		a.handleNotificationList(w, r)
	case "/v2/reports/user":
		if r.Method == http.MethodGet {
			a.handleAllUserReports(w, r)
		} else {
			a.handleUserReport(w, r)
		}
	case "/v2/reports/group":
		if r.Method == http.MethodGet {
			a.handleAllGroupReports(w, r)
		} else {
			a.handleGroupReport(w, r)
		}
	case "/v2/reports/bug":
		a.handleAllBugReports(w, r)
	case "/v2/feedback":
		a.handleSubmitBugReport(w, r)
	// --- v2 朋友圈 (moments) ---
	case "/v2/moments":
		a.handleMomentCreate(w, r)
	case "/v2/moments/feed":
		a.handleMomentFeed(w, r)
	case "/v2/moments/user":
		a.handleMomentUserFeed(w, r)
	case "/v2/moments/like":
		a.handleMomentLike(w, r)
	case "/v2/moments/unlike":
		a.handleMomentUnlike(w, r)
	case "/v2/moments/delete":
		a.handleMomentDelete(w, r)
	case "/v2/moments/comment":
		a.handleMomentComment(w, r)
	case "/v2/moments/comment/delete":
		a.handleMomentCommentDelete(w, r)
	case "/v2/moments/comments":
		a.handleMomentComments(w, r)
	// --- v2 小程序商店 ---
	case "/v2/cip/store":
		a.handleCipStoreList(w, r)
	default:
		writeError(w, http.StatusNotFound, "not_found", "unknown v2 path")
	}
	return nil
}

// isV2Path 判断路径是否是 /v2 白名单内的。
func isV2Path(p string) bool {
	switch p {
	case "/v2/updates/difference",
		"/v2/groups/events/after",
		"/v2/groups/messages/after",
		"/v2/groups/messages/v2",
		"/v2/direct/messages/v2":
		return true
	}
	return false
}

var _ = url.Parse
