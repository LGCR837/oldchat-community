package httpapi

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func TestSanitizeResourceNamePreservesUnicodeAndExtension(t *testing.T) {
	name := sanitizeResourceName(`C:\Users\测试\我的资源文件.zip`)
	if name != "我的资源文件.zip" {
		t.Fatalf("unexpected sanitized name: %q", name)
	}
	if !utf8.ValidString(name) {
		t.Fatalf("sanitized name is not valid utf-8: %q", name)
	}
}

func TestSanitizeResourceNameTruncatesByRunes(t *testing.T) {
	original := strings.Repeat("资", 150) + ".tar.gz"
	name := sanitizeResourceName(original)
	if !utf8.ValidString(name) {
		t.Fatalf("truncated name is not valid utf-8")
	}
	if !strings.HasSuffix(name, ".gz") {
		t.Fatalf("extension was not preserved: %q", name)
	}
	if len([]rune(name)) > 128 {
		t.Fatalf("name contains %d runes, want <= 128", len([]rune(name)))
	}
}

func TestSanitizeResourceNameRemovesControlCharacters(t *testing.T) {
	name := sanitizeResourceName("  报告\r\n最终版.pdf  ")
	if name != "报告最终版.pdf" {
		t.Fatalf("unexpected sanitized name: %q", name)
	}
}
