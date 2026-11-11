package httpapi

import (
	"context"
	"testing"
	"time"
)

func TestValidateTokenVersionSlidingWindow(t *testing.T) {
	api := &API{
		tokenVersionCache: map[string]tokenVersionEntry{
			"u1": {
				version:   10,
				expiresAt: time.Now().Add(10 * time.Second),
			},
		},
	}

	cases := []struct {
		name    string
		version int
		want    bool
	}{
		{name: "current", version: 10, want: true},
		{name: "previous_one", version: 9, want: true},
		{name: "previous_two", version: 8, want: true},
		{name: "previous_nine", version: 1, want: true},
		{name: "future_version", version: 11, want: false},
		{name: "zero", version: 0, want: false},
	}

	for _, tc := range cases {
		got := api.validateTokenVersion(context.Background(), "u1", tc.version)
		if got != tc.want {
			t.Fatalf("%s: got %v want %v (tokenVersion=%d)", tc.name, got, tc.want, tc.version)
		}
	}
}

func TestValidateTokenVersionFloorAtOne(t *testing.T) {
	api := &API{
		tokenVersionCache: map[string]tokenVersionEntry{
			"u1": {
				version:   2,
				expiresAt: time.Now().Add(10 * time.Second),
			},
		},
	}

	if !api.validateTokenVersion(context.Background(), "u1", 1) {
		t.Fatalf("version 1 should be accepted when current version is 2")
	}
	if api.validateTokenVersion(context.Background(), "u1", 0) {
		t.Fatalf("version 0 should be rejected")
	}
}
