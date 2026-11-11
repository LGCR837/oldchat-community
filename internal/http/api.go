package httpapi

import (
	"context"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jmoiron/sqlx"

	"metrochat/internal/auth"
	"metrochat/internal/config"
	"metrochat/internal/data"
	"metrochat/internal/oss"
	"metrochat/internal/ratelimit"
	"metrochat/internal/secure"
	"metrochat/internal/verify"
	"metrochat/internal/ws"
)

type API struct {
	cfg                config.Config
	dataSyncClient     *http.Client
	ossClient          *oss.Client
	rainOSSClient      *oss.RainClient
	db                 *sqlx.DB
	users              *data.UserStore
	refresh            *data.RefreshStore
	oauth              *data.OAuthStore
	oauthSigningKey    *auth.OAuthSigningKey
	friendReqs         *data.FriendRequestStore
	friends            *data.FriendStore
	direct             *data.DirectStore
	groups             *data.GroupStore
	groupJoins         *data.GroupJoinStore
	groupInvites       *data.GroupInvitationStore
	groupMsgs          *data.GroupMessageStore
	groupBans          *data.GroupBanStore
	channels           *data.ChannelStore
	moments            *data.MomentStore
	resources          *data.ResourceStore
	fileAssets         *data.FileAssetStore
	emojis             *data.EmojiPlazaStore
	musicPlaza         *data.MusicPlazaStore
	playlists          *data.PlaylistStore
	favorites          *data.FavoriteStore
	coinTransfers      *data.ExternalCoinTransferStore
	resourceReports    *data.ResourceReportStore
	publicCourt        *data.PublicCourtStore
	banAppeals         *data.BanAppealStore
	devices            *data.DeviceStore
	crashReportStore   *data.CrashReportStore
	reportStore        *data.ReportStore
	groupReportStore   *data.GroupReportStore
	bugReportStore     *data.BugReportStore
	aiQuotas           *data.AIQuotaStore
	aiProxyClient      *http.Client
	notifications      *data.NotificationStore
	titles             *data.TitleCatalogStore
	pts                *data.PtsStore
	groupEvents        *data.GroupEventStore
	events             *eventsService
	readThrottle       map[string]int64
	wsHub              *ws.Hub
	sessions           *secure.SessionStore
	checkinWall        *data.CheckInWallStore
	checkinInteract    *data.CheckInWallInteractionStore
	scratch            *data.ScratchStore
	cipStore           *data.CipStore
	emailCodes         *verify.EmailCodeStore
	sendLimiter        *verify.SendLimiter
	adminSessions      *adminSessions
	moderatorStore     *data.ModeratorStore
	moderatorSessions  *moderatorSessions
	moderatorLogs      *data.ModeratorLogStore
	channelWebSessions *channelWebSessions
	channelMediaKey    []byte
	ipLimiter          *ratelimit.Limiter
	actionIPLimiter    *ratelimit.Limiter
	idLimiter          *ratelimit.Limiter
	adminLoginLimiter   *ratelimit.Limiter
	systemStatusLimiter *ratelimit.Limiter
	webAppDir           string
	luaAppDir          string
	typing             *typingStore
	buttonSigner       *secure.ButtonSigner
	tokenVersionMu     sync.Mutex
	tokenVersionCache  map[string]tokenVersionEntry
	banStatusMu        sync.Mutex
	banStatusCache     map[string]banStatusEntry
}

