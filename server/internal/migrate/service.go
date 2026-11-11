package migrate

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/official"
)

// 任务状态。
const (
	StatusQueued  = "queued"
	StatusRunning = "running"
	StatusDone    = "done"
	StatusFailed  = "failed"
)

const (
	pageSize   = 50
	pageDelay  = 120 * time.Millisecond
	jobTimeout = 30 * time.Minute
)

type Options struct {
	SourceHost         string
	IncludeGroups      bool
	MaxMessagesPerChat int
}

type Credentials struct {
	Username string
	Password string
}

type Service struct {
	store     *Store
	opts      Options
	mu        sync.Mutex
	running   map[string]struct{}
	newClient func(base string) *official.Client
}

func NewService(db *sqlx.DB, opts Options) *Service {
	return &Service{
		store:     NewStore(db),
		opts:      opts,
		running:   make(map[string]struct{}),
		newClient: official.New,
	}
}

func (s *Service) Store() *Store { return s.store }

// Start 在后台跑一次迁移任务，同一任务重复启动会被忽略。
func (s *Service) Start(jobID string, creds Credentials) {
	s.mu.Lock()
	if _, ok := s.running[jobID]; ok {
		s.mu.Unlock()
		return
	}
	s.running[jobID] = struct{}{}
	s.mu.Unlock()

	go func() {
		defer func() {
			s.mu.Lock()
			delete(s.running, jobID)
			s.mu.Unlock()
		}()
		ctx, cancel := context.WithTimeout(context.Background(), jobTimeout)
		defer cancel()
		if err := s.Run(ctx, jobID, creds); err != nil {
			log.Printf("数据迁移失败 job=%s: %v", jobID, err)
		}
	}()
}

