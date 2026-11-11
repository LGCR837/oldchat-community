package httpapi

import "testing"

func TestParseMusicDurationMS(t *testing.T) {
	tests := []struct {
		name string
		raw  string
		want int
	}{
		{name: "正常时长", raw: "185000", want: 185000},
		{name: "允许空白", raw: " 90000 ", want: 90000},
		{name: "空值", raw: "", want: 0},
		{name: "非法文本", raw: "unknown", want: 0},
		{name: "负数", raw: "-1", want: 0},
		{name: "超过一天", raw: "86400001", want: 0},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := parseMusicDurationMS(test.raw); got != test.want {
				t.Fatalf("parseMusicDurationMS(%q) = %d，期望 %d", test.raw, got, test.want)
			}
		})
	}
}
