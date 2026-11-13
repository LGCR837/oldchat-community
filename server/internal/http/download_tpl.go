package httpapi

import "html/template"

type downloadChannel struct {
	Title       string
	BadgeClass  string
	Version     string
	VersionCode string
	Channel     string
	DownloadURL string
	Available   bool
	Notes       string
	ConfigName  string
	ConfigURL   string
}

type downloadPageData struct {
	Formal        downloadChannel
	Dev           downloadChannel
	Desktop       downloadChannel
	DesktopHidden bool
	Notice        string
}

const downloadPageHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
  <title>旧聊 客户端下载与版本发布</title>
  <meta name="theme-color" content="#f0f2f5" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#14161a" media="(prefers-color-scheme: dark)">
  <style>
    * { box-sizing: border-box; border-radius: 0!important; -webkit-tap-highlight-color: transparent; }
    body {
      margin: 0;
      padding: 16px 12px;
      background: #f0f2f5;
      color: #111;
      font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "WenQuanYi Micro Hei", sans-serif;
      line-height: 1.5;
    }
    .container {
      max-width: 580px;
      width: 100%;
      margin: 0 auto;
    }
    .top-nav {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 12px;
      margin-bottom: 14px;
      border-bottom: 2px solid #111;
    }
    .brand {
      font-weight: 900;
      font-size: 16px;
      letter-spacing: 0.5px;
    }
    .nav-links a {
      font-size: 13px;
      font-weight: 700;
      color: #111;
      text-decoration: none;
      margin-left: 10px;
      padding: 4px 8px;
      border: 1px solid #111;
      background: #fff;
    }
    .nav-links a:active {
      background: #111;
      color: #fff;
    }
    .notice {
      background: #fff8e1;
      border: 1px solid #f0c36d;
      padding: 8px 10px;
      font-size: 12.5px;
      margin-bottom: 14px;
    }
    .main-card {
      background: #fff;
      border: 2px solid #111;
      box-shadow: 3px 3px 0 #111;
      padding: 18px 16px;
      margin-bottom: 16px;
    }
    .app-header {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 6px 8px;
      margin-bottom: 8px;
    }
    .app-title {
      font-size: 17px;
      font-weight: 900;
      margin: 0;
      color: #000;
    }
    .ver-badge {
      display: inline-block;
      flex: none;
      max-width: 100%;
      overflow-wrap: anywhere;
      font-size: 12px;
      font-weight: 800;
      padding: 3px 8px;
      background: #111;
      color: #fff;
    }
    .ver-badge-test {
      background: #d9534f;
    }
    .ver-badge-empty {
      background: #8c98a4;
    }
    .btn-download-primary {
      display: block;
      width: 100%;
      padding: 13px 16px;
      background: #111;
      color: #fff;
      text-align: center;
      text-decoration: none;
      font-weight: 900;
      font-size: 15px;
      border: 2px solid #111;
      margin: 12px 0;
      cursor: pointer;
      box-shadow: 2px 2px 0 #333;
    }
    .btn-download-primary:active {
      background: #333;
      transform: translate(1px, 1px);
      box-shadow: 1px 1px 0 #333;
    }
    .btn-download-test {
      background: #333;
      border-color: #333;
    }
    .btn-download-disabled {
      background: #e9ecef;
      border-color: #ced4da;
      color: #6c757d;
      box-shadow: 2px 2px 0 #dee2e6;
      cursor: not-allowed;
    }
    .info-list {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 6px;
      background: #f8f9fa;
      border: 1px solid #ddd;
      padding: 8px 10px;
      margin: 10px 0;
      font-size: 12px;
    }
    .info-item {
      color: #333;
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .info-label {
      color: #777;
      font-weight: 600;
    }
    .changelog-box {
      background: #fafafa;
      border-left: 3px solid #111;
      padding: 10px 12px;
      margin: 10px 0 6px 0;
      font-size: 12.5px;
      line-height: 1.6;
      white-space: pre-line;
    }
    .changelog-title {
      font-weight: 800;
      font-size: 12px;
      color: #555;
      margin-bottom: 4px;
    }
    .details-box {
      margin-top: 14px;
      border-top: 1px solid #eee;
      padding-top: 10px;
    }
    summary {
      font-size: 12px;
      font-weight: 700;
      cursor: pointer;
      color: #555;
      padding: 4px 0;
    }
    .tech-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 11px;
      margin-top: 8px;
    }
    .tech-table th, .tech-table td {
      border: 1px solid #ddd;
      padding: 6px 8px;
      text-align: left;
    }
    .tech-table th {
      background: #f0f2f5;
      font-weight: 700;
    }
    .tech-table a {
      color: #111;
      font-weight: 700;
    }
    /* 深色自适应：不支持的浏览器整块忽略，保持上面的浅色 */
    @media (prefers-color-scheme: dark) {
      body { background: #14161a; color: #e6e8eb; color-scheme: dark; }
      .top-nav { border-bottom-color: #2f353d; }
      .nav-links a { background: #1a1d21; border-color: #2f353d; color: #c9cdd4; }
      .nav-links a:active { background: #2f353d; color: #ffffff; }
      .notice { background: #2a2413; border-color: #6b5518; color: #e8d9a8; }
      .main-card { background: #1a1d21; border-color: #2f353d; box-shadow: 3px 3px 0 #0b0d10; }
      .app-title { color: #e6e8eb; }
      .ver-badge { background: #2f353d; color: #e6e8eb; }
      .ver-badge-test { background: #a33a37; color: #ffffff; }
      .ver-badge-empty { background: #3a4048; color: #a8b0ba; }
      .btn-download-primary { background: #2e6591; border-color: #3a78a8; color: #ffffff; box-shadow: 2px 2px 0 #0b0d10; }
      .btn-download-primary:active { background: #3a78a8; box-shadow: 1px 1px 0 #0b0d10; }
      .btn-download-test { background: #3a3f46; border-color: #3a3f46; }
      .btn-download-disabled { background: #20242a; border-color: #2f353d; color: #6f7883; box-shadow: 2px 2px 0 #14161a; }
      .info-list { background: #20242a; border-color: #2f353d; }
      .info-item { color: #a8b0ba; }
      .info-label { color: #6f7883; }
      .changelog-box { background: #14161a; border-left-color: #6ea8d8; }
      .changelog-title { color: #8b94a0; }
      .details-box { border-top-color: #262b31; }
      summary { color: #8b94a0; }
      .tech-table th, .tech-table td { border-color: #2f353d; }
      .tech-table th { background: #20242a; }
      .tech-table a { color: #7fb2e0; }
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="top-nav">
      <div class="brand">旧聊 OldChat</div>
      <div class="nav-links">
        <a href="/">首页</a>
        <a href="/igotbanned">申诉通道</a>
      </div>
    </div>

    {{if .Notice}}<div class="notice">{{.Notice}}</div>{{end}}

    <!-- 正式版卡片 -->
    <div class="main-card">
      <div class="app-header">
        <h2 class="app-title">{{.Formal.Title}}</h2>
        <span class="ver-badge{{.Formal.BadgeClass}}">{{.Formal.Version}}</span>
      </div>

      {{if .Formal.Available}}
      <a class="btn-download-primary" href="{{.Formal.DownloadURL}}">下载正式版 APK 安装包</a>
      {{else}}
      <span class="btn-download-primary btn-download-disabled">正式版安装包暂未发布</span>
      {{end}}

      <div class="info-list">
        <div class="info-item"><span class="info-label">版本序号:</span> {{.Formal.VersionCode}}</div>
        <div class="info-item"><span class="info-label">更新通道:</span> {{.Formal.Channel}}</div>
        <div class="info-item"><span class="info-label">应用包名:</span> com.im.oldchat</div>
        <div class="info-item"><span class="info-label">系统要求:</span> Android 2.3+</div>
      </div>

      <div class="changelog-box">
        <div class="changelog-title">[版本更新内容]</div>
        <div>{{.Formal.Notes}}</div>
      </div>
    </div>

    <!-- 测试版卡片 -->
    <div class="main-card">
      <div class="app-header">
        <h2 class="app-title">{{.Dev.Title}}</h2>
        <span class="ver-badge ver-badge-test{{.Dev.BadgeClass}}">{{.Dev.Version}}</span>
      </div>

      {{if .Dev.Available}}
      <a class="btn-download-primary btn-download-test" href="{{.Dev.DownloadURL}}">下载最新测试版 APK</a>
      {{else}}
      <span class="btn-download-primary btn-download-disabled">测试版安装包暂未发布</span>
      {{end}}

      <div class="info-list">
        <div class="info-item"><span class="info-label">版本序号:</span> {{.Dev.VersionCode}}</div>
        <div class="info-item"><span class="info-label">更新通道:</span> {{.Dev.Channel}}</div>
        <div class="info-item"><span class="info-label">应用包名:</span> com.im.oldchat</div>
        <div class="info-item"><span class="info-label">系统要求:</span> Android 2.3+</div>
      </div>

      <div class="changelog-box">
        <div class="changelog-title">[测试更新内容]</div>
        <div>{{.Dev.Notes}}</div>
      </div>
    </div>

    {{if not .DesktopHidden}}
    <!-- WPF 桌面端卡片 -->
    <div class="main-card">
      <div class="app-header">
        <h2 class="app-title">{{.Desktop.Title}}</h2>
        <span class="ver-badge{{.Desktop.BadgeClass}}">{{.Desktop.Version}}</span>
      </div>

      {{if .Desktop.Available}}
      <a class="btn-download-primary" href="{{.Desktop.DownloadURL}}">下载 WPF 客户端安装包</a>
      {{else}}
      <span class="btn-download-primary btn-download-disabled">桌面端安装包暂未提供</span>
      {{end}}

      <div class="info-list">
        <div class="info-item"><span class="info-label">应用类型:</span> Windows 桌面客户端</div>
        <div class="info-item"><span class="info-label">运行环境:</span> .NET Framework 4.8</div>
        <div class="info-item"><span class="info-label">系统要求:</span> Windows 7 及以上</div>
        <div class="info-item"><span class="info-label">平台:</span> {{.Desktop.Channel}}</div>
      </div>

      <div class="changelog-box">
        <div class="changelog-title">[客户端说明]</div>
        <div>{{.Desktop.Notes}}</div>
      </div>
    </div>
    {{end}}

    <div class="details-box">
      <details>
        <summary>查看更新通道配置接口</summary>
        <div style="margin-top: 8px;">
          <table class="tech-table">
            <thead>
              <tr>
                <th>通道</th>
                <th>当前版本代码</th>
                <th>配置文件</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>正式通道</td>
                <td>{{.Formal.VersionCode}}</td>
                <td>{{if .Formal.ConfigURL}}<a href="{{.Formal.ConfigURL}}">{{.Formal.ConfigName}}</a>{{else}}{{.Formal.ConfigName}}（未生成）{{end}}</td>
              </tr>
              <tr>
                <td>测试通道</td>
                <td>{{.Dev.VersionCode}}</td>
                <td>{{if .Dev.ConfigURL}}<a href="{{.Dev.ConfigURL}}">{{.Dev.ConfigName}}</a>{{else}}{{.Dev.ConfigName}}（未生成）{{end}}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </div>
  </div>
</body>
</html>
`

var downloadPageTpl = template.Must(template.New("download_center").Parse(downloadPageHTML))