// Run 同步执行迁移，便于测试。
func (s *Service) Run(ctx context.Context, jobID string, creds Credentials) error {
	job, err := s.store.GetJob(ctx, jobID)
	if err != nil {
		return err
	}
	stats := map[string]int64{}
	fail := func(stage string, err error) error {
		msg := friendlyError(err)
		s.store.SetStatus(ctx, jobID, StatusFailed, stage, msg)
		s.store.AddEvent(ctx, jobID, "error", stage, msg)
		return err
	}

	s.store.SetStatus(ctx, jobID, StatusRunning, "登录旧服务器", "")
	s.store.AddEvent(ctx, jobID, "info", "登录", "正在登录 "+job.SourceHost+"，密码只保存在内存，不写入数据库与日志")

	client := s.newClient(job.SourceHost)
	profile, err := client.Login(ctx, creds.Username, creds.Password, "community-migrate-"+jobID)
	if err != nil {
		return fail("登录旧服务器", err)
	}
	officialUID := firstNonEmpty(profile.UID, profile.Ncuid)
	if officialUID == "" {
		return fail("登录旧服务器", errors.New("旧服务器没有返回账号 UID"))
	}
	s.store.AddEvent(ctx, jobID, "ok", "登录", fmt.Sprintf("登录成功：%s（%s）", displayNameOf(profile), officialUID))

	// 阶段 1：本人资料。
	s.store.UpdateProgress(ctx, jobID, "读取账号资料", 5, stats)
	if me, err := client.Me(ctx); err == nil {
		profile = me
	}
	s.store.AddEvent(ctx, jobID, "ok", "账号资料", fmt.Sprintf("已读取资料：昵称 %s，旧聊 ID %s，社区账号沿用当前昵称", displayNameOf(profile), officialUID))

	// 阶段 2：好友列表，先为对方建占位账号。
	s.store.UpdateProgress(ctx, jobID, "迁移好友列表", 10, stats)
	s.store.AddEvent(ctx, jobID, "info", "好友", "正在拉取好友列表")
	friends, err := client.Friends(ctx)
	if err != nil {
		return fail("迁移好友列表", err)
	}
	type peer struct {
		officialUID string
		userID      string
		name        string
	}
	peers := make([]peer, 0, len(friends))
	for i, f := range friends {
		uid := firstNonEmpty(f.UID, f.Ncuid)
		if uid == "" {
			continue
		}
		id, wasCreated, err := s.store.UpsertShadowUser(ctx, ShadowProfile{
			OfficialUID: uid,
			Username:    f.Username,
			DisplayName: f.DisplayName,
			AvatarURL:   f.AvatarURL,
		})
		if err != nil {
			s.store.AddEvent(ctx, jobID, "warn", "好友", fmt.Sprintf("跳过 %s：%v", firstNonEmpty(f.DisplayName, uid), err))
			continue
		}
		if id == job.UserID {
			continue
		}
		if err := s.store.AddFriendPair(ctx, job.UserID, id, f.RemarkName); err != nil {
			s.store.AddEvent(ctx, jobID, "warn", "好友", fmt.Sprintf("好友关系建立失败 %s：%v", firstNonEmpty(f.DisplayName, uid), err))
			continue
		}
		name := firstNonEmpty(f.RemarkName, f.DisplayName, f.Username, uid)
		peers = append(peers, peer{officialUID: uid, userID: id, name: name})
		stats["friends"]++
		stats["shadow_users"] += b2i(wasCreated)
		s.store.AddEvent(ctx, jobID, "info", "好友", fmt.Sprintf("好友 %s（%d/%d）%s", name, i+1, len(friends), shadowNote(wasCreated)))
		s.store.UpdateProgress(ctx, jobID, fmt.Sprintf("迁移好友列表（%d/%d）", i+1, len(friends)),
			10+int(float64(i+1)/float64(max(len(friends), 1))*8), stats)
	}
	s.store.AddEvent(ctx, jobID, "ok", "好友", fmt.Sprintf("好友列表完成：%d 位，其中新建占位账号 %d 个", stats["friends"], stats["shadow_users"]))

	// 阶段 3：私聊。
	if len(peers) > 0 {
		s.store.AddEvent(ctx, jobID, "info", "私聊", fmt.Sprintf("开始迁移私聊，共 %d 个会话", len(peers)))
	}
	var mediaSkipped int64
	for i, p := range peers {
		if ctx.Err() != nil {
			return fail("迁移私聊", ctx.Err())
		}
		basePct := 20 + int(float64(i)/float64(len(peers))*50)
		count, media, err := s.importDirect(ctx, client, job.UserID, officialUID, p.officialUID, p.userID, func(n int) {
			s.store.UpdateProgress(ctx, jobID, fmt.Sprintf("迁移与 %s 的私聊，本会话已导入 %d 条", p.name, n), basePct, stats)
		})
		stats["direct_messages"] += int64(count)
		mediaSkipped += media
		if err != nil {
			s.store.AddEvent(ctx, jobID, "warn", "私聊", fmt.Sprintf("与 %s 的会话中断：%v", p.name, err))
		}
		s.store.AddEvent(ctx, jobID, "ok", "私聊", fmt.Sprintf("与 %s 的私聊完成：%d 条消息", p.name, count))
		s.store.UpdateProgress(ctx, jobID, fmt.Sprintf("迁移私聊（%d/%d）", i+1, len(peers)),
			20+int(float64(i+1)/float64(len(peers))*50), stats)
	}
	if mediaSkipped > 0 {
		stats["media_skipped"] = mediaSkipped
		s.store.AddEvent(ctx, jobID, "warn", "媒体", fmt.Sprintf("%d 条消息带媒体文件，社区版不接对象存储，图片语音会显示为空", mediaSkipped))
	}

	// 阶段 4：群聊。
	if s.opts.IncludeGroups {
		s.store.UpdateProgress(ctx, jobID, "迁移群聊", 72, stats)
		s.store.AddEvent(ctx, jobID, "info", "群聊", "正在拉取群列表")
		groups, err := client.Groups(ctx)
		if err != nil {
			s.store.AddEvent(ctx, jobID, "warn", "群聊", fmt.Sprintf("群列表读取失败，跳过群聊：%v", err))
		} else {
			s.store.AddEvent(ctx, jobID, "info", "群聊", fmt.Sprintf("共 %d 个群，逐个迁移", len(groups)))
			for i, g := range groups {
				if ctx.Err() != nil {
					return fail("迁移群聊", ctx.Err())
				}
				groupID, _, err := s.store.EnsureImportedGroup(ctx, g.GroupID, g.Name, g.AvatarURL, job.UserID)
				if err != nil {
					s.store.AddEvent(ctx, jobID, "warn", "群聊", fmt.Sprintf("群 %s 创建失败：%v", g.Name, err))
					continue
				}
				if err := s.store.AddGroupMember(ctx, groupID, job.UserID, g.Role); err != nil {
					s.store.AddEvent(ctx, jobID, "warn", "群聊", fmt.Sprintf("群 %s 成员关系写入失败：%v", g.Name, err))
				}
				s.store.AddEvent(ctx, jobID, "info", "群聊", fmt.Sprintf("正在迁移群 %s（%d/%d）", g.Name, i+1, len(groups)))
				groupPct := 72 + int(float64(i)/float64(max(len(groups), 1))*26)
				count, err := s.importGroup(ctx, client, job.UserID, officialUID, groupID, g.GroupID, func(n int) {
					s.store.UpdateProgress(ctx, jobID, fmt.Sprintf("迁移群 %s，本群已导入 %d 条", g.Name, n), groupPct, stats)
				})
				stats["group_messages"] += int64(count)
				if err != nil {
					s.store.AddEvent(ctx, jobID, "warn", "群聊", fmt.Sprintf("群 %s 消息中断：%v", g.Name, err))
				}
				s.store.RefreshGroupSequence(ctx, groupID)
				stats["groups"]++
				s.store.AddEvent(ctx, jobID, "ok", "群聊", fmt.Sprintf("群 %s 完成：%d 条消息", g.Name, count))
				s.store.UpdateProgress(ctx, jobID, fmt.Sprintf("迁移群聊（%d/%d）", i+1, len(groups)),
					72+int(float64(i+1)/float64(max(len(groups), 1))*26), stats)
			}
		}
	}

	s.store.UpdateProgress(ctx, jobID, "迁移完成", 100, stats)
	s.store.SetStatus(ctx, jobID, StatusDone, "迁移完成", "")
	s.store.AddEvent(ctx, jobID, "ok", "完成", fmt.Sprintf(
		"全部完成：好友 %d，私聊消息 %d，群 %d，群消息 %d",
		stats["friends"], stats["direct_messages"], stats["groups"], stats["group_messages"]))
	return nil
}

