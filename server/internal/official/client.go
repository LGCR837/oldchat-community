package official

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ErrUnauthorized 表示旧服务器拒绝了这组账号密码。
var ErrUnauthorized = errors.New("official: unauthorized")

// Client 是对旧（官方）服务器的只读代理客户端。
// 只发明文 JSON 请求：服务端的加密中间件是可选启用的，不带 X-Enc 即走明文通道，
// 因此这里不需要实现 ECDH 握手。
type Client struct {
	base  string
	hc    *http.Client
	token string
}

type Profile struct {
	UID             string `json:"uid"`
	Ncuid           string `json:"ncuid"`
	Username        string `json:"username"`
	DisplayName     string `json:"display_name"`
	UserTitle       string `json:"user_title"`
	AvatarURL       string `json:"avatar_url"`
	Signature       string `json:"signature"`
	CoverURL        string `json:"cover_url"`
	CoinBalance     *int   `json:"coin_balance"`
	ReputationScore *int   `json:"reputation_score"`
}

type Friend struct {
	UID         string `json:"uid"`
	Ncuid       string `json:"ncuid"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	RemarkName  string `json:"remark_name"`
	UserTitle   string `json:"user_title"`
	AvatarURL   string `json:"avatar_url"`
}

type DirectMessage struct {
	ID          string `json:"id"`
	FromUID     string `json:"from_uid"`
	FromNcuid   string `json:"from_ncuid"`
	Body        string `json:"body"`
	MsgType     string `json:"msg_type"`
	MediaURL    string `json:"media_url"`
	ThumbURL    string `json:"thumb_url"`
	OriginalURL string `json:"original_url"`
	DurationMS  int    `json:"duration_ms"`
	CreatedAt   int64  `json:"created_at"`
}

type DirectPage struct {
	Messages []DirectMessage `json:"messages"`
	HasMore  bool            `json:"has_more"`
}

type Group struct {
	GroupID     string `json:"group_id"`
	Name        string `json:"name"`
	AvatarURL   string `json:"avatar_url"`
	Role        int16  `json:"role"`
	MemberCount int    `json:"member_count"`
}

type GroupMessage struct {
	ID          string `json:"id"`
	GroupID     string `json:"group_id"`
	FromUID     string `json:"from_uid"`
	FromNcuid   string `json:"from_ncuid"`
	FromName    string `json:"from_name"`
	Body        string `json:"body"`
	MsgType     string `json:"msg_type"`
	MediaURL    string `json:"media_url"`
	ThumbURL    string `json:"thumb_url"`
	OriginalURL string `json:"original_url"`
	DurationMS  int    `json:"duration_ms"`
	CreatedAt   int64  `json:"created_at"`
}

type GroupPage struct {
	Messages []GroupMessage `json:"messages"`
	HasMore  bool           `json:"has_more"`
}

func New(base string) *Client {
	return &Client{
		base: strings.TrimRight(strings.TrimSpace(base), "/"),
		hc:   &http.Client{Timeout: 30 * time.Second},
	}
}

type apiError struct {
	Code    string `json:"error"`
	Message string `json:"message"`
}

func (c *Client) do(ctx context.Context, method, path string, body interface{}, out interface{}) error {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return err
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, reader)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return fmt.Errorf("official: 请求失败: %w", err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if err != nil {
		return fmt.Errorf("official: 读取响应失败: %w", err)
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return ErrUnauthorized
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		var e apiError
		if json.Unmarshal(raw, &e) == nil && e.Message != "" {
			return fmt.Errorf("official: %s", e.Message)
		}
		return fmt.Errorf("official: HTTP %d", resp.StatusCode)
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return fmt.Errorf("official: 响应格式异常: %w", err)
	}
	return nil
}

// Login 代理登录旧服务器，成功后客户端携带该 token。
func (c *Client) Login(ctx context.Context, username, password, deviceID string) (Profile, error) {
	payload := map[string]interface{}{
		"identifier":  username,
		"username":    username,
		"password":    password,
		"device_id":   deviceID,
		"device_name": "community-migrate",
		"platform":    "web",
		"app_version": "1.4.5",
	}
	var resp struct {
		AccessToken string  `json:"access_token"`
		User        Profile `json:"user"`
	}
	if err := c.do(ctx, http.MethodPost, "/v1/auth/login", payload, &resp); err != nil {
		return Profile{}, err
	}
	if resp.AccessToken == "" {
		return Profile{}, errors.New("official: 登录响应缺少 access_token")
	}
	c.token = resp.AccessToken
	return resp.User, nil
}

func (c *Client) Me(ctx context.Context) (Profile, error) {
	var p Profile
	if err := c.do(ctx, http.MethodGet, "/v1/me", nil, &p); err != nil {
		return Profile{}, err
	}
	return p, nil
}

func (c *Client) Friends(ctx context.Context) ([]Friend, error) {
	var resp struct {
		Friends []Friend `json:"friends"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/friends", nil, &resp); err != nil {
		return nil, err
	}
	return resp.Friends, nil
}

