package mail

import (
	"testing"
)

func TestMailScriptPath(t *testing.T) {
	path, err := mailScriptPath()
	if err != nil {
		t.Fatalf("mailScriptPath failed: %v", err)
	}
	if path == "" {
		t.Fatal("mailScriptPath returned empty path")
	}
}