func (s *Service) importDirect(ctx context.Context, client *official.Client, selfID, selfOfficialUID, peerOfficialUID, peerID string, onPage func(int)) (int, int64, error) {
	threadID, err := s.store.EnsureThread(context.WithoutCancel(ctx), selfID, peerID)
	if err != nil {
		return 0, 0, err
	}
	var before int64
	var inserted int64
	var media int64
	for {
		if err := ctx.Err(); err != nil {
			return int(inserted), media, err
		}
		if s.opts.MaxMessagesPerChat > 0 && inserted >= int64(s.opts.MaxMessagesPerChat) {
			break
		}
		page, err := client.DirectMessages(ctx, peerOfficialUID, pageSize, before)
		if err != nil {
			return int(inserted), media, err
		}
		if len(page.Messages) == 0 {
			break
		}
		var oldest int64
		for _, m := range page.Messages {
			if m.CreatedAt <= 0 {
				continue
			}
			if s.opts.MaxMessagesPerChat > 0 && inserted >= int64(s.opts.MaxMessagesPerChat) {
				break
			}
			senderID := peerID
			if firstNonEmpty(m.FromUID, m.FromNcuid) == selfOfficialUID {
				senderID = selfID
			}
			if oldest == 0 || m.CreatedAt < oldest {
				oldest = m.CreatedAt
			}
			ok, err := s.store.InsertDirectMessage(context.WithoutCancel(ctx), threadID, senderID, m.ID, m.Body,
				m.MsgType, m.MediaURL, m.ThumbURL, m.OriginalURL, m.DurationMS, m.CreatedAt)
			if err != nil {
				return int(inserted), media, err
			}
			if ok {
				inserted++
			}
			if m.MediaURL != "" || m.ThumbURL != "" {
				media++
			}
		}
		if onPage != nil {
			onPage(int(inserted))
		}
		if !page.HasMore || oldest <= 0 {
			break
		}
		before = oldest
		time.Sleep(pageDelay)
	}
	return int(inserted), media, nil
}

