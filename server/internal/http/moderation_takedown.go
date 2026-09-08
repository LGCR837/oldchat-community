package httpapi

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

var (
	reUploadURL     = regexp.MustCompile(`(?:https?://[^/\s]+)?/(?:v1/)?uploads/([a-zA-Z0-9_\-\./]+)`)
	reGroupID       = regexp.MustCompile(`(?:群ID|group_id)[:：]\s*([a-zA-Z0-9_\-]+)`)
	reMessageID     = regexp.MustCompile(`(?:消息ID|message_id)[:：]\s*([a-zA-Z0-9_\-]+)`)
	reMusicSongID   = regexp.MustCompile(`(?:歌曲ID|item_id)[:：]\s*([a-zA-Z0-9_\-]+)`)
	reResourceResID = regexp.MustCompile(`(?:资源ID|res_id)[:：]\s*([a-zA-Z0-9_\-]+)`)
)

// takedownOffendingContent 从举报文本中智能提取涉案的违规媒体、音乐、资源及聊天消息，并执行物理删除与下架。
func (a *API) takedownOffendingContent(ctx context.Context, reportText string) []string {
	if strings.TrimSpace(reportText) == "" {
		return nil
	}
	var actions []string
	deletedFiles := make(map[string]bool)

	// 1. 删除所有涉案的媒体/图片/音频/附件直链
	matches := reUploadURL.FindAllStringSubmatch(reportText, -1)
	for _, m := range matches {
		if len(m) < 2 {
			continue
		}
		relPath := strings.Trim(strings.TrimSpace(m[1]), "/")
		if relPath == "" || strings.Contains(relPath, "..") || deletedFiles[relPath] {
			continue
		}
		deletedFiles[relPath] = true
		a.deleteUploadedMediaFile(relPath)
		actions = append(actions, "已删除媒体文件: "+filepath.Base(relPath))
	}

	// 2. 涉案音乐广场歌曲下架
	songID := ""
	if sm := reMusicSongID.FindStringSubmatch(reportText); len(sm) > 1 {
		songID = strings.TrimSpace(sm[1])
	}
	if songID != "" && a.musicPlaza != nil {
		if item, err := a.musicPlaza.GetByID(ctx, songID); err == nil && item != nil {
			_ = a.musicPlaza.DeleteItem(ctx, item.ID)
			a.deleteUploadedMediaByURL(item.SongURL, deletedFiles)
			a.deleteUploadedMediaByURL(item.CoverURL, deletedFiles)
			a.deleteUploadedMediaByURL(item.LyricsURL, deletedFiles)
			actions = append(actions, "已下架音乐广场歌曲: 《"+item.Name+"》(ID: "+item.ID+")")
		}
	} else if strings.Contains(reportText, "[音乐举报]") && a.musicPlaza != nil {
		// 未提取到 ID 时，尝试按媒体直链反查
		for relPath := range deletedFiles {
			if strings.HasPrefix(relPath, "music/") || strings.HasPrefix(relPath, "media/") {
				candidates := []string{
					"/v1/uploads/" + relPath,
					"/uploads/" + relPath,
				}
				for _, cand := range candidates {
					if item, err := a.musicPlaza.GetBySongURL(ctx, cand); err == nil && item != nil {
						_ = a.musicPlaza.DeleteItem(ctx, item.ID)
						actions = append(actions, "已下架音乐广场歌曲: 《"+item.Name+"》(ID: "+item.ID+")")
						break
					}
				}
			}
		}
	}

	// 3. 涉案资源大厅文件下架
	resID := ""
	if rm := reResourceResID.FindStringSubmatch(reportText); len(rm) > 1 {
		resID = strings.TrimSpace(rm[1])
	}
	if resID != "" && a.resources != nil {
		if item, err := a.resources.GetItemByID(ctx, resID); err == nil && item != nil {
			_ = a.resources.DeleteItem(ctx, item.ID)
			if a.resourceReports != nil {
				_ = a.resourceReports.DeleteByItemID(ctx, item.ID)
			}
			a.deleteUploadedMediaByURL(item.URL, deletedFiles)
			actions = append(actions, "已下架资源大厅文件: "+item.Name+"(ID: "+item.ID+")")
		}
	} else if a.resources != nil {
		for relPath := range deletedFiles {
			if strings.HasPrefix(relPath, "resources/") || strings.HasPrefix(relPath, "filewearhouse/") {
				candidates := []string{
					"/v1/uploads/" + relPath,
					"/uploads/" + relPath,
					"/resources/" + filepath.Base(relPath),
				}
				for _, cand := range candidates {
					if item, err := a.resources.GetItemByURL(ctx, cand); err == nil && item != nil {
						_ = a.resources.DeleteItem(ctx, item.ID)
						if a.resourceReports != nil {
							_ = a.resourceReports.DeleteByItemID(ctx, item.ID)
						}
						actions = append(actions, "已下架资源大厅文件: "+item.Name+"(ID: "+item.ID+")")
						break
					}
				}
			}
		}
	}

	// 4. 涉案群消息/私聊消息撤回
	groupID := ""
	if gm := reGroupID.FindStringSubmatch(reportText); len(gm) > 1 {
		groupID = strings.TrimSpace(gm[1])
	}
	msgID := ""
	if mm := reMessageID.FindStringSubmatch(reportText); len(mm) > 1 {
		msgID = strings.TrimSpace(mm[1])
	}

	if groupID != "" && msgID != "" && a.groupMsgs != nil {
		_ = a.groupMsgs.DeleteMessageByID(ctx, msgID)
		if a.groups != nil && a.wsHub != nil {
			if memberIDs, err := a.groups.ListMemberIDs(ctx, groupID); err == nil {
				env := wsEnvelope{
					Type: "group_recall",
					Data: map[string]interface{}{
						"message_id": msgID,
						"group_id":   groupID,
						"from_uid":   "system",
					},
				}
				if payload, err := json.Marshal(env); err == nil {
					a.wsHub.BroadcastToUsers(memberIDs, payload)
				}
			}
		}
		if a.groupEvents != nil {
			recallPayload := map[string]interface{}{
				"message_id":       msgID,
				"group_id":         groupID,
				"recall_edit_type": "recall",
				"operator_uid":     "system",
			}
			_, _ = a.groupEvents.EmitGroupEvent(context.Background(), groupID, "GROUP_MESSAGE_RECALL", msgID, recallPayload)
		}
		actions = append(actions, "已撤回涉案群消息: "+msgID)
	} else if msgID != "" && a.direct != nil {
		_ = a.direct.DeleteMessageByID(ctx, msgID)
		actions = append(actions, "已删除涉案私聊消息: "+msgID)
	}

	return actions
}

