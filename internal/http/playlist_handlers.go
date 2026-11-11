package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"metrochat/internal/data"
)

// ---------- 歌单同步接口（每用户 ≤5 歌单，每歌单 ≤200 首） ----------

type playlistSongDTO struct {
	SongID string          `json:"song_id"`
	Song   json.RawMessage `json:"song"`
}

type playlistDTO struct {
	ID        string            `json:"id"`
	Name      string            `json:"name"`
	CoverURL  string            `json:"cover_url,omitempty"`
	CreatedAt int64             `json:"created_at"`
	UpdatedAt int64             `json:"updated_at"`
	Songs     []playlistSongDTO `json:"songs"`
}

type playlistListResponse struct {
	Items []*playlistDTO `json:"items"`
	Total int            `json:"total"`
}

type playlistSyncRequest struct {
	Playlists []*playlistDTO `json:"playlists"`
}

// handlePlaylistList GET /v1/music/playlists —— 拉取我的全部歌单。
func (a *API) handlePlaylistList(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	pls, err := a.playlists.List(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	resp := make([]*playlistDTO, 0, len(pls))
	for _, p := range pls {
		resp = append(resp, toPlaylistDTO(p))
	}
	writeJSON(w, http.StatusOK, playlistListResponse{Items: resp, Total: len(resp)})
}

// handlePlaylistSync POST /v1/music/playlists/sync —— 全量推送本地歌单，事务内覆盖该用户云端歌单。
func (a *API) handlePlaylistSync(w http.ResponseWriter, r *http.Request) {
	claims, ok := claimsFromContext(r.Context())
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "unauthorized")
		return
	}
	if !requireJSON(w, r) {
		return
	}
	var req playlistSyncRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid json")
		return
	}
	pls, errMsg := validatePlaylists(req.Playlists)
	if errMsg != "" {
		writeError(w, http.StatusBadRequest, "invalid_playlists", errMsg)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	if err := a.playlists.ReplaceAll(ctx, claims.Subject, pls); err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	// 返回服务端最新列表，客户端可据此刷新。
	latest, err := a.playlists.List(ctx, claims.Subject)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "db_error", "internal error")
		return
	}
	resp := make([]*playlistDTO, 0, len(latest))
	for _, p := range latest {
		resp = append(resp, toPlaylistDTO(p))
	}
	writeJSON(w, http.StatusOK, playlistListResponse{Items: resp, Total: len(resp)})
}

// validatePlaylists 校验并转换为存储模型，超限返回错误信息。
func validatePlaylists(dtos []*playlistDTO) ([]*data.Playlist, string) {
	if len(dtos) > data.MaxPlaylistsPerUser {
		return nil, "playlist count exceeds limit " + strconv.Itoa(data.MaxPlaylistsPerUser)
	}
	out := make([]*data.Playlist, 0, len(dtos))
	seenIDs := make(map[string]bool, len(dtos))
	for _, d := range dtos {
		if d == nil {
			return nil, "playlist is null"
		}
		id := strings.TrimSpace(d.ID)
		name := strings.TrimSpace(d.Name)
		if id == "" || len(id) > 64 {
			return nil, "invalid playlist id"
		}
		if seenIDs[id] {
			return nil, "duplicate playlist id " + id
		}
		seenIDs[id] = true
		if name == "" || len(name) > data.MaxPlaylistNameLen {
			return nil, "invalid playlist name (max 64 chars)"
		}
		if len(d.CoverURL) > data.MaxPlaylistCoverLen {
			return nil, "invalid playlist cover"
		}
		if len(d.Songs) > data.MaxSongsPerPlaylist {
			return nil, "playlist " + name + " exceeds " + strconv.Itoa(data.MaxSongsPerPlaylist) + " songs"
		}
		songs := make([]data.PlaylistSong, 0, len(d.Songs))
		seenSongs := make(map[string]bool, len(d.Songs))
		for _, s := range d.Songs {
			sid := strings.TrimSpace(s.SongID)
			if sid == "" || len(sid) > 64 {
				return nil, "invalid song id in playlist " + name
			}
			if seenSongs[sid] {
				continue // 歌单内去重（与客户端 MusicPlaylist.addSong 行为一致）
			}
			seenSongs[sid] = true
			raw := s.Song
			if len(raw) == 0 || !json.Valid(raw) {
				return nil, "invalid song payload in playlist " + name
			}
			if len(raw) > data.MaxPlaylistSongJSON {
				return nil, "song payload too large in playlist " + name
			}
			songs = append(songs, data.PlaylistSong{SongID: sid, SongJSON: string(raw)})
		}
		out = append(out, &data.Playlist{
			ID:       id,
			Name:     name,
			CoverURL: strings.TrimSpace(d.CoverURL),
			Songs:    songs,
		})
	}
	return out, ""
}

func toPlaylistDTO(p *data.Playlist) *playlistDTO {
	d := &playlistDTO{
		ID:        p.ID,
		Name:      p.Name,
		CoverURL:  p.CoverURL,
		CreatedAt: p.CreatedAt.Unix(),
		UpdatedAt: p.UpdatedAt.Unix(),
		Songs:     make([]playlistSongDTO, 0, len(p.Songs)),
	}
	for _, s := range p.Songs {
		d.Songs = append(d.Songs, playlistSongDTO{
			SongID: s.SongID,
			Song:   json.RawMessage(s.SongJSON),
		})
	}
	return d
}