func New(cfg config.Config, db *sqlx.DB) http.Handler {
	// Ensure system user exists for sending notifications
	_ = data.EnsureSystemUser(db)

	ossClient, ossErr := oss.NewClient(oss.LoadConfigFromEnv())
	if ossErr != nil {
		log.Printf("OSS 初始化失败，将继续使用本地资源回源: %v", ossErr)
		ossClient = nil
	} else if ossClient.IsEnabled() {
		log.Printf("OSS 已启用，新资源将同步到对象存储")
	}

	rainClient, rainErr := oss.NewRainClient(oss.LoadRainConfigFromEnv())
	if rainErr != nil {
		log.Printf("雨云 S3 初始化失败，资源广场将仅本地存储: %v", rainErr)
		rainClient = nil
	} else if rainClient.IsEnabled() {
		log.Printf("雨云 S3 已启用，资源广场文件将同步（全局限速 5MB/s）")
	}

	api := &API{
		cfg:                cfg,
		dataSyncClient:     &http.Client{Timeout: 70 * time.Second},
		ossClient:          ossClient,
		rainOSSClient:      rainClient,
		db:                 db,
		users:              data.NewUserStore(db),
		refresh:            data.NewRefreshStore(db),
		oauth:              data.NewOAuthStore(db),
		friendReqs:         data.NewFriendRequestStore(db),
		friends:            data.NewFriendStore(db),
		direct:             data.NewDirectStore(db),
		groups:             data.NewGroupStore(db),
		groupJoins:         data.NewGroupJoinStore(db),
		groupInvites:       data.NewGroupInvitationStore(db),
		groupMsgs:          data.NewGroupMessageStore(db),
		groupBans:          data.NewGroupBanStore(db),
		channels:           data.NewChannelStore(db),
		moments:            data.NewMomentStore(db),
		resources:          data.NewResourceStore(db),
		fileAssets:         data.NewFileAssetStore(db),
		emojis:             data.NewEmojiPlazaStore(db),
		musicPlaza:         data.NewMusicPlazaStore(db),
		playlists:          data.NewPlaylistStore(db),
		favorites:          data.NewFavoriteStore(db),
		coinTransfers:      data.NewExternalCoinTransferStore(db),
		resourceReports:    data.NewResourceReportStore(db),
		publicCourt:        data.NewPublicCourtStore(db),
		banAppeals:         data.NewBanAppealStore(db),
		devices:            data.NewDeviceStore(db),
		crashReportStore:   data.NewCrashReportStore(db),
		reportStore:        data.NewReportStore(db),
		groupReportStore:   data.NewGroupReportStore(db),
		bugReportStore:     data.NewBugReportStore(db),
		pts:                data.NewPtsStore(db),
		groupEvents:        data.NewGroupEventStore(db),
		aiQuotas:           data.NewAIQuotaStore(db),
		aiProxyClient:      &http.Client{Timeout: 5 * time.Minute},
		checkinWall:        data.NewCheckInWallStore(db),
		checkinInteract:    data.NewCheckInWallInteractionStore(db),
		scratch:            data.NewScratchStore(db),
		cipStore:           data.NewCipStore(db),
		notifications:      data.NewNotificationStore(db),
		titles:             data.NewTitleCatalogStore(db),
		wsHub:              ws.NewHub(),
		sessions:           secure.NewSessionStore(),
		emailCodes:         verify.NewEmailCodeStore(),
		sendLimiter:        verify.NewSendLimiter(),
		adminSessions:      newAdminSessions(),
		moderatorStore:     data.NewModeratorStore(db),
		moderatorSessions:  newModeratorSessions(),
		moderatorLogs:      data.NewModeratorLogStore(db),
		channelWebSessions: newChannelWebSessions(),
		channelMediaKey:    loadOrCreateChannelMediaKey(cfg.UploadDir),
		ipLimiter:          ratelimit.NewLimiter(1.0, 5),
		// 加好友、发动态等写操作按 IP 每 5 秒最多一次，不允许突发。
		actionIPLimiter:   ratelimit.NewLimiter(0.2, 1),
		idLimiter:         ratelimit.NewLimiter(0.2, 3),
		// 管理后台登录与系统状态查询：同一 IP 每 3 秒最多一次，不允许突发。
		adminLoginLimiter:   ratelimit.NewLimiter(1.0/3.0, 1),
		systemStatusLimiter: ratelimit.NewLimiter(1.0/3.0, 1),
		webAppDir:           resolveWebAppDir(),
		luaAppDir:         resolveLuaAppDir(),
		typing:            newTypingStore(),
		tokenVersionCache: make(map[string]tokenVersionEntry),
		banStatusCache:    make(map[string]banStatusEntry),
	}
	api.events = newEventsService(api.pts, api.wsHub)
	api.readThrottle = make(map[string]int64)
	// 账号事件表只保留真正的账号级事件并保留最近 48 小时。
	// 群消息已改用每群唯一 group_seq，不再进入该表。启动后延迟清理，不阻塞启动。
	go func() {
		time.Sleep(30 * time.Second)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()
		cut := time.Now().Add(-48 * time.Hour)
		if n, err := api.pts.PruneBefore(ctx, cut); err != nil {
			log.Printf("account_events 清理失败: %v", err)
		} else if n > 0 {
			log.Printf("account_events 已清理 %d 条（保留 48h）", n)
		}
	}()
	// 表单提交结果属于短期敏感数据，只保留 10 分钟；每分钟物理删除过期记录。
	go func() {
		prune := func() {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			_, _ = api.db.ExecContext(ctx,
				`DELETE FROM button_responses WHERE created_at < datetime('now','-10 minutes')`)
		}
		prune()
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			prune()
		}
	}()
	if key, err := auth.LoadOrCreateOAuthSigningKey(cfg.OAuthSigningKeyPath); err != nil {
		log.Printf("OAuth 签名密钥初始化失败，第三方登录暂不可用: %v", err)
	} else {
		api.oauthSigningKey = key
	}
	// 按钮 tid 签名器（RSA，密钥持久化到 UploadDir）
	if signer, err := secure.LoadOrCreateButtonSigner(filepath.Join(cfg.UploadDir, "keys")); err != nil {
		log.Printf("按钮签名密钥初始化失败: %v", err)
	} else {
		api.buttonSigner = signer
	}
	// 首个 client 通过环境变量人工引导创建；后续 client 由管理员审核后写入。
	if clientID := strings.TrimSpace(os.Getenv("OAUTH_BOOTSTRAP_CLIENT_ID")); clientID != "" {
		redirectURI := strings.TrimSpace(os.Getenv("OAUTH_BOOTSTRAP_REDIRECT_URI"))
		if redirectURI != "" && auth.ValidateOAuthHTTPSURL(redirectURI, true) {
			secret := strings.TrimSpace(os.Getenv("OAUTH_BOOTSTRAP_CLIENT_SECRET"))
			if secret != "" && len(secret) < 32 {
				log.Printf("OAuth 引导 client secret 少于 32 字符，已拒绝创建")
			} else {
				secretHash := ""
				if secret != "" {
					secretHash = auth.HashToken(secret)
				}
				client := data.OAuthClient{
					ClientID: clientID, Name: strings.TrimSpace(os.Getenv("OAUTH_BOOTSTRAP_CLIENT_NAME")),
					SecretHash: secretHash, RedirectURI: redirectURI,
					AllowedScopes: "openid profile offline_access", PublicClient: secret == "", Active: true,
				}
				if client.Name == "" {
					client.Name = clientID
				}
				ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
				if err := api.oauth.UpsertClient(ctx, client); err != nil {
					log.Printf("OAuth 引导 client 创建失败: %v", err)
				}
				cancel()
			}
		}
	}
	setTransferRateLimits(cfg.MediaRateBytes, cfg.UpdateRateBytes, cfg.VideoRateBytes, cfg.MusicRateBytes)
	setTransferConcurrencyLimits(cfg.MediaDownloadConcurrency, cfg.UpdateDownloadConcurrency, cfg.VideoDownloadConcurrency, cfg.MusicDownloadConcurrency)

	// Best-effort table bootstrap so handlers don't do DDL on hot paths.
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = api.notifications.EnsureTable(ctx)
	_ = api.bugReportStore.EnsureTable(ctx)
	_ = api.titles.EnsureTable(ctx)
	_ = api.cipStore.EnsureTable(ctx)
	initRouteSwitch(filepath.Dir(cfg.UploadDir))
	startMediaCleanup(cfg.UploadDir)

	r := chi.NewRouter()
	r.Use(api.metricsMiddleware)
	r.Use(api.routeSwitchMiddleware)
	r.Use(requestLogger)
	r.Use(recoverer)
	r.Use(gzipResponses)
	r.Use(secureHeaders)

	r.Get("/.well-known/openid-configuration", api.handleOIDCDiscovery)
	r.Get("/oauth/jwks.json", api.handleOAuthJWKS)
	r.Get("/oauth/authorize", api.handleOAuthAuthorizeGet)
	r.Post("/oauth/authorize", api.handleOAuthAuthorizePost)
	r.Get("/oauth/authorize/resume", api.handleOAuthAuthorizeResume)
	r.Get("/oauth/register", api.handleOAuthRegisterGet)
	r.Post("/oauth/register", api.handleOAuthRegisterPost)
	r.Get("/oauth/reset", api.handleOAuthResetGet)
	r.Post("/oauth/reset", api.handleOAuthResetPost)
	r.Post("/oauth/token", api.handleOAuthToken)
	r.Post("/oauth/introspect", api.handleOAuthIntrospect)
	r.Post("/oauth/revoke", api.handleOAuthRevoke)
	r.Get("/oauth/userinfo", api.handleOAuthUserInfo)

	r.Get("/", api.handleLanding)
	r.Get("/landing", api.handleLanding)
	r.Get("/register", api.handleRegisterPage)
	r.Get("/terms", api.handleTermsPage)
	r.Get("/agreement", api.handleTermsPage)
	r.Get("/privacy", api.handleTermsPage)
	r.Get("/forgot-password", api.handleForgotPasswordPage)
	r.Get("/download/oldchat.apk", api.handleAppAPKDownload)
	r.Get("/download/oldchat-dev.apk", api.handleAppDevAPKDownload)
	r.Get("/download/oldchat-windows.exe", api.handleAppWindowsDownload)
	r.Get("/shop", api.handleReportPage)
	r.Get("/igotbanned", api.handleIGotBannedPage)
	r.Post("/igotbanned", api.handleIGotBannedSubmit)
	r.Get("/bot-apply", api.handleBotApplyPage)
	r.Post("/bot-apply", api.handleBotApplySubmit)
	r.Post("/bot-apply/token", api.handleBotTokenClaim)
	r.Get("/bot-api.md", handleBotAPIDocument)
	r.Get("/channel-api.md", handleChannelAPIDocument)
	r.Get("/app", api.handleWebApp)
	r.Get("/app/*", api.handleWebApp)
	r.Get("/coin-tool", api.handleCoinToolPage)
	if api.webAppDir != "" {
		r.Handle("/app-assets/*", http.StripPrefix("/app-assets/", http.FileServer(http.Dir(api.webAppDir))))
	}
	r.Get("/report", api.handleReportPage)
	r.Get("/cip-upload", api.handleCipUploadPage)
	r.Post("/cip-upload", api.handleCipUploadSubmit)
	r.Get("/v1/cip/store", api.handleCipStoreList)
	r.Get("/v1/cip/store/download/{id}", api.handleCipStoreDownload)
	r.Get("/status", api.handleStatusPage)
	r.Get("/v1/system/status", api.handleStatusDataAPI)
	r.Get("/lua-cip.md", handleLuaCIPDevelopmentGuide)
	r.Get("/api.md", handleAPIStandardDocument)
	r.Get("/in/api.md", handleAPIFullDocument)
	r.Get("/client-guide.md", handleClientGuideDocument)
	r.Get("/routes.md", handleNonAdminRoutesDocument)
	r.Get("/internal/message-sync.md", handleInternalMessageSyncDoc)
	r.Get("/oauth.md", handleOAuthLoginDocument)
	r.Handle("/lua-assets/*", http.HandlerFunc(api.handleLuaAppAsset))
	r.Post("/report", api.handleReportSubmit)
	r.Post("/shop/login", api.handleShopLogin)
	r.Get("/shop/logout", api.handleShopLogout)
	r.Get("/v1/music/cover/*", api.handleMusicCoverProxy)
	r.Get("/oss/*", api.handleOSSDownload)
	r.Head("/oss/*", api.handleOSSDownload)
	r.Handle("/landing-assets/*", http.StripPrefix("/landing-assets/", http.FileServer(http.Dir("ooldchat-web/assets"))))

	api.registerAdminAPIRoutes(r)
	r.Get("/admins/legacy", api.handleAdminIndex)
	r.Get("/admins/resources", api.handleAdminResourceHall)
	r.Post("/admins/resources/section/delete", api.handleAdminResourceSectionDelete)
	r.Get("/admins/moderation", api.handleAdminModeration)
	r.Get("/admins/moderation/login", api.handleModerationLoginPage)
	r.Post("/admins/moderation/login", api.handleModerationLogin)
	r.Get("/admins/moderation/logout", api.handleModerationLogout)
	r.Post("/admins/moderation/logout", api.handleModerationLogout)
	r.Post("/admins/moderation/report/status", api.handleAdminModerationReportStatus)
	r.Post("/admins/moderation/report/ban", api.handleAdminModerationReportBan)
	r.Post("/admins/moderation/appeal/status", api.handleAdminModerationAppealStatus)
	r.Post("/admins/moderation/bug/status", api.handleAdminModerationBugStatus)
	r.Post("/admins/moderation/bot/review", api.handleAdminModerationBotReview)
	r.Post("/admins/moderation/channel/review", api.handleAdminModerationChannelReview)
	r.Post("/admins/moderation/token/revoke", api.handleAdminModerationTokenRevoke)
	r.Post("/admins/moderation/cip/review", api.handleAdminModerationCipReview)
	r.Post("/admins/moderation/public-court/review", api.handleAdminModerationPublicCourtReview)
	r.Get("/admins/moderation/chat-context", api.handleAdminModerationChatContext)
	r.Post("/admins/moderation/inspector/moment/delete", api.handleAdminModerationInspectorDeleteMoment)
	r.Post("/admins/moderators/create", api.handleAdminModeratorCreate)
	r.Post("/admins/moderators/toggle", api.handleAdminModeratorToggle)
	r.Post("/admins/moderators/reset-password", api.handleAdminModeratorResetPassword)
	r.Post("/admins/moderators/delete", api.handleAdminModeratorDelete)
	r.Get("/admins/oauth-clients", api.handleAdminOAuthClients)
	r.Post("/admins/oauth-clients", api.handleAdminOAuthClientsPost)
	r.Post("/admins/ban/device", api.handleAdminBanDevice)
	r.Post("/admins/unban/device", api.handleAdminUnbanDevice)
	r.Post("/admins/ban/user", api.handleAdminBanUser)
	r.Post("/admins/unban/user", api.handleAdminUnbanUser)
	r.Post("/admins/user/deactivate", api.handleAdminDeactivateUser)
	r.Post("/admins/user/delete", api.handleAdminDeleteUser)
	r.Post("/admins/user-title", api.handleAdminUserTitle)
	r.Post("/admins/user-coin", api.handleAdminUserCoin)
	r.Post("/admins/user-uid", api.handleAdminUserUID)
	r.Post("/admins/user/create", api.handleAdminCreateTestUser)
	r.Post("/admins/title/create", api.handleAdminTitleCreate)
	r.Post("/admins/title/update", api.handleAdminTitleUpdate)
	r.Post("/admins/title/toggle", api.handleAdminTitleToggle)
	r.Post("/admins/title/delete", api.handleAdminTitleDelete)
	r.Post("/admins/group/ban", api.handleAdminBanGroup)
	r.Post("/admins/group/unban", api.handleAdminUnbanGroup)
	r.Post("/admins/group/delete", api.handleAdminDeleteGroup)
	r.Post("/admins/group/owner", api.handleAdminSetGroupOwner)
	r.Post("/admins/group/daily-limit", api.handleAdminSetGroupDailyLimit)
	r.Post("/admins/registration-limit", api.handleAdminRegistrationLimit)
	r.Post("/admins/video-toggle", api.handleAdminVideoToggle)
	r.Get("/admins/crash-reports", api.handleListCrashReports)
	r.Get("/admins/crash-reports/{id}", api.handleGetCrashReport)
	r.Post("/admins/notification/send", api.handleAdminNotificationSend)
	r.Post("/admins/notification/delete", api.handleAdminNotificationDelete)
	r.Get("/admins/bug-reports", api.handleAdminBugReports)
	r.Post("/admins/bug-reports/delete", api.handleAdminBugReportDelete)
	r.Post("/admins/bug-reports/clear", api.handleAdminBugReportsClear)
	r.Post("/admins/bug-reports/status", api.handleAdminBugReportStatus)
	r.Post("/admins/resources/delete", api.handleAdminResourceDelete)
	r.Post("/admins/emoji-plaza/delete", api.handleAdminEmojiPlazaDelete)
	r.Post("/admins/resource-quota", api.handleAdminResourceQuota)
	r.Post("/admins/bandwidth-limit", api.handleAdminBandwidthLimit)
	r.Post("/admins/data-sync", api.handleAdminDataSyncConfig)
	r.Post("/admins/user-reports/status", api.handleAdminUserReportStatus)
	r.Post("/admins/group-reports/status", api.handleAdminGroupReportStatus)
	r.Post("/admins/resource-reports/status", api.handleAdminResourceReportStatus)
	r.Get("/admins/resource-reports", api.handleAllResourceReports)
	r.Post("/admins/ban-appeals/status", api.handleAdminBanAppealStatus)
	r.Post("/admins/public-court/close", api.handleAdminPublicCourtClose)
	r.Post("/admins/public-court/clear", api.handleAdminPublicCourtClear)
	r.Get("/admins/server-log", api.handleAdminServerLog)
	r.Get("/admins/lua/apps", api.handleAdminLuaApps)
	r.Post("/admins/lua/apps/upload", api.handleAdminLuaAppUpload)
	r.Post("/admins/lua/apps/toggle", api.handleAdminLuaAppToggle)
	r.Post("/admins/lua/apps/delete", api.handleAdminLuaAppDelete)

	r.Handle("/update/*", withUpdateDownloadLimit(http.StripPrefix("/update/", http.FileServer(http.Dir(cfg.UpdateDir)))))
	fileDownloads := withFileDownloadHeaders(http.StripPrefix("/files/", http.FileServer(http.Dir(filepath.Join(cfg.UploadDir, "files")))))
	r.Handle("/files/*", api.authMiddleware(withDownloadLimit(fileDownloads)))
	resourceDownloads := withFileDownloadHeaders(http.StripPrefix("/resources/", http.FileServer(http.Dir(filepath.Join(cfg.UploadDir, "resources")))))
	r.Handle("/resources/*", api.authMiddleware(withDownloadLimit(api.resourceGateHandler(resourceDownloads))))
	uploadsHandler := withStaticMediaCache(http.StripPrefix("/uploads/", http.FileServer(http.Dir(cfg.UploadDir))))
	uploadsHandler = ossRedirectHandler(cfg.UploadDir, api.ossClient, uploadsHandler)
	uploadsHandler = withProtectedUploadPaths(api.authMiddleware, uploadsHandler)
	r.Handle("/uploads/*", withMediaDownloadLimit(func() bool { return api.cfg.VideoEnabled }, uploadsHandler))

	api.wsHub.OnPresenceChange = api.handlePresenceChange
	go StartDirectBurnCleanup(api)
	go StartGroupBurnCleanup(api)

	// 机器人按钮接口：/v2/buttons/send 使用 message-token（Bearer）认证，
	// 不依赖登录 JWT，因此注册在 /v2 路由组之外（组内挂了 authMiddleware）。
	r.Post("/v2/buttons/send", api.handleBotSend)
	r.Get("/v2/buttons/responses", api.handleBotResponses)
	// 外部 publisher token 的频道创建/发帖接口，不使用登录 JWT。
	r.Post("/v2/channel-api/channels", api.handleChannelAPICreate)
	r.Post("/v2/channel-api/posts", api.handleChannelAPIPost)
	r.Patch("/v2/channel-api/posts", api.handleChannelAPIEditPost)
	r.Delete("/v2/channel-api/posts", api.handleChannelAPIDeletePost)
	r.Get("/channel-manage", api.handleChannelWebHome)
	r.Post("/channel-manage/login", api.handleChannelWebLogin)
	r.Get("/channel-manage/logout", api.handleChannelWebLogout)
	r.Post("/channel-manage/post", api.handleChannelWebPost)
	r.Post("/channel-manage/publisher", api.handleChannelWebPublisher)
	r.Get("/channel-manage/register", api.handleChannelWebRegister)
	r.Post("/channel-manage/register", api.handleChannelWebRegisterPost)
	r.Post("/channel-manage/update", api.handleChannelWebUpdate)
	r.Get("/channel-api/apply", api.handleChannelAPIApplyPage)
	r.Post("/channel-api/apply", api.handleChannelAPIApplyPost)
	r.Post("/channel-api/apply/token", api.handleChannelAPITokenClaim)
	r.Get("/channel-media/{filename}", api.handleChannelMediaDownload)
	r.Get("/admin/channel-api/applications", api.handleChannelAPIAdminList)
	r.Post("/admin/channel-api/applications/review", api.handleChannelAPIAdminReview)
	// 回调需要登录用户 claims（记录点击者），挂 authMiddleware 但不要求加密会话。
	r.Group(func(r chi.Router) {
		r.Use(api.authMiddleware)
		r.Post("/v2/buttons/callback", api.handleBotCallback)
	})
	// 机器人管理后台走 admin cookie 会话，与登录 JWT 无关。
	r.Get("/admin/buttons", api.handleBotAdminList)
	r.Post("/admin/buttons/review", api.handleBotAdminReview)

	r.Route("/v1", api.registerV1Routes)
	r.Route("/v1/v1", api.registerV1Routes)
	r.Route("/v2", api.registerV2Routes)

	return r
}