func (a *API) deleteUploadedMediaByURL(rawURL string, deletedMap map[string]bool) {
	if rawURL == "" {
		return
	}
	baseURL := strings.SplitN(rawURL, "?", 2)[0]
	rel := ""
	switch {
	case strings.HasPrefix(baseURL, "/v1/uploads/"):
		rel = strings.TrimPrefix(baseURL, "/v1/uploads/")
	case strings.HasPrefix(baseURL, "/uploads/"):
		rel = strings.TrimPrefix(baseURL, "/uploads/")
	case strings.HasPrefix(baseURL, "/resources/"):
		rel = "resources/" + strings.TrimPrefix(baseURL, "/resources/")
	case strings.HasPrefix(baseURL, "/files/"):
		rel = "files/" + strings.TrimPrefix(baseURL, "/files/")
	}
	rel = strings.Trim(rel, "/")
	if rel == "" || strings.Contains(rel, "..") || (deletedMap != nil && deletedMap[rel]) {
		return
	}
	if deletedMap != nil {
		deletedMap[rel] = true
	}
	a.deleteUploadedMediaFile(rel)
}

func (a *API) deleteUploadedMediaFile(relPath string) {
	if a == nil || relPath == "" || strings.Contains(relPath, "..") {
		return
	}
	absPath := filepath.Join(a.cfg.UploadDir, filepath.FromSlash(relPath))
	_ = os.Remove(absPath)
	_ = os.Remove(absPath + ".oss")
	_ = os.Remove(absPath + ".rain")

	if a.ossClient != nil && a.ossClient.IsEnabled() {
		_ = a.ossClient.Delete(relPath)
	}
	if a.rainOSSClient != nil && a.rainOSSClient.IsEnabled() {
		_ = a.rainOSSClient.Delete(relPath)
	}
}
