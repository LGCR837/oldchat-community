package data

import (
	"context"
	"time"

	"github.com/jmoiron/sqlx"
)

// PlaylistSong 歌单内歌曲：song_json 存客户端 MusicItem.toJson 完整快照，
// 拉取时可 100% 还原（即使原曲被删）。
type PlaylistSong struct {
	SongID   string `db:"song_id"`
	SongJSON string `db:"song_json"`
	Sort     int    `db:"sort_order"`
}

// Playlist 歌单（含歌曲列表，Songs 不入库列）。
type Playlist struct {
	ID        string         `db:"id"`
	UserID    string         `db:"user_id"`
	Name      string         `db:"name"`
	CoverURL  string         `db:"cover_url"`
	CreatedAt time.Time      `db:"created_at"`
	UpdatedAt time.Time      `db:"updated_at"`
	Songs     []PlaylistSong `db:"-"`
}

const (
	MaxPlaylistsPerUser   = 5
	MaxSongsPerPlaylist   = 200
	MaxPlaylistNameLen    = 64
	MaxPlaylistCoverLen   = 1024
	MaxPlaylistSongJSON   = 8 * 1024
)

// PlaylistStore 歌单云端存储。
type PlaylistStore struct {
	db *sqlx.DB
}

func NewPlaylistStore(db *sqlx.DB) *PlaylistStore {
	return &PlaylistStore{db: db}
}

// Count 返回用户歌单数。
func (s *PlaylistStore) Count(ctx context.Context, userID string) (int, error) {
	var n int
	err := s.db.GetContext(ctx, &n,
		`SELECT COUNT(*) FROM user_playlists WHERE user_id = $1`, userID)
	return n, err
}

// List 返回用户全部歌单（含歌曲，按 updated_at 倒序）。
func (s *PlaylistStore) List(ctx context.Context, userID string) ([]*Playlist, error) {
	var pls []*Playlist
	if err := s.db.SelectContext(ctx, &pls,
		`SELECT id, user_id, name, cover_url, created_at, updated_at
		 FROM user_playlists WHERE user_id = $1 ORDER BY updated_at DESC, created_at DESC`, userID); err != nil {
		return nil, err
	}
	for _, p := range pls {
		songs, err := s.ListSongs(ctx, p.ID)
		if err != nil {
			return nil, err
		}
		p.Songs = songs
	}
	return pls, nil
}

// ListSongs 返回歌单内歌曲（按 sort_order）。
func (s *PlaylistStore) ListSongs(ctx context.Context, playlistID string) ([]PlaylistSong, error) {
	var songs []PlaylistSong
	err := s.db.SelectContext(ctx, &songs,
		`SELECT song_id, song_json, sort_order FROM user_playlist_songs
		 WHERE playlist_id = $1 ORDER BY sort_order ASC`, playlistID)
	return songs, err
}

// ReplaceAll 事务内全量替换用户歌单（先删后插）。调用方需先做 5/200 上限校验。
func (s *PlaylistStore) ReplaceAll(ctx context.Context, userID string, playlists []*Playlist) error {
	tx, err := s.db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx,
		`DELETE FROM user_playlists WHERE user_id = $1`, userID); err != nil {
		return err
	}

	now := time.Now()
	for _, p := range playlists {
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO user_playlists (id, user_id, name, cover_url, created_at, updated_at)
			 VALUES ($1, $2, $3, $4, $5, $6)`,
			p.ID, userID, p.Name, p.CoverURL, now, now); err != nil {
			return err
		}
		for i, song := range p.Songs {
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO user_playlist_songs (playlist_id, song_id, song_json, sort_order)
				 VALUES ($1, $2, $3, $4)`,
				p.ID, song.SongID, song.SongJSON, i); err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}