// registerV2Routes 消息 v2 路由组：新协议聊天接口。
// 与 /v1 并存（旧客户端走 /v1，新客户端走 /v2），handler 复用同一实现。
// 防护链：secureMiddleware（解密 body）→ authMiddleware（注入 claims）→
// v2SignMiddleware（强制会话 + 请求签名 + 设备绑定）。
func (api *API) registerV2Routes(r chi.Router) {
	r.Use(api.secureMiddleware)
	r.Use(api.authMiddleware)
	r.Use(api.v2SignMiddleware)
	// 网关入口：新客户端所有 /v2 请求折叠到这一个路径（真实语义在密文里）。
	r.Post("/gateway", api.handleV2Gateway)
	r.Get("/updates/difference", api.handleUpdatesDifference)
	r.Get("/groups/events/after", api.handleGroupEventsAfter)
	r.Get("/groups/messages/after", api.handleGroupMessagesAfterSeq)
	r.Get("/groups/messages/v2", api.handleGroupMessagesV2)
	r.Get("/direct/messages/v2", api.handleDirectMessagesV2)
	// v2 unread 直接路由（POST body 分页）。有会话走 gateway 折叠，无会话（老设备无 ECDH）
	// 直接明文 POST 到这里；gateway 与直接路由都指向同一 handler。
	r.Post("/unread/direct", api.handleDirectUnread)
	r.Post("/unread/groups", api.handleGroupUnread)
	// 大文件 multipart 不进入整包加密；仍由 authMiddleware 校验 Bearer JWT。
	r.Post("/files/check", api.handleFileCheckV2)
	r.Post("/files/upload", api.handleFileUploadV2)
	r.Get("/files/download/{fileID}", api.handleFileAssetDownload)
	r.Head("/files/download/{fileID}", api.handleFileAssetDownload)
	r.Get("/resources/download/{itemID}", api.resourceGate(api.handleResourceItemDownload))
	r.Head("/resources/download/{itemID}", api.resourceGate(api.handleResourceItemDownload))
	r.Post("/resources/upload", api.resourceGate(api.handleResourceUploadV2))
	// v2 写接口直接路由（发消息/已读/正在输入/红包/阅后即焚）。
	r.Post("/groups/message/send", api.handleGroupMessageSend)
	r.Post("/groups/read", api.handleGroupRead)
	r.Post("/groups/burn/open", api.handleGroupBurnOpen)
	r.Post("/groups/typing", api.handleGroupTyping)
	r.Post("/direct/send", api.handleDirectSend)
	r.Post("/direct/read", api.handleDirectRead)
	r.Post("/direct/burn/open", api.handleDirectBurnOpen)
	r.Post("/chats/typing", api.handleChatTyping)
	// 频道订阅、双水位状态、增量拉帖和按可见序号已读。
	r.Get("/channels/discover", api.handleChannelDiscover)
	r.Post("/channels/posts/send", api.handleChannelPostSend)
	r.Post("/channels/subscribe", api.handleChannelSubscribe)
	r.Post("/channels/unsubscribe", api.handleChannelUnsubscribe)
	r.Get("/channels/state", api.handleChannelState)
	r.Get("/channels/states", api.handleChannelStates)
	r.Get("/channels/posts/after", api.handleChannelPostsAfter)
	r.Get("/channels/events/after", api.handleChannelEventsAfter)
	r.Post("/channels/read", api.handleChannelRead)
	r.Post("/channels/notifications", api.handleChannelNotifications)
	r.Post("/channels/reactions/toggle", api.handleChannelReactionToggle)
	r.Post("/channel-api/apply", api.handleChannelAPIApply)
	r.Get("/channel-api/status", api.handleChannelAPIStatus)
	r.Post("/redpackets/send", api.handleRedPacketSend)
	r.Post("/redpackets/claim", api.handleRedPacketClaim)
	// v2 好友接口直接路由。
	r.Get("/friends/requests", api.handleFriendRequests)
	r.Post("/friends/request", api.handleFriendRequest)
	r.Post("/friends/respond", api.handleFriendRespond)
	r.Post("/friends/remark", api.handleFriendRemark)
	r.Post("/friends/delete", api.handleFriendDelete)
	// v2 群管理接口直接路由。
	r.Post("/groups/create", api.handleGroupCreate)
	r.Post("/groups/join", api.handleGroupJoin)
	r.Get("/groups/list", api.handleGroupList)
	r.Get("/groups/members", api.handleGroupMembers)
	r.Get("/groups/members/lookup", api.handleGroupMembersLookup)
	r.Get("/groups/requests", api.handleGroupJoinRequests)
	r.Post("/groups/leave", api.handleGroupLeave)
	// v2 个人中心直接路由。
	r.Post("/me/uid", api.handleUpdateUID)
	r.Post("/me/profile", api.handleUpdateProfile)
	r.Post("/me/password", api.handleUpdatePassword)
	r.Post("/me/delete/email/send", api.handleSendDeleteAccountEmailCode)
	r.Post("/me/delete", api.handleDeleteAccount)
	r.Get("/me/group-invite-preference", api.handleGetGroupInvitePreference)
	r.Post("/me/group-invite-preference", api.handleSetGroupInvitePreference)
	r.Post("/me/avatar", api.handleAvatarUpload)
	r.Post("/me/cover", api.handleCoverUpload)
	r.Get("/users/profile", api.handleUserProfile)
	r.Get("/friends", api.handleFriendList)
	// v2 群管理剩余直接路由。
	r.Post("/groups/approve", api.handleGroupApprove)
	r.Post("/groups/invite", api.handleGroupInvite)
	r.Get("/groups/invitations", api.handleGroupInvitations)
	r.Post("/groups/invitations/respond", api.handleGroupInvitationRespond)
	r.Post("/groups/admin", api.handleGroupAdmin)
	r.Post("/groups/avatar", api.handleGroupAvatar)
	r.Post("/groups/kick", api.handleGroupKick)
	r.Post("/groups/name", api.handleGroupRename)
	r.Post("/groups/settings", api.handleGroupSettings)
	r.Post("/groups/announcement", api.handleGroupAnnouncement)
	r.Post("/groups/announcement/read", api.handleGroupAnnouncementRead)
	r.Post("/groups/dissolve", api.handleGroupDissolve)
	// v2 签到/在线/设备/举报查询直接路由。
	r.Post("/me/checkin", api.handleMeCheckIn)
	r.Get("/me/scratch", api.handleMeScratchStatus)
	r.Post("/me/scratch", api.handleMeScratch)
	r.Post("/me/presence", api.handleUpdatePresence)
	r.Get("/me/devices", api.handleMeDevices)
	r.Post("/me/devices/cleanup", api.handleMeDevicesCleanupOthers)
	r.Get("/me/bug-reports", api.handleMeBugReports)
	r.Get("/me/user-reports", api.handleMeUserReports)
	r.Get("/me/group-reports", api.handleMeGroupReports)
	r.Get("/notifications", api.handleNotificationList)
	// v2 朋友圈 (moments) 直接路由。
	r.Post("/moments", api.handleMomentCreate)
	r.Get("/moments/feed", api.handleMomentFeed)
	r.Get("/moments/user", api.handleMomentUserFeed)
	r.Post("/moments/like", api.handleMomentLike)
	r.Post("/moments/unlike", api.handleMomentUnlike)
	r.Post("/moments/delete", api.handleMomentDelete)
	r.Post("/moments/comment", api.handleMomentComment)
	r.Post("/moments/comment/delete", api.handleMomentCommentDelete)
	r.Get("/moments/comments", api.handleMomentComments)
	// v2 小程序商店直接路由。
	r.Get("/cip/store", api.handleCipStoreList)
	r.Get("/cip/store/download/{id}", api.handleCipStoreDownload)
}