func (s *Service) importGroup(ctx context.Context, client *official.Client, selfID, selfOfficialUID, groupID, officialGroupID string, onPage func(int)) (int, error) {
	var before int64
	var inserted int64
	for {
		if err := ctx.Err(); err != nil {
			return int(inserted), err
		}
		if s.opts.MaxMessagesPerChat > 0 && inserted >= int64(s.opts.MaxMessagesPerChat) {
			break
		}
		page, err := client.GroupMessages(ctx, officialGroupID, pageSize, before)
		if err != nil {
			return int(inserted), err
		}
		if len(page.Messages) == 0 {
			break
		}
		var oldest int64
		for _, m := range page.Messages {
			if m.CreatedAt <= 0 {
				continue
			}
			if s.opts.MaxMessagesPerChat > 0 && inserted >= int64(s.opts.MaxMessagesPerChat) {
				break
			}
			uid := firstNonEmpty(m.FromUID, m.FromNcuid)
			senderID := selfID
			if uid != "" && uid != selfOfficialUID {
				id, _, err := s.store.UpsertShadowUser(context.WithoutCancel(ctx), ShadowProfile{
					OfficialUID: uid,
					DisplayName: m.FromName,
				})
				if err != nil {
					continue
				}
				senderID = id
			}
			if oldest == 0 || m.CreatedAt < oldest {
				oldest = m.CreatedAt
			}
			ok, err := s.store.InsertGroupMessage(context.WithoutCancel(ctx), groupID, senderID, m.ID, m.Body,
				m.MsgType, m.MediaURL, m.ThumbURL, m.OriginalURL, m.DurationMS, m.CreatedAt)
			if err != nil {
				return int(inserted), err
			}
			if ok {
				inserted++
			}
		}
		if onPage != nil {
			onPage(int(inserted))
		}
		if !page.HasMore || oldest <= 0 {
			break
		}
		before = oldest
		time.Sleep(pageDelay)
	}
	return int(inserted), nil
}

func friendlyError(err error) string {
	if errors.Is(err, official.ErrUnauthorized) {
		return "旧服务器拒绝了这组账号密码，请确认账号可用"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "迁移超时，已导入的数据会保留，可重新发起一次补齐"
	}
	return err.Error()
}

func displayNameOf(p official.Profile) string {
	return firstNonEmpty(p.DisplayName, p.Username, p.UID)
}

func shadowNote(created bool) string {
	if created {
		return "（新建占位账号）"
	}
	return ""
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func b2i(v bool) int64 {
	if v {
		return 1
	}
	return 0
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func sanitizeUID(raw string) string {
	var b strings.Builder
	for _, r := range raw {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '_':
			b.WriteRune(r)
		}
		if b.Len() >= 20 {
			break
		}
	}
	return b.String()
}

func randomSuffix() string {
	raw := make([]byte, 6)
	if _, err := rand.Read(raw); err != nil {
		return strconv.FormatInt(time.Now().UnixNano()%1000000, 10)
	}
	return strings.ToLower(base64.RawURLEncoding.EncodeToString(raw))
}

func newID() string {
	raw := make([]byte, 12)
	if _, err := rand.Read(raw); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return base64.RawURLEncoding.EncodeToString(raw)
}
