package httpapi

import (
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func resolveWebAppDir() string {
	candidates := []string{
		"webapp",
		"server/webapp",
	}
	for _, dir := range candidates {
		musicPath := filepath.Join(dir, "music.html")
		info, err := os.Stat(musicPath)
		if err == nil && !info.IsDir() {
			return dir
		}
		indexPath := filepath.Join(dir, "index.html")
		info, err = os.Stat(indexPath)
		if err == nil && !info.IsDir() {
			return dir
		}
	}
	return ""
}

func (a *API) handleWebApp(w http.ResponseWriter, r *http.Request) {
	if a.webAppDir == "" {
		a.handleLanding(w, r)
		return
	}
	// 按路径返回对应页面：
	//   /app         -> music.html (音乐大厅)
	//   /app/music   -> music.html (音乐大厅)
	//   /app/xxx     -> 对应文件（存在则返回，否则回退 music.html）
	name := strings.TrimPrefix(r.URL.Path, "/app/")
	if name == "" || name == "/" || strings.Contains(name, "..") {
		name = "music.html"
	}
	// 无扩展名的路径补 .html：/app/music -> music.html
	if !strings.Contains(name, ".") {
		name += ".html"
	}
	target := filepath.Join(a.webAppDir, filepath.FromSlash(name))
	if info, err := os.Stat(target); err == nil && !info.IsDir() {
		http.ServeFile(w, r, target)
		return
	}
	http.ServeFile(w, r, filepath.Join(a.webAppDir, "music.html"))
}