func (api *API) registerV1Routes(r chi.Router) {
	r.Use(api.secureMiddleware)
	r.Group(func(r chi.Router) {
		r.Use(api.authMiddleware)
		r.Get("/updates/difference", api.handleUpdatesDifference)
		r.Get("/groups/events/after", api.handleGroupEventsAfter)
	})
	r.Post("/auth/web/register", api.handleWebRegister)
	r.Post("/auth/register", api.handleLegacyRegister)
	r.Post("/auth/login", api.handleLogin)
	r.Post("/auth/direct-create", api.handleDirectCreateUser)
	r.Post("/auth/password/reset", api.handleResetPassword)
	r.Post("/auth/handshake", api.handleHandshake)
	r.Get("/auth/email/send", api.handleEmailCode)
	r.Post("/auth/email/send", api.handleEmailCode)
	r.Post("/auth/refresh", api.handleRefresh)
	r.Post("/auth/logout", api.handleLogout)
	r.Get("/ws", api.handleWS)
	r.Get("/music/cover/*", api.handleMusicCoverProxy)
	// 频道媒体改为签名下载，阻断旧公开静态目录访问。
	r.Handle("/uploads/channel-media/*", http.NotFoundHandler())
	v1UploadsHandler := withStaticMediaCache(http.StripPrefix("/v1/uploads/", http.FileServer(http.Dir(api.cfg.UploadDir))))
	v1UploadsHandler = ossRedirectHandler(api.cfg.UploadDir, api.ossClient, v1UploadsHandler)
	v1UploadsHandler = withProtectedUploadPaths(api.authMiddleware, v1UploadsHandler)
	r.Handle("/uploads/*", withMediaDownloadLimit(func() bool { return api.cfg.VideoEnabled }, v1UploadsHandler))
	r.Post("/external/groups", api.handleExternalGroupList)
	r.Post("/external/friends", api.handleExternalFriendList)
	r.Post("/external/direct/send", api.handleExternalDirectSend)
	r.Post("/external/group/send", api.handleExternalGroupSend)
	r.Post("/external/coin/pay", api.handleExternalCoinPay)
	r.Post("/external/coin/verify", api.handleExternalCoinVerify)

	r.Group(func(r chi.Router) {
		r.Use(api.authMiddleware)
		r.Get("/me", api.handleMe)
		// v2test78 频道路径兼容：该版本误走 /v1/channels/*，避免持续 404。
		r.Get("/channels/discover", api.handleChannelDiscover)
		r.Post("/channels/posts/send", api.handleChannelPostSend)
		r.Post("/channels/media/upload", api.handleChannelMediaUpload)
		r.Post("/channels/subscribe", api.handleChannelSubscribe)
		r.Post("/channels/unsubscribe", api.handleChannelUnsubscribe)
		r.Get("/channels/state", api.handleChannelState)
		r.Get("/channels/states", api.handleChannelStates)
		r.Get("/channels/posts/after", api.handleChannelPostsAfter)
		r.Get("/channels/events/after", api.handleChannelEventsAfter)
		r.Post("/channels/read", api.handleChannelRead)
		r.Post("/channels/notifications", api.handleChannelNotifications)
		r.Post("/channels/reactions/toggle", api.handleChannelReactionToggle)
		r.Get("/ai/quota", api.handleAIQuota)
		r.Post("/ai/chat/completions", api.handleAIChatCompletions)
		r.Post("/chat/completions", api.handleAIChatCompletions) // OpenAI 兼容别名
		r.Post("/me/checkin", api.handleMeCheckIn)
		r.Get("/me/scratch", api.handleMeScratchStatus)
		r.Post("/me/scratch", api.handleMeScratch)
		r.Get("/me/checkin/wall", api.handleMeCheckInWall)
		r.Post("/me/checkin/wall", api.handleMeCheckInWallPost)
		r.Post("/me/checkin/wall/like", api.handleMeCheckInWallLike)
		r.Post("/me/checkin/wall/unlike", api.handleMeCheckInWallUnlike)
		r.Post("/me/checkin/wall/comment", api.handleMeCheckInWallComment)
		r.Get("/me/checkin/wall/comments", api.handleMeCheckInWallComments)
		r.Get("/me/checkin/wall/likes", api.handleMeCheckInWallLikes)
		r.Post("/me/presence", api.handleUpdatePresence)
		r.Get("/me/devices", api.handleMeDevices)
		r.Post("/me/devices/cleanup", api.handleMeDevicesCleanupOthers)
		// 兼容旧聊客户端曾使用的路径，避免“清理陌生设备”返回 404。
		r.Post("/me/devices/cleanup-others", api.handleMeDevicesCleanupOthers)
		r.Get("/me/bug-reports", api.handleMeBugReports)
		r.Get("/me/user-reports", api.handleMeUserReports)
		r.Get("/me/group-reports", api.handleMeGroupReports)
		r.Get("/me/resource-reports", api.resourceGate(api.handleMeResourceReports))
		r.Get("/reports/bug", api.handleAllBugReports)
		r.Get("/reports/user", api.handleAllUserReports)
		r.Get("/reports/group", api.handleAllGroupReports)
		r.Post("/me/uid", api.handleUpdateUID)
		r.Post("/me/profile", api.handleUpdateProfile)
		r.Post("/me/password", api.handleUpdatePassword)
		r.Post("/me/delete/email/send", api.handleSendDeleteAccountEmailCode)
		r.Post("/me/delete", api.handleDeleteAccount)
		r.Get("/me/group-invite-preference", api.handleGetGroupInvitePreference)
		r.Post("/me/group-invite-preference", api.handleSetGroupInvitePreference)
		r.Post("/me/avatar", api.handleAvatarUpload)
		r.Post("/me/cover", api.handleCoverUpload)
		r.Post("/voice/asr", api.handleVoiceASR)
		r.Post("/media", api.handleMediaUpload)
		r.Post("/files/upload", api.handleFileUploadV1)
		r.Get("/download/sources", api.handleDownloadSources)
		r.Get("/users/profile", api.handleUserProfile)
		r.Get("/friends", api.handleFriendList)
		r.Get("/friends/requests", api.handleFriendRequests)
		r.Post("/friends/request", api.handleFriendRequest)
		r.Post("/friends/respond", api.handleFriendRespond)
		r.Post("/friends/remark", api.handleFriendRemark)
		r.Post("/friends/delete", api.handleFriendDelete)
		r.Post("/resources/sections", api.resourceGate(api.handleResourceSectionCreate))
		r.Get("/resources/sections", api.resourceGate(api.handleResourceSectionList))
		r.Post("/resources/sections/delete", api.resourceGate(api.handleResourceSectionDelete))
		r.Post("/resources/upload", api.resourceGate(api.handleResourceUpload))
		r.Get("/me/resources/quota", api.resourceGate(api.handleMeResourceQuota))
		r.Get("/resources/items", api.resourceGate(api.handleResourceItems))
		r.Get("/resources/search", api.resourceGate(api.handleResourceSearch))
		r.Post("/resources/items/delete", api.resourceGate(api.handleResourceItemDelete))
		r.Post("/resources/like", api.resourceGate(api.handleResourceLike))
		r.Post("/resources/unlike", api.resourceGate(api.handleResourceUnlike))
		r.Post("/resources/comment", api.resourceGate(api.handleResourceComment))
		r.Get("/resources/comments", api.resourceGate(api.handleResourceComments))
		r.Post("/resources/comment/delete", api.resourceGate(api.handleResourceCommentDelete))
		r.Post("/resources/report", api.resourceGate(api.handleResourceReport))
		r.Post("/direct/send", api.handleDirectSend)
		r.Post("/chats/typing", api.handleChatTyping)
		r.Get("/chats/{chatId}/typing", api.handleChatTypingStatus)
		r.Post("/redpackets/send", api.handleRedPacketSend)
		r.Post("/redpackets/claim", api.handleRedPacketClaim)
		r.Get("/redpackets/{packetID}", api.handleRedPacketDetail)
		r.Get("/direct/messages/v2", api.handleDirectMessagesV2)
		r.Get("/direct/messages/search", api.handleDirectMessagesSearch)
		r.Get("/direct/messages", api.handleDirectMessages)
		r.Post("/direct/unread", api.handleDirectUnread)
		r.Post("/direct/read", api.handleDirectRead)
		r.Post("/direct/burn/open", api.handleDirectBurnOpen)
		r.Post("/direct/messages/{messageID}/transcribe", api.handleDirectMessageVoiceTranscribe)
		r.Delete("/direct/messages/{messageID}", api.handleDirectMessageDelete)
		r.Post("/groups/create", api.handleGroupCreate)
		r.Post("/groups/join", api.handleGroupJoin)
		r.Post("/groups/approve", api.handleGroupApprove)
		r.Get("/groups/list", api.handleGroupList)
		r.Get("/groups/members", api.handleGroupMembers)
		r.Get("/groups/members/lookup", api.handleGroupMembersLookup)
		r.Get("/groups/requests", api.handleGroupJoinRequests)
		r.Post("/groups/invite", api.handleGroupInvite)
		r.Get("/groups/invitations", api.handleGroupInvitations)
		r.Post("/groups/invitations/respond", api.handleGroupInvitationRespond)
		r.Post("/groups/admin", api.handleGroupAdmin)
		r.Post("/groups/avatar", api.handleGroupAvatar)
		r.Post("/groups/kick", api.handleGroupKick)
		r.Post("/groups/name", api.handleGroupRename)
		r.Post("/groups/settings", api.handleGroupSettings)
		r.Post("/groups/announcement", api.handleGroupAnnouncement)
		r.Post("/groups/announcement/read", api.handleGroupAnnouncementRead)
		r.Post("/groups/leave", api.handleGroupLeave)
		r.Post("/groups/dissolve", api.handleGroupDissolve)
		r.Post("/groups/typing", api.handleGroupTyping)
		r.Get("/groups/{groupId}/typing", api.handleGroupTypingStatus)
		r.Post("/groups/message/send", api.handleGroupMessageSend)
		r.Post("/groups/unread", api.handleGroupUnread)
		r.Post("/groups/read", api.handleGroupRead)
		r.Post("/groups/burn/open", api.handleGroupBurnOpen)
		r.Post("/groups/messages/{messageID}/transcribe", api.handleGroupMessageVoiceTranscribe)
		r.Get("/groups/messages/v2", api.handleGroupMessagesV2)
		r.Get("/groups/messages/after", api.handleGroupMessagesAfterSeq)
		r.Get("/groups/messages/search", api.handleGroupMessagesSearch)
		r.Get("/groups/messages", api.handleGroupMessages)
		r.Delete("/groups/messages/{messageID}", api.handleGroupMessageDelete)
		r.Post("/moments", api.handleMomentCreate)
		r.Get("/moments", api.handleMomentFeed)
		r.Get("/moments/v2", api.handleMomentFeedV2)
		r.Get("/moments/user", api.handleMomentUserFeed)
		r.Post("/moments/like", api.handleMomentLike)
		r.Post("/moments/unlike", api.handleMomentUnlike)
		r.Post("/moments/delete", api.handleMomentDelete)
		r.Post("/moments/comment", api.handleMomentComment)
		r.Post("/moments/comment/delete", api.handleMomentCommentDelete)
		r.Get("/moments/comments", api.handleMomentComments)
		r.Get("/discover/lua/manifest", api.handleLuaAppManifest)
		r.Get("/discover/lua/apps/{id}", api.handleLuaAppScript)
		r.Get("/discover/lua/assets/*", api.handleLuaAppAsset)
		r.Get("/emoji/plaza", api.handleEmojiPlazaList)
		r.Get("/emoji/plaza/mine", api.handleEmojiPlazaMineList)
		r.Post("/emoji/plaza/upload", api.handleEmojiPlazaUpload)
		r.Post("/emoji/plaza/save", api.handleEmojiPlazaSave)
		r.Post("/emoji/plaza/delete", api.handleEmojiPlazaDelete)
		r.Get("/music/plaza", api.musicGate(api.handleMusicPlazaList))
		r.Get("/music/plaza/detail", api.musicGate(api.handleMusicPlazaDetail))
		r.Get("/music/plaza/mine", api.musicGate(api.handleMusicPlazaMineList))
		r.Post("/music/plaza/upload", api.musicGate(api.handleMusicPlazaUpload))
		r.Post("/music/plaza/update", api.musicGate(api.handleMusicPlazaUpdate))
		r.Post("/music/plaza/lyrics", api.musicGate(api.handleMusicPlazaLyricsUpload))
		r.Post("/music/plaza/delete", api.musicGate(api.handleMusicPlazaDelete))
		r.Post("/music/plaza/mine/delete-batch", api.musicGate(api.handleMusicPlazaMineBatchDelete))
		r.Post("/music/plaza/like", api.musicGate(api.handleMusicPlazaLike))
		r.Post("/music/plaza/unlike", api.musicGate(api.handleMusicPlazaUnlike))
		r.Post("/music/plaza/comment", api.musicGate(api.handleMusicPlazaComment))
		r.Post("/music/plaza/comment/delete", api.musicGate(api.handleMusicPlazaCommentDelete))
		r.Get("/music/plaza/comments", api.musicGate(api.handleMusicPlazaComments))
		r.Post("/music/plaza/play", api.musicGate(api.handleMusicPlazaPlay))
		r.Get("/music/plaza/ranking", api.musicGate(api.handleMusicPlazaRanking))
		r.Get("/music/playlists", api.musicGate(api.handlePlaylistList))
		r.Post("/music/playlists/sync", api.musicGate(api.handlePlaylistSync))
		r.Get("/favorites", api.handleFavoriteList)
		r.Post("/favorites/add", api.handleFavoriteAdd)
		r.Post("/favorites/remove", api.handleFavoriteRemove)
		r.Post("/reports/user", api.handleUserReport)
		r.Get("/public-court/cases", api.handlePublicCourtCases)
		r.Get("/public-court/cases/{caseID}", api.handlePublicCourtCaseDetail)
		r.Get("/public-court/cases/{caseID}/votes", api.handlePublicCourtCaseVotes)
		r.Get("/public-court/cases/{caseID}/discussions", api.handlePublicCourtCaseDiscussions)
		r.Get("/public-court/cases/{caseID}/chat-context", api.handlePublicCourtCaseChatContext)
		r.Get("/public-court/chat-context", api.handlePublicCourtCaseChatContext)
		r.Post("/public-court/cases/{caseID}/vote", api.handlePublicCourtCaseVote)
		r.Post("/public-court/cases/{caseID}/statement", api.handlePublicCourtCaseStatement)
		r.Post("/public-court/cases/{caseID}/discussion", api.handlePublicCourtCaseDiscussion)
		r.Post("/public-court/cases/{caseID}/withdraw", api.handlePublicCourtCaseWithdraw)
		r.Post("/reports/group", api.handleGroupReport)
		r.Post("/admins/crash-reports", api.handleSubmitCrashReport)
		r.Post("/feedback", api.handleSubmitBugReport)
		r.Get("/notifications", api.handleNotificationList)
	})
}

func (a *API) toUserResponse(u *data.User) userResponse {
	return toUserResponse(u)
}

func (a *API) toSelfUserResponse(u *data.User) selfUserResponse {
	return toSelfUserResponse(u)
}

func (a *API) resourceGate(handler http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.cfg.ResourceSquareEnabled {
			writeError(w, http.StatusNotFound, "not_found", "not found")
			return
		}
		handler(w, r)
	}
}

func (a *API) resourceGateHandler(handler http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !a.cfg.ResourceSquareEnabled {
			writeError(w, http.StatusNotFound, "not_found", "not found")
			return
		}
		handler.ServeHTTP(w, r)
	})
}

func (a *API) musicGate(handler http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !a.cfg.MusicPlazaEnabled {
			writeError(w, http.StatusNotFound, "not_found", "not found")
			return
		}
		handler(w, r)
	}
}
