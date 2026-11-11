package httpapi

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jmoiron/sqlx"

	"metrochat/internal/config"
	"metrochat/internal/data"
)

func TestTakedownOffendingContent(t *testing.T) {
	tempDir := t.TempDir()
	mediaDir := filepath.Join(tempDir, "media")
	_ = os.MkdirAll(mediaDir, 0755)

	testMediaFile := filepath.Join(mediaDir, "test_bad_img.jpg")
	_ = os.WriteFile(testMediaFile, []byte("fake image content"), 0644)
	_ = os.WriteFile(testMediaFile+".oss", []byte("https://fake.oss/media/test_bad_img.jpg"), 0644)

	db, err := sqlx.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)

	_, err = db.Exec(`
CREATE TABLE IF NOT EXISTS music_plaza_items (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    name TEXT NOT NULL,
    song_url TEXT NOT NULL,
    cover_url TEXT NOT NULL DEFAULT '',
    lyrics_url TEXT NOT NULL DEFAULT '',
    size_bytes INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    play_count INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL
);
CREATE TABLE IF NOT EXISTS resource_items (
    id TEXT PRIMARY KEY,
    section_id TEXT NOT NULL DEFAULT '',
    uploader_id TEXT NOT NULL DEFAULT '',
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL
);
`)
	if err != nil {
		t.Fatal(err)
	}

	api := &API{
		cfg: config.Config{
			UploadDir: tempDir,
		},
		musicPlaza: data.NewMusicPlazaStore(db),
		resources:  data.NewResourceStore(db),
	}

	// 插入测试音乐广场数据
	testMusic := &data.MusicPlazaItem{
		ID:        "test_m_1001",
		OwnerID:   "usr_test",
		Name:      "涉案违规歌曲",
		SongURL:   "/v1/uploads/music/bad_song.mp3",
		CoverURL:  "/v1/uploads/media/bad_cover.jpg",
		LyricsURL: "",
		CreatedAt: time.Now(),
	}
	_ = api.musicPlaza.Create(context.Background(), testMusic)

	// 构造举报文本
	reportText := `发簧片
群ID: GRP-3TTO6Q
消息类型: image
消息ID: c_DDC8CfiYgL_xvgijNJv
内容: [图片]
媒体: /v1/uploads/media/test_bad_img.jpg

另外还有音乐:
[音乐举报] 歌曲ID: test_m_1001 歌名: 涉案违规歌曲 上传者: 测试
`

	actions := api.takedownOffendingContent(context.Background(), reportText)
	if len(actions) == 0 {
		t.Fatalf("expected takedown actions, got none")
	}

	// 验证本地图片已删除
	if _, err := os.Stat(testMediaFile); !os.IsNotExist(err) {
		t.Errorf("expected test_bad_img.jpg to be deleted, but it still exists")
	}
	if _, err := os.Stat(testMediaFile + ".oss"); !os.IsNotExist(err) {
		t.Errorf("expected sidecar .oss to be deleted, but it still exists")
	}

	// 验证音乐广场已下架
	if _, err := api.musicPlaza.GetByID(context.Background(), "test_m_1001"); err == nil {
		t.Errorf("expected test_m_1001 to be deleted from music_plaza_items")
	}
}
