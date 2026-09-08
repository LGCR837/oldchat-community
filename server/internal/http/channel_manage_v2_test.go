package httpapi

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"metrochat/internal/data"
)

func TestChannelTokenTTLIs365Days(t *testing.T) {
	if channelTokenTTL != 365*24*time.Hour {
		t.Fatalf("channelTokenTTL=%s", channelTokenTTL)
	}
}

func TestChannelManageUsesWebsiteStyleAndOwnerPermissions(t *testing.T) {
	selected := &data.Channel{ID: "CH-TEST", OwnerID: "user-1", Name: "测试频道", Handle: "test_channel", Visibility: "public"}
	pageData := channelWebDashboard{
		CSRF: "csrf", UserUID: "UID100", Scopes: "channel:create,channel:post,channel:manage",
		CanCreate: true, CanPost: true, IsOwner: true, SelectedRole: "owner",
		Channels: []data.Channel{*selected}, Selected: selected,
		Publishers: []channelWebPublisher{{UID: "UID100", Username: "owner", Role: "owner"}, {UID: "UID200", Username: "writer", Role: "publisher"}},
	}
	var out bytes.Buffer
	if err := channelDashboardTemplate.Execute(&out, pageData); err != nil {
		t.Fatal(err)
	}
	body := out.String()
	for _, want := range []string{"border-radius:0!important", "频道控制台", "发布消息", "频道资料", "发言成员", "创建频道", "DEVELOPER DOC"} {
		if !strings.Contains(body, want) {
			t.Fatalf("dashboard missing %q", want)
		}
	}
	for _, unwanted := range []string{"border-radius:12px", "box-shadow", "机器人接口申请"} {
		if strings.Contains(body, unwanted) {
			t.Fatalf("dashboard contains old UI marker %q", unwanted)
		}
	}
	assertNoEmoji(t, body)
}

func TestChannelManageHidesOwnerActionsFromPublisher(t *testing.T) {
	selected := &data.Channel{ID: "CH-TEST", OwnerID: "owner-1", Name: "测试频道", Visibility: "private"}
	var out bytes.Buffer
	if err := channelDashboardTemplate.Execute(&out, channelWebDashboard{
		UserUID: "UID200", Scopes: "channel:post,channel:manage", CanPost: true,
		SelectedRole: "publisher", Selected: selected, Channels: []data.Channel{*selected},
	}); err != nil {
		t.Fatal(err)
	}
	body := out.String()
	if strings.Contains(body, "<h2>频道资料</h2>") || strings.Contains(body, "<h2>发言成员</h2>") {
		t.Fatal("publisher must not see owner management forms")
	}
	if !strings.Contains(body, "仅频道所有者可管理") {
		t.Fatal("publisher permission explanation missing")
	}
}

func TestChannelManageLoginAndRegisterUseWebsiteStyle(t *testing.T) {
	for name, body := range map[string]string{"login": channelWebLoginHTML, "register": renderChannelRegisterForTest(t)} {
		for _, want := range []string{"border-radius:0!important", "HOME", "#0366d6", "#111"} {
			if !strings.Contains(body, want) {
				t.Fatalf("%s missing %q", name, want)
			}
		}
		assertNoEmoji(t, body)
	}
}

func renderChannelRegisterForTest(t *testing.T) string {
	t.Helper()
	var out bytes.Buffer
	if err := channelRegisterPageTemplate.Execute(&out, channelRegisterPageData{CSRF: "csrf"}); err != nil {
		t.Fatal(err)
	}
	return out.String()
}

func assertNoEmoji(t *testing.T, body string) {
	t.Helper()
	for _, r := range body {
		if (r >= 0x1F300 && r <= 0x1FAFF) || (r >= 0x2600 && r <= 0x27BF) {
			t.Fatalf("page contains emoji %U", r)
		}
	}
}
