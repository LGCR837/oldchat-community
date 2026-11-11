package httpapi

import "html/template"

type appLandingData struct {
	VersionText string
}

type landingRelease struct {
	VersionName string `json:"version_name"`
	APK         string `json:"apk"`
}

const appLandingHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Oldchat 旧聊 —— 轻量、可靠、兼容 Android 2.3 及以上版本的即时通讯系统。">
  <title>Oldchat 旧聊</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, "Segoe UI", "DejaVu Sans", "Lucida Grande", "Noto Sans SC", Helvetica, Arial, sans-serif;
      font-size: 14px;
      line-height: 1.6;
      color: #111;
      background: #fdfdfd;
    }
    a { color: #004488; text-decoration: underline; }
    a:hover { color: #002244; }
    .layout-wrapper { display: flex; min-height: 100vh; }
    
    /* 移动端菜单导航条（桌面端隐藏） */
    .mobile-nav-bar { display: none; }

    /* 左侧边栏 - 经典 urwid 紧凑两栏风格 */
    .sidebar {
      width: 250px;
      min-width: 250px;
      background: #f0f2f5;
      border-right: 1px solid #d0d4dc;
      padding: 18px 14px;
      font-size: 13px;
    }
    .sidebar-section { margin-bottom: 20px; }
    .sidebar-title {
      font-size: 11px;
      font-weight: bold;
      color: #495057;
      margin-bottom: 8px;
      border-bottom: 1px solid #d0d4dc;
      padding-bottom: 3px;
      letter-spacing: 0.5px;
    }
    .sidebar-list { list-style: none; padding-left: 0; }
    .sidebar-list li { margin: 5px 0; }
    .sidebar-list li::before { content: "» "; color: #8c98a4; }
    .sidebar-list a { text-decoration: none; color: #004488; }
    .sidebar-list a:hover { text-decoration: underline; }
    .sidebar-list a.highlight { font-weight: bold; color: #b02a37; }

    /* 右侧主内容区 */
    .content {
      flex: 1;
      max-width: 860px;
      padding: 24px 32px;
    }
    .ascii-logo {
      font-family: "DejaVu Sans Mono", "Consolas", "Courier New", monospace;
      font-size: 11px;
      line-height: 1.15;
      color: #2b5b84;
      white-space: pre;
      margin-bottom: 12px;
      user-select: none;
    }
    h1 { font-size: 24px; font-weight: bold; margin: 0 0 4px; color: #111; border-bottom: 2px solid #2b5b84; padding-bottom: 4px; }
    .subtitle { font-size: 13px; color: #495057; margin-bottom: 14px; }
    h2 { font-size: 16px; font-weight: bold; margin: 22px 0 8px; border-bottom: 1px solid #d0d4dc; padding-bottom: 4px; color: #212529; }
    p { margin-bottom: 10px; }

    /* 经典下载药丸按钮 */
    .btn-pill {
      display: inline-block;
      background: #e7f1ff;
      border: 1px solid #b6d4fe;
      padding: 7px 14px;
      font-weight: bold;
      font-size: 13px;
      margin: 4px 6px 4px 0;
      text-decoration: none;
      color: #0d6efd;
    }
    .btn-pill:hover { background: #cfe2ff; text-decoration: none; }
    .btn-pill.primary { background: #2b5b84; color: #fff; border-color: #1f4261; }
    .btn-pill.primary:hover { background: #224869; }

    /* 移动端自适应与侧边栏折叠 */
    @media (max-width: 768px) {
      .layout-wrapper { flex-direction: column; }
      .mobile-nav-bar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 8px 14px;
        background: #f0f2f5;
        border-bottom: 1px solid #d0d4dc;
      }
      .mobile-toggle-btn {
        background: #fff;
        border: 1px solid #ced4da;
        padding: 6px 12px;
        font-size: 13px;
        font-weight: bold;
        color: #004488;
        cursor: pointer;
      }
      .sidebar {
        width: 100%;
        min-width: 100%;
        border-right: none;
        border-bottom: 1px solid #d0d4dc;
        display: none;
      }
      .sidebar.open {
        display: block;
      }
      .content { padding: 18px 14px; }
      .ascii-logo { font-size: 8px; }
    }
  </style>
</head>
<body>
<div class="layout-wrapper">
  <!-- 移动端顶部菜单折叠控制栏 -->
  <div class="mobile-nav-bar">
    <button class="mobile-toggle-btn" id="sidebarToggle" type="button">☰ 导航菜单</button>
  </div>

  <!-- 左侧控制边栏 (Sidebar) -->
  <div class="sidebar" id="mainSidebar">
    <div class="sidebar-section">
      <div class="sidebar-title">快捷导航</div>
      <ul class="sidebar-list">
        <li><a href="/">官网主页</a></li>
        <li><a href="/register" class="highlight">注册账号</a></li>
        <li><a href="/update/">下载客户端</a></li>
        <li><a href="/routes.md">路由清单</a></li>
      </ul>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-title">在线服务</div>
      <ul class="sidebar-list">
        <li><a href="/status">服务运行状态</a></li>
        <li><a href="/app/music">网页音乐大厅</a></li>
        <li><a href="/shop">称号商店</a></li>
        <li><a href="/coin-tool">旧币工具</a></li>
        <li><a href="/igotbanned">封禁申诉</a></li>
      </ul>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-title">开发者平台</div>
      <ul class="sidebar-list">
        <li><a href="/bot-apply">申请机器人 Token</a></li>
        <li><a href="/channel-api/apply">申请频道 Token</a></li>
        <li><a href="/cip-upload">上传 CIP 小程序</a></li>
        <li><a href="/channel-manage">频道管理</a></li>
      </ul>
    </div>

    <div class="sidebar-section">
      <div class="sidebar-title">开放文档</div>
      <ul class="sidebar-list">
        <li><a href="/api.md">主协议规范</a></li>
        <li><a href="/in/api.md">完整接口参考</a></li>
        <li><a href="/client-guide.md">客户端开发指南</a></li>
        <li><a href="/lua-cip.md">CIP 小程序开发</a></li>
        <li><a href="/bot-api.md">机器人开发手册</a></li>
        <li><a href="/channel-api.md">频道开发手册</a></li>
        <li><a href="/oauth.md">第三方登录 OAuth</a></li>
      </ul>
    </div>
  </div>

  <!-- 右侧主内容区 (Content) -->
  <div class="content">
    <div class="ascii-logo">
  ___  _     _       _           _   
 / _ \| |   | |     | |         | |  
| | | | | __| | ___ | |__   __ _| |_ 
| | | | |/ _' |/ __|| '_ \ / _' | __|
| |_| | | (_| | (__ | | | | (_| | |_ 
 \___/|_|\__,_|\___||_| |_|\__,_|\__|</div>

    <h1>Oldchat 旧聊</h1>
    <div class="subtitle">{{.VersionText}} / 原生兼容 Android 2.3+</div>

    <p>轻量可靠的即时通讯应用：私聊、群聊、语音、图片、动态与 CIP 小程序，兼容旧设备。</p>

    <h2>客户端下载</h2>
    <div style="margin: 12px 0; display: flex; gap: 8px; flex-wrap: wrap;">
      <a class="btn-pill primary" href="/update/">前往客户端下载中心</a>
      <a class="btn-pill" style="background:#1976D2; color:#fff;" href="/update/oldchat-text-android1.6.apk">Android 1.6+ 兼容版 (1.5MB)</a>
    </div>
  </div>
</div>

<script>
  (function() {
    var btn = document.getElementById('sidebarToggle');
    var sidebar = document.getElementById('mainSidebar');
    if (btn && sidebar) {
      btn.onclick = function() {
        var isOpen = sidebar.className.indexOf('open') !== -1;
        if (isOpen) {
          sidebar.className = sidebar.className.replace(/\bopen\b/g, '').trim();
          btn.textContent = '☰ 导航菜单';
        } else {
          sidebar.className = (sidebar.className + ' open').trim();
          btn.textContent = '✕ 收起菜单';
        }
      };
    }
  })();
</script>
</body>
</html>
`

var appLandingTpl = template.Must(template.New("app_landing").Parse(appLandingHTML))
