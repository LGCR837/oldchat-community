package httpapi

import (
	"context"
	"html/template"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type adminResourceHallData struct {
	Error              string
	Success            string
	SectionCount       int
	ItemCount          int
	TotalBytes         int64
	MaxFileBytes       int64
	Keyword            string
	SectionID          string
	Sections           []resourceHallSection
	Items              []resourceHallItem
	CurrentPage        int
	PageSize           int
	TotalPages         int
	HasPrevPage        bool
	HasNextPage        bool
	PrevPageURL        string
	NextPageURL        string
	ResourceQuotaGB    string
	ResourceQuotaBytes int64
}

type resourceHallSection struct {
	ID            string
	Name          string
	OwnerName     string
	OwnerUID      string
	ResourceCount int
	CreatedAt     time.Time
}

type resourceHallItem struct {
	ID           string
	SectionID    string
	SectionName  string
	Name         string
	SizeBytes    int64
	UploaderName string
	UploaderUID  string
	CreatedAt    time.Time
}

var adminResourceHallTpl = template.Must(template.New("admin_resource_hall").Funcs(template.FuncMap{
	"formatBytes": func(b int64) string {
		if b < 1024 {
			return strconv.FormatInt(b, 10) + " B"
		}
		if b < 1024*1024 {
			return strconv.FormatFloat(float64(b)/1024, 'f', 1, 64) + " KB"
		}
		if b < 1024*1024*1024 {
			return strconv.FormatFloat(float64(b)/(1024*1024), 'f', 1, 64) + " MB"
		}
		return strconv.FormatFloat(float64(b)/(1024*1024*1024), 'f', 2, 64) + " GB"
	},
}).Parse(adminResourceHallHTML))

func (a *API) handleAdminResourceHall(w http.ResponseWriter, r *http.Request) {
	if !a.isAdmin(r) {
		renderAdminLogin(w, "")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()

	keyword := strings.TrimSpace(r.URL.Query().Get("q"))
	sectionID := strings.TrimSpace(r.URL.Query().Get("section_id"))
	page := 1
	if raw := r.URL.Query().Get("page"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed >= 1 {
			page = parsed
		}
	}
	pageSize := 50
	offset := (page - 1) * pageSize

	data := adminResourceHallData{
		Keyword:      keyword,
		SectionID:    sectionID,
		CurrentPage:  page,
		PageSize:     pageSize,
		MaxFileBytes: maxFileUploadBytes,
	}
	data.Success = strings.TrimSpace(r.URL.Query().Get("ok"))
	data.Error = strings.TrimSpace(r.URL.Query().Get("err"))

	sections, err := a.resources.ListSections(ctx, 200, 0)
	if err == nil {
		data.SectionCount = len(sections)
		for _, s := range sections {
			data.Sections = append(data.Sections, resourceHallSection{
				ID: s.ID, Name: s.Name,
				OwnerName: displayName(s.OwnerName, s.OwnerUID), OwnerUID: s.OwnerUID,
				ResourceCount: s.ResourceCount, CreatedAt: s.Created,
			})
		}
	}
	if count, err := a.resources.CountItems(ctx, keyword); err == nil {
		data.ItemCount = count
	}
	if total, err := a.resources.SumItemsSize(ctx); err == nil {
		data.TotalBytes = total
	}
	items, err := a.resources.ListAllItems(ctx, keyword, sectionID, pageSize, offset)
	if err == nil {
		sectionNames := make(map[string]string, len(sections))
		for _, s := range sections {
			sectionNames[s.ID] = s.Name
		}
		for _, it := range items {
			data.Items = append(data.Items, resourceHallItem{
				ID: it.ID, SectionID: it.SectionID, SectionName: sectionNames[it.SectionID],
				Name: it.Name, SizeBytes: it.SizeBytes,
				UploaderName: displayName(it.UploaderName, it.UploaderUID), UploaderUID: it.UploaderUID,
				CreatedAt: it.Created,
			})
		}
	}
	data.TotalPages = (data.ItemCount + pageSize - 1) / pageSize
	if data.TotalPages < 1 {
		data.TotalPages = 1
	}
	data.HasPrevPage = page > 1
	data.HasNextPage = page < data.TotalPages
	base := "/admins/resources?"
	if data.Keyword != "" {
		base += "q=" + urlQueryEscape(data.Keyword) + "&"
	}
	if data.SectionID != "" {
		base += "section_id=" + urlQueryEscape(data.SectionID) + "&"
	}
	data.PrevPageURL = base + "page=" + strconv.Itoa(page-1)
	data.NextPageURL = base + "page=" + strconv.Itoa(page+1)

	data.ResourceQuotaBytes = a.cfg.ResourceQuota
	if a.cfg.ResourceQuota > 0 {
		data.ResourceQuotaGB = strconv.FormatFloat(float64(a.cfg.ResourceQuota)/(1024*1024*1024), 'f', 2, 64)
	}
	renderAdminResourceHall(w, data)
}

func renderAdminResourceHall(w http.ResponseWriter, data adminResourceHallData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = adminResourceHallTpl.Execute(w, data)
}

func (a *API) handleAdminResourceSectionDelete(w http.ResponseWriter, r *http.Request) {
	if !a.ensureAdminPost(w, r) {
		return
	}
	sectionID := strings.TrimSpace(r.FormValue("section_id"))
	if sectionID == "" {
		http.Redirect(w, r, "/admins/resources", http.StatusSeeOther)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	items, err := a.resources.DeleteSectionAndItems(ctx, sectionID)
	if err != nil {
		http.Redirect(w, r, "/admins/resources?err=delete+failed", http.StatusSeeOther)
		return
	}
	for _, item := range items {
		if path := resourceUploadPath(a.cfg.UploadDir, item.URL); path != "" {
			_ = os.Remove(path)
		}
	}
	http.Redirect(w, r, "/admins/resources?ok=section+deleted", http.StatusSeeOther)
}

func urlQueryEscape(value string) string {
	return strings.ReplaceAll(strings.ReplaceAll(value, "&", "%26"), "=", "%3D")
}

func displayName(name, uid string) string {
	if name != "" {
		return name
	}
	return uid
}

const adminResourceHallHTML = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>资源大厅管理 - 旧聊管理后台</title>
<style>
  :root{--radius:10px;--radius-lg:14px;--line:#e5e7eb;--line-strong:#d1d5db;--primary:#4f46e5;}
  *{box-sizing:border-box}
  body{margin:0;font-family:-apple-system,"Noto Sans SC",sans-serif;background:#f1f5f9;color:#0f172a}
  .layout{display:flex;min-height:100vh}
  .sidebar{width:230px;background:#111827;color:#e5e7eb;padding:18px 12px;flex-shrink:0}
  .side-header{padding:4px 6px 14px}
  .side-title{font-size:17px;font-weight:700;color:#fff}
  .side-sub{font-size:11px;color:#9ca3af;margin-top:2px}
  .side-nav{display:flex;flex-direction:column;gap:2px}
  .side-nav a{color:#cbd5e1;text-decoration:none;padding:8px 10px;border-radius:8px;font-size:13px}
  .side-nav a:hover{background:#1f2937;color:#fff}
  .side-nav a.active{background:rgba(79,70,229,.35);color:#fff;font-weight:600}
  .wrap{flex:1;padding:20px;max-width:1200px}
  h1{font-size:20px;margin:0 0 4px}
  .top{display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:16px}
  .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:16px 0}
  .stat{background:#fff;border:1px solid var(--line);border-radius:var(--radius);padding:14px}
  .stat-label{font-size:12px;color:#64748b}
  .stat-value{font-size:22px;font-weight:700;margin-top:4px}
  .card{background:#fff;border:1px solid var(--line);border-radius:var(--radius);padding:16px;margin:14px 0}
  .card h2{font-size:15px;margin:0 0 10px}
  .ok{color:#065f46;background:#d1fae5;padding:8px 12px;border-radius:8px;margin:10px 0}
  .err{color:#991b1b;background:#fee2e2;padding:8px 12px;border-radius:8px;margin:10px 0}
  .table-wrap{overflow:auto;border:1px solid var(--line);border-radius:var(--radius);background:#fff}
  table{width:100%;border-collapse:collapse;font-size:13px;min-width:820px}
  th,td{border-bottom:1px solid var(--line);padding:8px 10px;text-align:left;vertical-align:middle;word-break:break-word}
  th{background:#f9fafb;font-weight:600;color:#374151;font-size:11px;text-transform:uppercase;letter-spacing:.3px}
  tbody tr:hover{background:#f0f4ff}
  .mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:#64748b}
  .muted{color:#6b7280;font-size:12px}
  .actions{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
  input,select,button{padding:7px 10px;border:1px solid var(--line-strong);border-radius:8px;font-size:13px;background:#fff}
  button{background:#111827;color:#fff;border-color:#111827;cursor:pointer;font-weight:600}
  button.danger{background:#dc2626;border-color:#dc2626}
  a.btn{display:inline-block;padding:7px 10px;border-radius:8px;font-size:13px;text-decoration:none;border:1px solid var(--line-strong);color:#0f172a;background:#fff}
  .pager{display:flex;gap:8px;align-items:center;margin-top:12px;flex-wrap:wrap}
  .pager span{font-size:13px;color:#475569}
  @media (max-width:1024px){.layout{flex-direction:column}.sidebar{width:auto}}
</style>
</head>
<body>
<div class="layout">
  <aside class="sidebar">
    <div class="side-header"><div class="side-title">旧聊管理后台</div><div class="side-sub">Admin Console</div></div>
    <nav class="side-nav">
      <a href="/admins">概览</a>
      <a href="/admins?section=resources">资源举报/配额</a>
      <a href="/admins/resources" class="active">资源大厅</a>
      <a href="/admins?section=users">用户</a>
      <a href="/admins?section=groups">群组</a>
      <a href="/admins?section=reports">举报/法庭</a>
      <a href="/admins?section=notifications">通知</a>
      <a href="/admins?section=bugs">Bug</a>
    </nav>
  </aside>
  <div class="wrap">
    <div class="top">
      <h1>资源大厅管理</h1>
      <div class="actions">
        <a class="btn" href="/admins">返回总后台</a>
        <form method="post" action="/admins/logout"><button type="submit">退出登录</button></form>
      </div>
    </div>
    {{if .Success}}<div class="ok">{{.Success}}</div>{{end}}
    {{if .Error}}<div class="err">{{.Error}}</div>{{end}}

    <div class="stats">
      <div class="stat"><div class="stat-label">分区总数</div><div class="stat-value">{{.SectionCount}}</div></div>
      <div class="stat"><div class="stat-label">资源条目</div><div class="stat-value">{{.ItemCount}}</div></div>
      <div class="stat"><div class="stat-label">总占用</div><div class="stat-value">{{formatBytes .TotalBytes}}</div></div>
      <div class="stat"><div class="stat-label">单文件上限</div><div class="stat-value">500MB</div></div>
      <div class="stat"><div class="stat-label">用户配额</div><div class="stat-value">{{.ResourceQuotaGB}} GB</div></div>
    </div>

    <div class="card">
      <h2>搜索与筛选</h2>
      <form method="get" action="/admins/resources" class="actions">
        <input name="q" placeholder="按资源名称搜索" value="{{.Keyword}}" style="min-width:220px">
        <select name="section_id">
          <option value="">全部分区</option>
          {{range .Sections}}<option value="{{.ID}}" {{if eq $.SectionID .ID}}selected{{end}}>{{.Name}}</option>{{end}}
        </select>
        <button type="submit">搜索</button>
        {{if or .Keyword .SectionID}}<a class="btn" href="/admins/resources">清除</a>{{end}}
      </form>
    </div>

    <div class="card">
      <h2>分区列表（{{.SectionCount}}）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>名称</th><th>创建者</th><th>条目数</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody>
          {{range .Sections}}
            <tr>
              <td><strong>{{.Name}}</strong><div class="mono">{{.ID}}</div></td>
              <td>{{.OwnerName}}<div class="mono">{{.OwnerUID}}</div></td>
              <td>{{.ResourceCount}}</td>
              <td>{{.CreatedAt.Format "2006-01-02 15:04"}}</td>
              <td>
                <div class="actions">
                  <a class="btn" href="/admins/resources?section_id={{.ID}}">查看条目</a>
                  <form method="post" action="/admins/resources/section/delete" onsubmit="return confirm('确定删除分区「{{.Name}}」及其全部条目？此操作不可恢复！');">
                    <input type="hidden" name="section_id" value="{{.ID}}">
                    <button type="submit" class="danger">删除分区</button>
                  </form>
                </div>
              </td>
            </tr>
          {{else}}
            <tr><td colspan="5" class="muted">暂无分区</td></tr>
          {{end}}
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h2>资源条目（{{.ItemCount}}）</h2>
      <div class="table-wrap">
        <table>
          <thead><tr><th>名称</th><th>分区</th><th>上传者</th><th>大小</th><th>时间</th><th>操作</th></tr></thead>
          <tbody>
          {{range .Items}}
            <tr>
              <td><strong>{{.Name}}</strong><div class="mono">{{.ID}}</div></td>
              <td>{{if .SectionName}}{{.SectionName}}{{else}}<span class="muted">-</span>{{end}}</td>
              <td>{{.UploaderName}}<div class="mono">{{.UploaderUID}}</div></td>
              <td>{{formatBytes .SizeBytes}}</td>
              <td>{{.CreatedAt.Format "2006-01-02 15:04"}}</td>
              <td>
                <form method="post" action="/admins/resources/delete" onsubmit="return confirm('确定删除该资源？');">
                  <input type="hidden" name="item_id" value="{{.ID}}">
                  <button type="submit" class="danger">删除</button>
                </form>
              </td>
            </tr>
          {{else}}
            <tr><td colspan="6" class="muted">暂无资源条目</td></tr>
          {{end}}
          </tbody>
        </table>
      </div>
      <div class="pager">
        {{if .HasPrevPage}}<a class="btn" href="{{.PrevPageURL}}">上一页</a>{{end}}
        <span>第 {{.CurrentPage}} / {{.TotalPages}} 页</span>
        {{if .HasNextPage}}<a class="btn" href="{{.NextPageURL}}">下一页</a>{{end}}
      </div>
    </div>
  </div>
</div>
</body>
</html>
`
