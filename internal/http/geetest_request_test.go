package httpapi

import (
	"encoding/json"
	"testing"
)

func TestGeeTestGenTimeUnmarshal(t *testing.T) {
	tests := []struct {
		name string
		body string
		want string
	}{
		{name: "string", body: `{"geetest_gen_time":"1722912345"}`, want: "1722912345"},
		{name: "number", body: `{"geetest_gen_time":1722912345}`, want: "1722912345"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var req struct {
				GenTime geetestGenTime `json:"geetest_gen_time"`
			}
			if err := json.Unmarshal([]byte(tt.body), &req); err != nil {
				t.Fatalf("Unmarshal: %v", err)
			}
			if got := string(req.GenTime); got != tt.want {
				t.Fatalf("gen_time = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestGeeTestGenTimeRejectsInvalidValues(t *testing.T) {
	for _, body := range []string{
		`{"geetest_gen_time":""}`,
		`{"geetest_gen_time":"not-a-time"}`,
		`{"geetest_gen_time":1.5}`,
		`{"geetest_gen_time":null}`,
	} {
		var req struct {
			GenTime geetestGenTime `json:"geetest_gen_time"`
		}
		if err := json.Unmarshal([]byte(body), &req); err == nil {
			t.Errorf("Unmarshal(%s) succeeded, want error", body)
		}
	}
}
