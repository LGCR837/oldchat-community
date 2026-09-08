package httpapi

import "testing"

func TestBotResponseCursorRoundTrip(t *testing.T) {
	want := botResponseCursor{ID: 12345}
	raw := encodeBotResponseCursor(want)
	if raw == "" {
		t.Fatal("encoded cursor is empty")
	}
	got, err := decodeBotResponseCursor(raw)
	if err != nil {
		t.Fatalf("decode cursor: %v", err)
	}
	if got != want {
		t.Fatalf("cursor round trip = %#v, want %#v", got, want)
	}
}

func TestBotResponseCursorValidation(t *testing.T) {
	empty, err := decodeBotResponseCursor("")
	if err != nil || empty.ID != 0 {
		t.Fatalf("empty cursor = %#v, %v", empty, err)
	}
	for _, invalid := range []string{"not-base64!", "e30", "eyJpZCI6MH0"} {
		if _, err := decodeBotResponseCursor(invalid); err == nil {
			t.Fatalf("invalid cursor %q was accepted", invalid)
		}
	}
}