func (c *Client) DirectMessages(ctx context.Context, withUID string, limit int, before int64) (DirectPage, error) {
	q := url.Values{}
	q.Set("with_uid", withUID)
	if limit <= 0 {
		limit = 50
	}
	q.Set("limit", strconv.Itoa(limit))
	if before > 0 {
		q.Set("before", strconv.FormatInt(before, 10))
	}
	var page DirectPage
	if err := c.do(ctx, http.MethodGet, "/v1/direct/messages?"+q.Encode(), nil, &page); err != nil {
		return DirectPage{}, err
	}
	return page, nil
}

// Moment 是旧站朋友圈条目，image_url 在单图时是地址，多图时是 JSON 数组。
type Moment struct {
	ID        string `json:"id"`
	FromUID   string `json:"from_uid"`
	FromNcuid string `json:"from_ncuid"`
	Body      string `json:"body"`
	ImageURL  string `json:"image_url"`
	CreatedAt int64  `json:"created_at"`
	Likes     int    `json:"likes"`
	Comments  int    `json:"comments"`
	Liked     bool   `json:"liked"`
}

// MomentComment 是朋友圈下的一条评论。
type MomentComment struct {
	ID        string `json:"id"`
	MomentID  string `json:"moment_id"`
	FromUID   string `json:"from_uid"`
	FromNcuid string `json:"from_ncuid"`
	FromName  string `json:"from_name"`
	Body      string `json:"body"`
	CreatedAt int64  `json:"created_at"`
}

// UserMoments 拉取某个账号的朋友圈，按时间倒序，用 before 往前翻。
func (c *Client) UserMoments(ctx context.Context, ncuid, uid string, limit int, before int64) ([]Moment, error) {
	q := url.Values{}
	if strings.TrimSpace(ncuid) != "" {
		q.Set("ncuid", strings.TrimSpace(ncuid))
	} else {
		q.Set("uid", strings.ToUpper(strings.TrimSpace(uid)))
	}
	if limit <= 0 {
		limit = 50
	}
	q.Set("limit", strconv.Itoa(limit))
	if before > 0 {
		q.Set("before", strconv.FormatInt(before, 10))
	}
	var resp struct {
		Moments []Moment `json:"moments"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/moments/user?"+q.Encode(), nil, &resp); err != nil {
		return nil, err
	}
	return resp.Moments, nil
}

// MomentComments 拉取一条朋友圈的评论。
func (c *Client) MomentComments(ctx context.Context, momentID string, limit int, before int64) ([]MomentComment, error) {
	q := url.Values{}
	q.Set("moment_id", momentID)
	if limit <= 0 {
		limit = 100
	}
	q.Set("limit", strconv.Itoa(limit))
	if before > 0 {
		q.Set("before", strconv.FormatInt(before, 10))
	}
	var resp struct {
		Comments []MomentComment `json:"comments"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/moments/comments?"+q.Encode(), nil, &resp); err != nil {
		return nil, err
	}
	return resp.Comments, nil
}

func (c *Client) Groups(ctx context.Context) ([]Group, error) {
	var resp struct {
		Groups []Group `json:"groups"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/groups/list", nil, &resp); err != nil {
		return nil, err
	}
	return resp.Groups, nil
}

func (c *Client) GroupMessages(ctx context.Context, groupID string, limit int, before int64) (GroupPage, error) {
	q := url.Values{}
	q.Set("group_id", groupID)
	if limit <= 0 {
		limit = 50
	}
	q.Set("limit", strconv.Itoa(limit))
	if before > 0 {
		q.Set("before", strconv.FormatInt(before, 10))
	}
	var page GroupPage
	if err := c.do(ctx, http.MethodGet, "/v1/groups/messages?"+q.Encode(), nil, &page); err != nil {
		return GroupPage{}, err
	}
	return page, nil
}
