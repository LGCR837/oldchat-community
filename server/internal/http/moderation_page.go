package httpapi

// moderationHTML 内容审核台页面模板。UI 风格与官网主页一致：
// 白底、2px 黑色描边、无圆角、等宽字体、hover 位移 + 硬阴影。
// 支持举报内容富文本智能解析（聊天记录、引用消息、图片渲染预览、音频/音乐在线试听播放）。
const moderationHTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>内容审核台 · 旧聊</title>
<style>
:root{--paper:#fff;--ink:#050505;--muted:#626262;--soft:#f5f5f5;--line:2px;--err:#d7263d;--ok:#0a7d33;--sans:-apple-system,BlinkMacSystemFont,"Segoe UI","Noto Sans SC",sans-serif;--mono:"SFMono-Regular",Consolas,"Liberation Mono",monospace}
*{box-sizing:border-box;border-radius:0!important}
html{scroll-behavior:smooth;background:var(--paper)}
body{margin:0;background:var(--paper);color:var(--ink);font-family:var(--sans);line-height:1.7;-webkit-font-smoothing:antialiased}
a{color:inherit;text-decoration:none}
.nav{height:64px;padding:0 40px;border-bottom:var(--line) solid var(--ink);display:flex;align-items:center;justify-content:space-between;gap:24px;position:sticky;top:0;background:var(--paper);z-index:20}
.brand{font-weight:900;letter-spacing:.8px;display:flex;align-items:center}
.logo{font-family:var(--mono);margin-right:10px}
.navlinks{display:flex;gap:24px}
.smallbtn{font-family:var(--mono);font-size:12px;font-weight:700;border-bottom:2px solid transparent}
.smallbtn:hover{border-color:var(--ink)}
.wrap{max-width:1080px;margin:0 auto;padding:44px 40px 90px}
.eyebrow{font-family:var(--mono);font-size:12px;font-weight:700;letter-spacing:1.6px;text-transform:uppercase}
.page-title{font-size:clamp(30px,5vw,46px);line-height:1.05;letter-spacing:-2px;margin:10px 0 6px;font-weight:900}
.page-sub{font-size:14px;color:var(--muted);margin:0 0 30px}
.alert{border:var(--line) solid var(--ink);padding:12px 16px;margin-bottom:22px;font:800 13px var(--mono)}
.alert-ok{color:var(--ok)}
.alert-err{color:var(--err)}
.tabs{display:flex;border:var(--line) solid var(--ink);margin-bottom:30px;flex-wrap:wrap}
.tab{flex:1;min-width:130px;text-align:center;padding:14px 10px;font:800 13px var(--mono);border-right:var(--line) solid var(--ink);letter-spacing:.4px;transition:background .15s,color .15s}
.tab:last-child{border-right:none}
.tab:hover{background:var(--soft)}
.tab.active{background:var(--ink);color:var(--paper)}
.tab .cnt{display:block;font-size:10px;margin-top:3px;opacity:.72}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:16px;margin-bottom:24px}
.stat{border:var(--line) solid var(--ink);padding:16px;text-align:center;transition:transform .15s,box-shadow .15s}
.stat:hover{transform:translate(-3px,-3px);box-shadow:3px 3px 0 var(--ink)}
.stat-label{font:11px var(--mono);color:var(--muted);letter-spacing:1px}
.stat-value{font:900 24px var(--mono);margin-top:6px}

/* Guidelines & Quantized Penalty Standards Banner */
.guidelines-box{border:var(--line) solid var(--ink);padding:16px 20px;margin-bottom:26px;background:var(--soft)}
.guidelines-title{font:900 13px var(--mono);letter-spacing:.8px;margin-bottom:12px;display:flex;align-items:center;justify-content:space-between;gap:12px;text-transform:uppercase;flex-wrap:wrap}
.guidelines-warn{color:var(--err);font-weight:900;font-size:12px;letter-spacing:0;background:var(--paper);border:1.5px solid var(--err);padding:2px 8px}
.guidelines-content{display:flex;flex-direction:column;gap:8px}
.guideline-item{display:flex;align-items:flex-start;gap:10px;font-size:12px;line-height:1.6;font-family:var(--sans)}
.guideline-item .num{font:900 12px var(--mono);color:var(--ink);background:var(--paper);border:1px solid var(--ink);padding:0 6px;line-height:18px;min-width:20px;text-align:center}
.guideline-item b{font-weight:800;color:var(--ink)}

/* Quick Filter Toolbar (Brutalist style) */
.filter-bar{display:flex;border:var(--line) solid var(--ink);margin-bottom:24px;background:var(--paper);align-items:stretch}
.filter-input{flex:1;border:none!important;background:transparent;padding:12px 16px;font:700 13px var(--mono);color:var(--ink);outline:none}
.filter-btn{border:none!important;border-left:var(--line) solid var(--ink)!important;background:var(--paper);padding:0 18px;font:800 12px var(--mono);cursor:pointer;transition:background .15s,color .15s}
.filter-btn:hover{background:var(--ink);color:var(--paper)}
.filter-info{font:700 11px var(--mono);color:var(--muted);padding:12px 14px;border-left:var(--line) solid var(--ink);display:flex;align-items:center;white-space:nowrap}

.list-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:4px 0 18px;flex-wrap:wrap}
.list-head h2{font:800 15px var(--mono);margin:0;letter-spacing:.4px}
.list-head .hint{font:12px var(--mono);color:var(--muted)}
.card{border:var(--line) solid var(--ink);margin-bottom:16px;padding:20px;display:grid;grid-template-columns:1fr auto;gap:20px;transition:transform .15s,box-shadow .15s;background:var(--paper)}
.card:hover{transform:translate(-3px,-3px);box-shadow:3px 3px 0 var(--ink)}
.card .head{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:8px}
.card .title{font:800 14px var(--mono)}
.meta{font:12px var(--mono);color:var(--muted);margin-bottom:10px;line-height:1.6;word-break:break-all}
.body{font-size:13px;border:1px solid var(--ink);background:var(--soft);padding:12px;white-space:pre-wrap;word-break:break-all;margin-bottom:12px;min-height:28px;max-height:360px;overflow:auto}
.body-mono{font-family:var(--mono);font-size:12px}
.status{font:800 11px var(--mono);padding:3px 10px;border:2px solid var(--ink);white-space:nowrap}
.st-pending,.st-open,.st-processing{background:var(--paper);color:var(--ink)}
.st-approved,.st-resolved{background:var(--ink);color:var(--paper)}
.st-rejected,.st-closed{background:var(--paper);color:var(--err)}

.card .actions{display:flex;flex-direction:column;gap:8px;align-items:stretch;min-width:240px}
.ops{display:flex;flex-direction:column;gap:14px;min-width:240px}
.ops .actions{min-width:0}
.ops .ban{border-top:2px dashed var(--ink);padding-top:12px}
.ban-title{font:800 11px var(--mono);letter-spacing:.6px;color:var(--err)}
.actions.ban button{background:var(--err)}
.actions.ban button:hover{background:var(--paper);color:var(--err);box-shadow:inset 0 0 0 2px var(--err)}
.actions select,.actions input[type=text],.actions input[type=number]{background:var(--paper);border:2px solid var(--ink);color:var(--ink);padding:8px 10px;font:13px var(--mono);width:100%}
.actions button{background:var(--ink);color:var(--paper);border:none;padding:9px 12px;font:800 12px var(--mono);cursor:pointer;transition:background .15s,color .15s}
.actions button:hover{background:var(--paper);color:var(--ink);box-shadow:inset 0 0 0 2px var(--ink)}
.actions label{font:11px var(--mono);color:var(--muted);display:flex;align-items:center;gap:6px}
.actions input[type=checkbox]{width:auto;accent-color:var(--ink)}

/* Brutalist Quick Action Preset Chips */
.presets{display:flex;flex-wrap:wrap;gap:4px;margin-top:2px}
.preset{font:800 10px var(--mono);padding:2px 6px;border:1px solid var(--ink);background:var(--soft);color:var(--ink);cursor:pointer;transition:background .12s,color .12s}
.preset:hover{background:var(--ink);color:var(--paper)}
.preset-danger:hover{background:var(--err);color:var(--paper);border-color:var(--err)}

.copy-btn{font:800 11px var(--mono);padding:3px 8px;border:1px solid var(--ink);background:var(--paper);cursor:pointer;transition:all .15s;margin-left:auto}
.copy-btn:hover{background:var(--ink);color:var(--paper)}

/* Chat Context Viewer Component */
.btn-ctx{display:inline-flex;align-items:center;gap:6px;padding:6px 12px;font:700 12px var(--mono);background:var(--paper);border:1.5px solid var(--ink);color:var(--ink);cursor:pointer;transition:all .15s;margin-top:6px;margin-bottom:6px}
.btn-ctx:hover{background:var(--ink);color:var(--paper)}
.ctx-container{border:2px solid var(--ink);background:#fcfcfc;padding:12px;margin-top:10px;margin-bottom:10px}
.ctx-head{font:800 12px var(--mono);padding-bottom:8px;margin-bottom:8px;border-bottom:1px solid #ddd;display:flex;justify-content:space-between;align-items:center}
.ctx-load-more{width:100%;padding:6px;font:700 11px var(--mono);background:#f0f0f0;border:1px dashed var(--ink);cursor:pointer;margin:6px 0;text-align:center}
.ctx-load-more:hover{background:#e0e0e0}
.ctx-list{display:flex;flex-direction:column;gap:8px;max-height:450px;overflow-y:auto;padding-right:4px}
.ctx-item{background:#fff;border:1px solid #ddd;padding:8px 10px;font-size:12px;line-height:1.5}
.ctx-item.is-target{border:2px solid var(--err);background:#fff5f5;box-shadow:0 0 6px rgba(239,68,68,0.2)}
.ctx-item-header{display:flex;justify-content:space-between;font-family:var(--mono);font-size:11px;color:var(--muted);margin-bottom:4px}
.ctx-item-sender{font-weight:700;color:var(--ink)}
.ctx-item-target-badge{background:var(--err);color:#fff;font-weight:800;padding:1px 6px;border-radius:2px;font-size:10px}
.ctx-item-quote{border-left:3px solid var(--muted);background:#f5f5f5;padding:4px 8px;margin-bottom:6px;font-size:11px;color:#555}
.ctx-item-body{font-size:13px;color:#111;word-break:break-all;white-space:pre-wrap}

.court-subtabs{display:flex;gap:8px;margin-bottom:20px;flex-wrap:wrap;}
.court-subtab{font:800 12px var(--mono);padding:8px 16px;border:2px solid var(--ink);background:var(--paper);color:var(--ink);transition:all .15s;display:inline-flex;align-items:center;gap:6px;}
.court-subtab:hover{background:var(--soft);}
.court-subtab.active{background:var(--ink);color:var(--paper);}
.parties-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-bottom:14px;background:var(--soft);border:1px solid var(--ink);padding:14px;}
@media(max-width:600px){.parties-grid{grid-template-columns:1fr;}}
.party-box{display:flex;align-items:center;gap:12px;}
.party-avatar{width:48px;height:48px;object-fit:cover;border:2px solid var(--ink);background:#fff;flex-shrink:0;}
.party-role{font:800 10px var(--mono);padding:2px 6px;border:1px solid var(--ink);background:#fff;display:inline-block;margin-bottom:2px;}
.party-name{font:800 14px var(--sans);line-height:1.2;}
.party-uid{font:11px var(--mono);color:var(--muted);margin-top:2px;}
.vote-bar{border:1px solid var(--ink);background:#e0e0e0;height:24px;position:relative;display:flex;margin:8px 0 14px;overflow:hidden;}
.vote-bar-ban{background:var(--err);height:100%;transition:width .3s;}
.vote-bar-keep{background:var(--ok);height:100%;transition:width .3s;}
.vote-bar-text{position:absolute;width:100%;height:100%;top:0;left:0;display:flex;align-items:center;justify-content:space-between;padding:0 10px;font:800 11px var(--mono);color:#fff;text-shadow:0 1px 2px rgba(0,0,0,0.8);}
.evidence-box{background:#fff;border:1px solid var(--ink);padding:12px;margin-bottom:12px;}
.evidence-title{font:800 12px var(--mono);margin-bottom:6px;display:flex;align-items:center;gap:6px;}
.jury-section{border-top:1px dashed var(--ink);padding-top:10px;margin-top:10px;}
.jury-item{display:flex;align-items:flex-start;gap:10px;padding:8px 0;border-bottom:1px dotted #ccc;font-size:12px;}
.jury-avatar{width:32px;height:32px;object-fit:cover;border:1px solid var(--ink);background:#fff;flex-shrink:0;}

.empty{border:var(--line) dashed var(--ink);color:var(--muted);font:800 13px var(--mono);text-align:center;padding:36px 16px}
.pager{display:flex;align-items:center;justify-content:center;gap:16px;margin-top:30px;font-family:var(--mono);flex-wrap:wrap}
.pg{display:inline-block;padding:10px 18px;border:2px solid var(--ink);font:800 13px var(--mono);transition:transform .15s,box-shadow .15s,background .15s,color .15s}
.pg:hover{transform:translate(-2px,-2px);box-shadow:2px 2px 0 var(--ink);background:var(--ink);color:var(--paper)}
.pg.off{opacity:.35;pointer-events:none}
.pg-info{font-size:13px;color:var(--muted)}
.footer{border-top:var(--line) solid var(--ink);padding:30px 20px;text-align:center;font:12px var(--mono);color:var(--muted)}
@media(max-width:760px){.nav{padding:0 18px;height:auto;min-height:64px}.navlinks{gap:12px;flex-wrap:wrap;justify-content:flex-end}.smallbtn{font-size:10px}.wrap{padding:30px 18px 70px}.card{grid-template-columns:1fr}.card .actions{min-width:0}.tab{min-width:110px}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}.stat,.card,.pg{transition:none}.stat:hover,.card:hover,.pg:hover{transform:none;box-shadow:none}}
</style>
</head>
<body>
<nav class="nav">
  <a class="brand" href="/"><span class="logo">OLDCHAT/</span><span>内容审核台</span></a>
  <div class="navlinks">
    {{if .CurrentUserName}}<span class="smallbtn" style="color:var(--muted);cursor:default;">👤 {{.CurrentUserName}}{{if .IsSuperAdmin}} (超级管理员){{else}} (审核员){{end}}</span>{{end}}
    {{if .IsSuperAdmin}}<a class="smallbtn" href="/admins">ADMIN 主后台</a>{{end}}
    <a class="smallbtn" href="/">HOME 官网</a>
    <a class="smallbtn" href="/admins/moderation/logout">LOGOUT 登出</a>
  </div>
</nav>
<main class="wrap">
  <div class="eyebrow">MODERATION CONSOLE / ADMIN ONLY</div>
  <h1 class="page-title">内容审核台</h1>
  <p class="page-sub">公开法庭 · 封禁申诉 · 用户举报 · Bug 反馈 · 机器人与频道 Token 申请 —— 分区处理，分页浏览。</p>
  {{if .Success}}<div class="alert alert-ok">[ OK ] {{.Success}}</div>{{end}}
  {{if .Error}}<div class="alert alert-err">[ ERR ] {{.Error}}</div>{{end}}

  <div class="guidelines-box">
    <div class="guidelines-title">
      <span>⚖️ 审核处置标准与量刑量度指引</span>
      <span class="guidelines-warn">⚠️ 任何操作都会被后台记录 如有违规审核，恶意审核将会停用/封号处理</span>
    </div>
    <div class="guidelines-content">
      <div class="guideline-item">
        <span class="num">1</span>
        <div><b>刷屏、骂人、恶意攻击与扰乱秩序</b>：第 1 次违规给予驳回警告或下架处理；第 2 次违规封号 <b>24 小时</b>；后续屡犯以此类推递增至 <b>7 天（7day）</b>、<b>30 天（1 个月）</b>直至永久封号。</div>
      </div>
      <div class="guideline-item">
        <span class="num">2</span>
        <div><b>不正常言论（涉政/暴力/低俗/敏感/人身威胁）</b>：首次直接封号 <b>24 小时</b>；后续屡犯以此类推递增至 <b>7 天</b>、<b>30 天</b>直至永久封禁账号并封设备。</div>
      </div>
      <div class="guideline-item">
        <span class="num">3</span>
        <div><b>违规图片、文件、音乐与涉案资源</b>：严格参照第 2 条标准处置（直接封号 <b>24 小时</b>起并递增），同时处理时务必勾选<b>“同步下架/删除涉案资源与音频”</b>。</div>
      </div>
      <div class="guideline-item">
        <span class="num">4</span>
        <div><b>电信诈骗、黑灰产、跑分洗钱、招嫖赌博与恶意引流</b>：<b>零容忍，首次直接永久封号并封禁设备（720h / 永久）</b>；涉案群组/频道直接封停或解散。</div>
      </div>
      <div class="guideline-item">
        <span class="num">5</span>
        <div><b>恶意举报、虚假指控与滥用审核机制</b>：对无证据恶意诬告、报复性举报者驳回处理并扣除信誉分；情节严重或连续滥用举报者，反向予以 <b>24h ~ 7天</b> 封号惩戒。</div>
      </div>
      <div class="guideline-item">
        <span class="num">6</span>
        <div><b>封禁申诉与二审复核规范</b>：申诉人须有诚恳反省态度并提供合理说明；初犯或误封且封禁期满者可审核通过（勾选“通过时解封”）；严重违法或屡犯者一律驳回维持原判。</div>
      </div>
    </div>
  </div>

  <div class="tabs">
    <a class="tab {{if eq .ActiveSection "public_court"}}active{{end}}" href="/admins/moderation?section=public_court">PUBLIC COURT 公开法庭<span class="cnt">{{.PublicCourtPending}} 待二审</span></a>
    <a class="tab {{if eq .ActiveSection "court"}}active{{end}}" href="/admins/moderation?section=court">COURT 申诉<span class="cnt">{{.CourtUnhandled}} 待处理</span></a>
    <a class="tab {{if eq .ActiveSection "reports"}}active{{end}}" href="/admins/moderation?section=reports">REPORTS 举报<span class="cnt">{{.ReportsUnhandled}} 待处理</span></a>
    <a class="tab {{if eq .ActiveSection "bugs"}}active{{end}}" href="/admins/moderation?section=bugs">BUGS 反馈<span class="cnt">{{.BugsUnhandled}} 待处理</span></a>
    <a class="tab {{if eq .ActiveSection "bots"}}active{{end}}" href="/admins/moderation?section=bots">BOT TOKENS<span class="cnt">{{.BotsUnhandled}} 待审核</span></a>
    <a class="tab {{if eq .ActiveSection "channels"}}active{{end}}" href="/admins/moderation?section=channels">CHANNEL TOKENS<span class="cnt">{{.ChannelsUnhandled}} 待审核</span></a>
    <a class="tab {{if eq .ActiveSection "cip"}}active{{end}}" href="/admins/moderation?section=cip">CIP 小程序<span class="cnt">{{.CipUnhandled}} 待审核</span></a>
    <a class="tab {{if eq .ActiveSection "tokens"}}active{{end}}" href="/admins/moderation?section=tokens">TOKENS 可用<span class="cnt">{{.Total}} 个</span></a>
    <a class="tab {{if eq .ActiveSection "crashes"}}active{{end}}" href="/admins/moderation?section=crashes">CRASHES 崩溃<span class="cnt">{{.CrashTotal}} 条</span></a>
    <a class="tab {{if eq .ActiveSection "logs"}}active{{end}}" href="/admins/moderation?section=logs">LOGS 操作日志<span class="cnt">{{if eq .ActiveSection "logs"}}{{.Total}}{{else}}-{{end}} 条</span></a>
    <a class="tab {{if eq .ActiveSection "archived"}}active{{end}}" href="/admins/moderation?section=archived">ARCHIVED 已处理<span class="cnt">{{.HandledTotal}} 条</span></a>
    <a class="tab {{if eq .ActiveSection "inspector"}}active{{end}}" href="/admins/moderation?section=inspector">INSPECTOR 查成分<span class="cnt">画像与动态</span></a>
  </div>

  <div class="stats">
    <div class="stat"><div class="stat-label">TOTAL</div><div class="stat-value">{{.Total}}</div></div>
    <div class="stat"><div class="stat-label">PAGE</div><div class="stat-value">{{.Page}} / {{.TotalPages}}</div></div>
    <div class="stat"><div class="stat-label">PER PAGE</div><div class="stat-value">15</div></div>
    <div class="stat"><div class="stat-label">UNHANDLED</div><div class="stat-value">{{sumAll .PublicCourtPending .CourtUnhandled .ReportsUnhandled .BugsUnhandled .BotsUnhandled .ChannelsUnhandled}}</div></div>
  </div>

  <!-- Real-time instant filter -->
  <div class="filter-bar">
    <input type="text" class="filter-input" id="quickFilter" placeholder="[ 实时过滤当前页: 输入 UID / 用户名 / ID / 关键词 / 设备型号 ]">
    <button type="button" class="filter-btn" id="clearFilter">清空</button>
    <div class="filter-info" id="filterStats">第 {{.Page}} / {{.TotalPages}} 页 · 共 {{.Total}} 条</div>
  </div>

  {{if eq .ActiveSection "public_court"}}
  <div class="list-head">
    <h2>⚖️ 公开法庭 · 人民陪审团案件</h2>
    <span class="hint">按案件状态与开庭时间排序 · 每页 15 条</span>
  </div>

  <div class="court-subtabs">
    <a class="court-subtab {{if eq .PublicCourtSub "all"}}active{{end}}" href="/admins/moderation?section=public_court&sub=all">全部案件 ({{.Total}})</a>
    <a class="court-subtab {{if eq .PublicCourtSub "pending_review"}}active{{end}}" href="/admins/moderation?section=public_court&sub=pending_review">🔴 待二审复核 ({{.PublicCourtPending}})</a>
    <a class="court-subtab {{if eq .PublicCourtSub "open"}}active{{end}}" href="/admins/moderation?section=public_court&sub=open">🟡 审理进行中 ({{.PublicCourtOpen}})</a>
    <a class="court-subtab {{if eq .PublicCourtSub "closed"}}active{{end}}" href="/admins/moderation?section=public_court&sub=closed">🟢 已结案完事 ({{.PublicCourtClosed}})</a>
  </div>

  <div id="cardList">
  {{range .PublicCourtCases}}
  <div class="card" data-filter="{{.Summary.ID}} {{.Summary.ReporterUID}} {{.Summary.ReporterName}} {{.Summary.DefendantUID}} {{.Summary.DefendantName}} {{.Summary.ReportReason}} {{.Summary.Verdict}} {{.Summary.Status}}">
    <div>
      <div class="head">
        <span class="title">案件 #{{.Summary.ID}}</span>
        <span class="status st-{{publicCourtStatusClass .Summary.Status}}">{{publicCourtStatusText .Summary.Status}}</span>
        {{if .Summary.Verdict}}<span class="status {{if eq .Summary.Verdict "ban"}}st-rejected{{else}}st-approved{{end}}">裁决: {{.Summary.Verdict}}{{if eq .Summary.Verdict "ban"}} ({{.Summary.BanHours}}h){{end}}</span>{{end}}
        <span class="title" style="font-size:12px;color:var(--muted);">开庭: {{.Summary.CreatedAt.Format "2006-01-02 15:04"}}</span>
      </div>

      <!-- 双方当事人画像 (举报方 vs 被告方) -->
      <div class="parties-grid">
        <div class="party-box">
          <img src="{{if .Summary.ReporterAvatar}}{{.Summary.ReporterAvatar}}{{else}}data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23626262' d='M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z'/%3E%3C/svg%3E{{end}}" class="party-avatar" alt="举报方头像" onerror="this.src='data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 24 24\'%3E%3Cpath fill=\'%23626262\' d=\'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z\'/%3E%3C/svg%3E'">
          <div>
            <span class="party-role">⚖️ 举报方 (原告)</span>
            <div class="party-name">{{if .Summary.ReporterName}}{{.Summary.ReporterName}}{{else}}{{.Summary.ReporterUID}}{{end}}</div>
            <div class="party-uid">UID: {{.Summary.ReporterUID}}</div>
          </div>
        </div>
        <div class="party-box">
          <img src="{{if .Summary.DefendantAvatar}}{{.Summary.DefendantAvatar}}{{else}}data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23626262' d='M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z'/%3E%3C/svg%3E{{end}}" class="party-avatar" alt="被举报方头像" onerror="this.src='data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' viewBox=\'0 0 24 24\'%3E%3Cpath fill=\'%23626262\' d=\'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z\'/%3E%3C/svg%3E'">
          <div>
            <span class="party-role" style="color:var(--err);">🚨 被举报方 (被告)</span>
            <div class="party-name">{{if .Summary.DefendantName}}{{.Summary.DefendantName}}{{else}}{{.Summary.DefendantUID}}{{end}}</div>
            <div class="party-uid" style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
              <span>UID: {{.Summary.DefendantUID}}</span>
              <a href="/admins/moderation?section=inspector&q={{.Summary.DefendantUID}}" target="_blank" rel="noopener" style="font:700 11px var(--mono);background:var(--paper);color:var(--ink);border:1.5px solid var(--ink);padding:1px 6px;text-decoration:none;display:inline-flex;align-items:center;gap:3px;">🔍 个人空间/画像</a>
            </div>
            <div style="margin-top:4px;display:flex;gap:6px;flex-wrap:wrap;">
              <span class="status {{if gt .DefendantReportedCount 1}}st-rejected{{else}}st-pending{{end}}" style="font-size:10px;padding:1px 6px;">历史被举报: {{.DefendantReportedCount}} 次</span>
              <span class="status {{if gt .DefendantBanCount 0}}st-rejected{{else}}st-pending{{end}}" style="font-size:10px;padding:1px 6px;">历史封禁: {{.DefendantBanCount}} 次</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 陪审团投票对比条 -->
      <div style="font:800 11px var(--mono);margin-bottom:4px;display:flex;justify-content:space-between;">
        <span>陪审团初审投票 (共 {{.Summary.TotalVoteCount}} 票 / 10 票锁定二审)</span>
        <span>封禁: {{.Summary.BanVoteCount}} 票 vs 保留: {{.Summary.KeepVoteCount}} 票</span>
      </div>
      <div class="vote-bar">
        <div class="vote-bar-ban" style="width:{{if gt .Summary.TotalVoteCount 0}}{{divMul .Summary.BanVoteCount .Summary.TotalVoteCount 100}}%{{else}}0%{{end}};"></div>
        <div class="vote-bar-keep" style="width:{{if gt .Summary.TotalVoteCount 0}}{{divMul .Summary.KeepVoteCount .Summary.TotalVoteCount 100}}%{{else}}0%{{end}};"></div>
        <div class="vote-bar-text">
          <span>封禁 {{.Summary.BanVoteCount}} 票</span>
          <span>保留 {{.Summary.KeepVoteCount}} 票</span>
        </div>
      </div>

      <!-- 涉案指控与证据（富媒体智能解析） -->
      <div class="evidence-box">
        <div class="evidence-title">📑 举报指控与涉案内容：</div>
        <div class="body report-render" data-raw-report="{{.Summary.ReportReason}}\n{{.Summary.ReportEvidence}}" data-target-uid="{{.Summary.DefendantUID}}">{{.Summary.ReportReason}}
{{.Summary.ReportEvidence}}</div>
      </div>

      {{if or .Summary.DefenseReason .Summary.DefenseEvidence}}
      <div class="evidence-box" style="background:#f9fbf9;border-color:var(--ok);">
        <div class="evidence-title" style="color:var(--ok);">🛡️ 被告自辩与陈述：</div>
        <div class="body report-render" data-raw-report="{{.Summary.DefenseReason}}\n{{.Summary.DefenseEvidence}}">{{.Summary.DefenseReason}}
{{.Summary.DefenseEvidence}}</div>
      </div>
      {{end}}

      <!-- 陪审团投票与评论详情 (可折叠) -->
      {{if .Votes}}
      <details class="jury-section">
        <summary style="cursor:pointer;font:800 12px var(--mono);color:var(--ink);">[ 👥 查看陪审团具体投票记录 ({{len .Votes}} 人已投) ]</summary>
        <div style="margin-top:10px;">
          {{range .Votes}}
          <div class="jury-item">
            <img src="{{if .VoterAvatar}}{{.VoterAvatar}}{{else}}data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23626262' d='M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z'/%3E%3C/svg%3E{{end}}" class="jury-avatar" alt="陪审员头像">
            <div style="flex:1;">
              <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
                <span style="font-weight:700;font-family:var(--mono);">{{if .VoterName}}{{.VoterName}}{{else}}{{.VoterUID}}{{end}}</span>
                <span class="status {{if eq .Vote "ban"}}st-rejected{{else}}st-approved{{end}}" style="font-size:10px;padding:1px 6px;">
                  {{if eq .Vote "ban"}}🔴 投给【封禁】{{else}}🟢 投给【保留/无罪】{{end}}
                </span>
                <span style="font-size:11px;color:var(--muted);font-family:var(--mono);">{{.CreatedAt.Format "2006-01-02 15:04"}}</span>
              </div>
              {{if .Reason}}<div style="margin-top:3px;color:#222;font-size:12px;"><strong>理由:</strong> {{.Reason}}</div>{{end}}
              {{if .Evidence}}<div style="margin-top:2px;font-size:11px;color:var(--muted);" class="report-render" data-raw-report="{{.Evidence}}">{{.Evidence}}</div>{{end}}
            </div>
          </div>
          {{end}}
        </div>
      </details>
      {{end}}

      {{if .Discussions}}
      <details class="jury-section">
        <summary style="cursor:pointer;font:800 12px var(--mono);color:var(--ink);">[ 💬 查看法庭讨论发言 (共 {{len .Discussions}} 条) ]</summary>
        <div style="margin-top:10px;">
          {{range .Discussions}}
          <div class="jury-item">
            <img src="{{if .UserAvatar}}{{.UserAvatar}}{{else}}data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23626262' d='M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z'/%3E%3C/svg%3E{{end}}" class="jury-avatar" alt="发言人头像">
            <div style="flex:1;">
              <div style="font-weight:700;font-family:var(--mono);font-size:12px;">
                {{if .UserName}}{{.UserName}}{{else}}{{.UserUID}}{{end}} <span style="font-size:11px;color:var(--muted);font-weight:normal;">{{.CreatedAt.Format "2006-01-02 15:04"}}</span>
              </div>
              <div style="margin-top:3px;font-size:12px;color:#222;white-space:pre-wrap;">{{.Body}}</div>
            </div>
          </div>
          {{end}}
        </div>
      </details>
      {{end}}

      {{if .Summary.AdminNote}}<div class="meta" style="margin-top:10px;font-weight:700;">👨‍⚖️ 管理员二审备注: {{.Summary.AdminNote}}</div>{{end}}
    </div>

    <!-- 右侧操作栏 (二审终审) -->
    <div class="ops">
      {{if or (eq .Summary.Status "pending_review") (ge .Summary.TotalVoteCount 5)}}
      <form class="actions ban" method="post" action="/admins/moderation/public-court/review" onsubmit="return confirm('确认提交该案件的终审裁决吗？');">
        <input type="hidden" name="case_id" value="{{.Summary.ID}}">
        <div class="ban-title">👨‍⚖️ 二审终审裁决</div>
        <select name="verdict" id="verdict_{{.Summary.ID}}" onchange="onCourtVerdictChange('{{.Summary.ID}}', this.value)">
          <option value="ban" {{if eq .Summary.Verdict "ban"}}selected{{end}}>🔴 裁定违规并封禁 (ban)</option>
          <option value="keep" {{if eq .Summary.Verdict "keep"}}selected{{end}}>🟢 裁定无罪/维持保留 (keep)</option>
        </select>
        <div id="verdict_tip_{{.Summary.ID}}" style="font-size:11px;font-weight:bold;margin:4px 0;color:{{if eq .Summary.Verdict "keep"}}#15803d{{else}}#b91c1c{{end}};">
          {{if eq .Summary.Verdict "keep"}}🟢 裁定无罪：维持保留，不执行任何封禁处罚{{else}}🔴 裁定违规：将对被举报人执行封禁处罚{{end}}
        </div>
        <div id="ban_options_{{.Summary.ID}}" style="{{if eq .Summary.Verdict "keep"}}display:none;{{end}}">
          <input type="number" name="ban_hours" id="ban_hours_{{.Summary.ID}}" value="{{if gt .Summary.BanHours 0}}{{.Summary.BanHours}}{{else}}24{{end}}" min="1" placeholder="封禁时长 (小时)">
          <div class="presets">
            <span class="preset preset-danger" onclick="setBanDuration('ban_hours_{{.Summary.ID}}', 24)">24h</span>
            <span class="preset preset-danger" onclick="setBanDuration('ban_hours_{{.Summary.ID}}', 72)">3天</span>
            <span class="preset preset-danger" onclick="setBanDuration('ban_hours_{{.Summary.ID}}', 168)">7天</span>
            <span class="preset preset-danger" onclick="setBanDuration('ban_hours_{{.Summary.ID}}', 720)">30天</span>
          </div>
          <label style="font-size:11px;margin-top:4px;display:block;"><input type="checkbox" name="takedown_resource" value="1" checked> 同步下架/删除涉案资源与音频</label>
        </div>
        <input type="text" name="admin_note" placeholder="二审裁决理由/备注 (选填)" value="{{.Summary.AdminNote}}">
        <button type="submit" id="verdict_btn_{{.Summary.ID}}" style="{{if eq .Summary.Verdict "keep"}}background:#15803d;{{else}}background:#b91c1c;{{end}}">{{if eq .Summary.Verdict "keep"}}✅ 确认裁定无罪并结案{{else}}🚨 确认裁定违规并执行封禁{{end}}</button>
      </form>
      {{else if eq .Summary.Status "open"}}
      <div style="font:800 12px var(--mono);color:var(--muted);text-align:center;padding:16px 8px;border:1px dashed var(--ink);background:rgba(0,0,0,0.02);">
        <div style="font-size:13px;color:#b45309;margin-bottom:6px;">⏳ 审理投票进行中</div>
        <div style="font-size:11px;line-height:1.5;">当前总票数：<strong>{{.Summary.TotalVoteCount}} / 5 票</strong><br>（须累计满 5 票或进入待二审状态后方可进行终审判决）</div>
      </div>
      {{else}}
      <div style="font:800 12px var(--mono);color:var(--muted);text-align:center;padding:20px;border:1px dashed var(--ink);">
        {{if eq .Summary.Status "closed"}}✅ 案件已结案归档{{else}}⚪ 案件已撤诉{{end}}
        {{if .Summary.ClosedAt.Valid}}<div style="font-size:10px;margin-top:4px;">结案时间: {{.Summary.ClosedAt.Time.Format "2006-01-02 15:04"}}</div>{{end}}
      </div>
      {{end}}
    </div>
  </div>
  {{else}}<div class="empty">暂无公开法庭案件</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "court"}}
  <div class="list-head"><h2>🏛 法庭 · 封禁申诉</h2><span class="hint">待处理优先排序 · 每页 15 条</span></div>
  <div id="cardList">
  {{range .BanAppeals}}
  <div class="card" data-filter="{{.ID}} {{.Username}} {{.UID}} {{.BanReason}} {{.AppealText}} {{.Contact}}">
    <div>
      <div class="head">
        <span class="title">#{{.ID}}</span>
        <span class="status st-{{.Status}}">{{moderationStatusText .Status}}</span>
        <span class="title">{{.Username}} · {{.UID}}</span>
      </div>
      <div class="meta">封禁原因: {{.BanReason}} · 提交于 {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body">{{.AppealText}}</div>
      {{if .Contact}}<div class="meta">联系方式: {{.Contact}}</div>{{end}}
      {{if .AdminNote}}<div class="meta">管理员备注: {{.AdminNote}}</div>{{end}}
    </div>
    <form class="actions" method="post" action="/admins/moderation/appeal/status">
      <input type="hidden" name="id" value="{{.ID}}">
      <select name="status" id="status_{{.ID}}">
        <option value="pending" {{if eq .Status "pending"}}selected{{end}}>待处理</option>
        <option value="processing" {{if eq .Status "processing"}}selected{{end}}>处理中</option>
        <option value="approved" {{if eq .Status "approved"}}selected{{end}}>通过（解封）</option>
        <option value="rejected" {{if eq .Status "rejected"}}selected{{end}}>驳回</option>
      </select>
      <input type="text" name="admin_note" id="note_{{.ID}}" placeholder="处理备注" value="{{.AdminNote}}">
      <div class="presets">
        <span class="preset" onclick="fillAppealPreset('{{.ID}}', 'approved', '核实无误，予以解封', true)">误封解封</span>
        <span class="preset" onclick="fillAppealPreset('{{.ID}}', 'rejected', '违规属实，维持原判', false)">维持原判</span>
        <span class="preset" onclick="fillAppealPreset('{{.ID}}', 'processing', '请补充有效证据与说明', false)">补充材料</span>
      </div>
      <label><input type="checkbox" name="unban_on_approve" id="unban_{{.ID}}" value="1" checked> 通过时解封</label>
      <button type="submit">更新状态</button>
    </form>
  </div>
  {{else}}<div class="empty">暂无申诉</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "reports"}}
  <div class="list-head"><h2>🚨 用户举报</h2><span class="hint">按提交时间倒序 · 每页 15 条 · 支持多媒体解析与试听</span></div>
  <div id="cardList">
  {{range .UserReports}}
  <div class="card" data-filter="{{.ID}} {{.TargetUID}} {{.ReporterUID}} {{.TargetDevice}} {{.Reason}} {{.Result}}">
    <div>
      <div class="head">
        <span class="title">#{{.ID}}</span>
        <span class="status st-{{.Status}}">{{moderationStatusText .Status}}</span>
        <span class="title">举报 {{.TargetUID}}</span>
        <a href="/admins/moderation?section=inspector&q={{.TargetUID}}" target="_blank" rel="noopener" style="font:700 11px var(--mono);background:var(--paper);color:var(--ink);border:1.5px solid var(--ink);padding:2px 8px;text-decoration:none;display:inline-flex;align-items:center;gap:4px;">🔍 查看个人空间/画像</a>
        <span class="status {{if gt .TargetReportedCount 1}}st-rejected{{else}}st-pending{{end}}" style="font-size:10px;padding:1px 6px;">历史被举报: {{.TargetReportedCount}} 次</span>
        <span class="status {{if gt .TargetBanCount 0}}st-rejected{{else}}st-pending{{end}}" style="font-size:10px;padding:1px 6px;">历史封禁: {{.TargetBanCount}} 次</span>
      </div>
      <div class="meta">举报人: {{.ReporterUID}} · 目标设备: {{.TargetDevice}} · {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body report-render" data-raw-report="{{.Reason}}" data-target-uid="{{.TargetUID}}">{{.Reason}}</div>
      {{if .Result}}<div class="meta">处理结果: {{.Result}}</div>{{end}}
    </div>
    <div class="ops">
      <form class="actions" method="post" action="/admins/moderation/report/status">
        <input type="hidden" name="id" value="{{.ID}}">
        <select name="status">
          <option value="pending" {{if eq .Status "pending"}}selected{{end}}>待处理</option>
          <option value="processing" {{if eq .Status "processing"}}selected{{end}}>处理中</option>
          <option value="resolved" {{if eq .Status "resolved"}}selected{{end}}>已处理</option>
          <option value="rejected" {{if eq .Status "rejected"}}selected{{end}}>无效举报</option>
        </select>
        <input type="text" name="result" placeholder="处理结果" value="{{.Result}}">
        <label style="font-size:11px;margin-top:2px;"><input type="checkbox" name="takedown_resource" value="1"> 顺带下架/删除涉案资源</label>
        <button type="submit">更新状态</button>
      </form>
      <form class="actions ban" method="post" action="/admins/moderation/report/ban" onsubmit="return confirmBanAction(this, '{{.TargetUID}}');">
        <input type="hidden" name="id" value="{{.ID}}">
        <div class="ban-title">⚡ 封禁与处置</div>
        <div style="display:flex;gap:10px;flex-wrap:wrap;">
          <label><input type="checkbox" name="ban_user" value="1" checked> 封号</label>
          <label><input type="checkbox" name="ban_device" value="1"> 封设备</label>
          <label><input type="checkbox" name="takedown_resource" value="1" checked> 下架/删除资源</label>
        </div>
        <input type="number" name="duration_hours" id="duration_{{.ID}}" value="0" min="0" placeholder="时长(小时,0=永久)">
        <div class="presets">
          <span class="preset preset-danger" onclick="setBanDuration('{{.ID}}', 24)">24h</span>
          <span class="preset preset-danger" onclick="setBanDuration('{{.ID}}', 72)">3天</span>
          <span class="preset preset-danger" onclick="setBanDuration('{{.ID}}', 168)">7天</span>
          <span class="preset preset-danger" onclick="setBanDuration('{{.ID}}', 720)">30天</span>
          <span class="preset preset-danger" onclick="setBanDuration('{{.ID}}', 0)">永久</span>
        </div>
        <input type="text" name="reason" placeholder="封禁原因（默认：举报处理）">
        <button type="submit">执行处置</button>
      </form>
    </div>
  </div>
  {{else}}<div class="empty">暂无举报</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "bugs"}}
  <div class="list-head"><h2>🐛 Bug 反馈</h2><span class="hint">按提交时间倒序 · 每页 15 条</span></div>
  <div id="cardList">
  {{range .BugReports}}
  <div class="card" data-filter="{{.ID}} {{.UserUID}} {{.DeviceModel}} {{.AndroidVersion}} {{.AppVersion}} {{.Content}}">
    <div>
      <div class="head">
        <span class="title">#{{.ID}}</span>
        <span class="status st-{{.Status}}">{{moderationStatusText .Status}}</span>
        <span class="title">{{.UserUID}}</span>
      </div>
      <div class="meta">{{.DeviceModel}} · Android {{.AndroidVersion}} · v{{.AppVersion}} · {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body">{{.Content}}</div>
      {{if .AdminNote}}<div class="meta">管理员备注: {{.AdminNote}}</div>{{end}}
    </div>
    <form class="actions" method="post" action="/admins/moderation/bug/status">
      <input type="hidden" name="id" value="{{.ID}}">
      <select name="status" id="bug_status_{{.ID}}">
        <option value="open" {{if eq .Status "open"}}selected{{end}}>待处理</option>
        <option value="resolved" {{if eq .Status "resolved"}}selected{{end}}>已解决</option>
        <option value="closed" {{if eq .Status "closed"}}selected{{end}}>已关闭</option>
      </select>
      <input type="text" name="note" id="bug_note_{{.ID}}" placeholder="处理备注" value="{{.AdminNote}}">
      <div class="presets">
        <span class="preset" onclick="fillBugPreset('{{.ID}}', 'resolved', '已在最新测试版修复')">已修复</span>
        <span class="preset" onclick="fillBugPreset('{{.ID}}', 'closed', '无法复现，等待补充')">无法复现</span>
        <span class="preset" onclick="fillBugPreset('{{.ID}}', 'open', '已记录并排期修复')">已排期</span>
      </div>
      <button type="submit">更新状态</button>
    </form>
  </div>
  {{else}}<div class="empty">暂无 Bug 反馈</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "bots"}}
  <div class="list-head"><h2>机器人 Message Token 申请</h2><span class="hint">通过后由申请人自行领取一次性 Token</span></div>
  <div id="cardList">
  {{range .BotPermits}}
  <div class="card" data-filter="{{.ID}} {{.Username}} {{.UID}} {{.Scopes}} {{.Reason}} {{.ReviewReason}}">
    <div>
      <div class="head">
        <span class="title">{{.Username}} · {{.UID}}</span>
        <span class="status st-{{.Status}}">{{moderationStatusText .Status}}</span>
      </div>
      <div class="meta">#{{.ID}} · scopes: {{.Scopes}} · {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body">{{.Reason}}</div>
      {{if .ReviewReason}}<div class="meta">审核意见：{{.ReviewReason}}</div>{{end}}
    </div>
    {{if eq .Status "applied"}}
    <form class="actions" method="post" action="/admins/moderation/bot/review">
      <input type="hidden" name="id" value="{{.ID}}">
      <input type="text" name="review_reason" id="bot_reason_{{.ID}}" placeholder="审核意见（拒绝时建议填写）">
      <div class="presets">
        <span class="preset" onclick="fillInput('bot_reason_{{.ID}}', '用途说明不明确，请补充具体场景')">用途不明确</span>
        <span class="preset" onclick="fillInput('bot_reason_{{.ID}}', '暂不支持该类自动化功能申请')">暂不开放</span>
      </div>
      <button name="action" value="approve" type="submit">通过申请</button>
      <button name="action" value="reject" type="submit">拒绝申请</button>
    </form>
    {{end}}
  </div>
  {{else}}<div class="empty">暂无机器人 Token 申请</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "channels"}}
  <div class="list-head"><h2>频道 Publisher Token 申请</h2><span class="hint">审核 API 创建、发帖与管理权限</span></div>
  <div id="cardList">
  {{range .ChannelPermits}}
  <div class="card" data-filter="{{.ID}} {{.Username}} {{.UID}} {{.Scopes}} {{.Reason}} {{.ReviewReason}}">
    <div>
      <div class="head">
        <span class="title">{{.Username}} · {{.UID}}</span>
        <span class="status st-{{.Status}}">{{moderationStatusText .Status}}</span>
      </div>
      <div class="meta">#{{.ID}} · scopes: {{.Scopes}} · {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body">{{.Reason}}</div>
      {{if .ReviewReason}}<div class="meta">审核意见：{{.ReviewReason}}</div>{{end}}
    </div>
    {{if eq .Status "applied"}}
    <form class="actions" method="post" action="/admins/moderation/channel/review">
      <input type="hidden" name="id" value="{{.ID}}">
      <input type="text" name="review_reason" id="chan_reason_{{.ID}}" placeholder="审核意见（拒绝时建议填写）">
      <div class="presets">
        <span class="preset" onclick="fillInput('chan_reason_{{.ID}}', '申请理由不充分，请详细说明业务')">理由不充分</span>
        <span class="preset" onclick="fillInput('chan_reason_{{.ID}}', '请先完成频道基础认证')">需先认证</span>
      </div>
      <button name="action" value="approve" type="submit">通过申请</button>
      <button name="action" value="reject" type="submit">拒绝申请</button>
    </form>
    {{end}}
  </div>
  {{else}}<div class="empty">暂无频道 Token 申请</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "tokens"}}
  <div class="list-head"><h2>🔑 可用 Token 列表</h2><span class="hint">已审核通过并已领取的 Bot / Channel Token · 可停用</span></div>
  <div id="cardList">
  {{range .ActiveTokens}}
  <div class="card" data-filter="{{.ID}} {{.Kind}} {{.Username}} {{.UID}} {{.Scopes}} {{.Reason}}">
    <div>
      <div class="head">
        <span class="status st-approved">{{if eq .Kind "bot"}}BOT{{else}}CHANNEL{{end}}</span>
        <span class="title">{{.Username}} · {{.UID}}</span>
        <span class="status st-approved">{{moderationStatusText .Status}}</span>
      </div>
      <div class="meta">#{{.ID}} · scopes: {{.Scopes}} · 创建于 {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      {{if .Reason}}<div class="body">{{.Reason}}</div>{{end}}
      <div class="meta">到期: {{if .ExpiresAt}}{{.ExpiresAt.Format "2006-01-02 15:04"}}{{else}}长期{{end}} · 最近使用: {{if .LastUsedAt}}{{.LastUsedAt.Format "2006-01-02 15:04"}}{{else}}从未{{end}}</div>
    </div>
    <form class="actions ban" method="post" action="/admins/moderation/token/revoke" onsubmit="return confirmAction('确定要立即停用该 Token 吗？停用后该服务将立即失效！');">
      <input type="hidden" name="id" value="{{.ID}}">
      <input type="hidden" name="kind" value="{{.Kind}}">
      <div class="ban-title">⚠ 停用后该 Token 立即失效</div>
      <button type="submit">停用 Token</button>
    </form>
  </div>
  {{else}}<div class="empty">暂无可用 Token</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "crashes"}}
  <div class="list-head"><h2>💥 崩溃日志</h2><span class="hint">客户端自动上报 · 按时间倒序 · 每页 15 条</span></div>
  <div id="cardList">
  {{range .CrashReports}}
  <div class="card" style="grid-template-columns: 1fr;" data-filter="{{.ID}} {{.UserID}} {{.DeviceModel}} {{.AndroidVersion}} {{.CrashLog}}">
    <div>
      <div class="head">
        <span class="title">#{{.ID}}</span>
        <span class="title">{{if .UserID}}UID {{.UserID}}{{else}}匿名{{end}}</span>
        <button type="button" class="copy-btn" onclick="copyCrashLog('crash_{{.ID}}')">[ 复制堆栈 ]</button>
      </div>
      <div class="meta">{{.DeviceModel}} · Android {{.AndroidVersion}} · {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body body-mono" id="crash_{{.ID}}">{{.CrashLog}}</div>
    </div>
  </div>
  {{else}}<div class="empty">暂无崩溃日志</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "cip"}}
  <div class="list-head"><h2>📦 CIP 小程序审核 · 上架申请</h2><span class="hint">待审核优先排序 · 审核通过后自动上架小程序商店与系统发现页</span></div>
  <div id="cardList">
  {{range .CipItems}}
  <div class="card" data-filter="{{.ID}} {{.Name}} {{.AuthorUID}} {{.AuthorName}} {{.Description}}">
    <div>
      <div class="head">
        {{if .IconURL}}<img src="{{.IconURL}}" style="width:32px;height:32px;object-fit:cover;border:1px solid #111;margin-right:4px;">{{end}}
        <span class="title">{{.Name}} (<code>{{.ID}}</code>)</span>
        <span class="status st-{{.Status}}">{{moderationStatusText .Status}}</span>
        <span class="title">v{{.Version}} · {{.AuthorName}}</span>
      </div>
      <div class="meta">
        大小: {{.FileSize}} 字节 · SHA256: <code>{{.SHA256}}</code><br>
        权限: {{range .PermList}}<span style="border:1px solid #111;padding:1px 4px;margin-right:4px;font-size:11px;">{{.}}</span>{{else}}<span style="color:var(--muted);font-size:11px;">无特殊权限</span>{{end}}
      </div>
      <div class="body">{{if .Description}}{{.Description}}{{else}}（暂无描述）{{end}}</div>
      <div class="meta">
        包文件: <a href="{{.CipFileURL}}" target="_blank" style="text-decoration:underline;font-weight:bold;">下载 {{.ID}}.cip</a>
        {{if .RejectReason}}<br><span style="color:var(--err);">驳回说明: {{.RejectReason}}</span>{{end}}
      </div>
    </div>
    <form class="actions" method="post" action="/admins/moderation/cip/review">
      <input type="hidden" name="id" value="{{.ID}}">
      <input type="text" name="reason" placeholder="审核说明或驳回理由…" value="{{.RejectReason}}">
      <div style="display:flex;gap:6px;">
        <button type="submit" name="action" value="approve" style="flex:1;background:var(--ok);">通过上架</button>
        <button type="submit" name="action" value="reject" style="flex:1;background:var(--err);">驳回</button>
      </div>
    </form>
  </div>
  {{else}}
  <div class="empty">暂无 CIP 小程序提交记录</div>
  {{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "logs"}}
  <div class="list-head"><h2>📋 审核员操作审计日志</h2><span class="hint">记录所有审核员与管理员的处置操作 · 按时间倒序 · 每页 15 条</span></div>
  <div id="cardList">
  {{range .ModeratorLogs}}
  <div class="card" data-filter="{{.ID}} {{.ModeratorName}} {{.ModeratorID}} {{.ActionType}} {{.TargetType}} {{.TargetID}} {{.ActionDetail}} {{.IP}}">
    <div>
      <div class="head">
        <span class="title">操作人: <strong>{{.ModeratorName}}</strong> (ID: {{.ModeratorID}})</span>
        <span class="status st-resolved">{{.ActionType}}</span>
        <span class="status st-pending">目标: {{.TargetType}} #{{.TargetID}}</span>
      </div>
      <div class="meta">时间: {{.CreatedAt.Format "2006-01-02 15:04:05"}} · 操作 IP: {{.IP}}</div>
      <div class="body">{{.ActionDetail}}</div>
    </div>
  </div>
  {{else}}<div class="empty">暂无审核操作日志记录</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "archived"}}
  <div class="list-head"><h2>🗄 已处理归档</h2><span class="hint">法庭 / 举报 / Bug 已处理项聚合 · 按时间倒序 · 每页 15 条</span></div>
  <div id="cardList">
  {{range .ArchivedItems}}
  <div class="card" data-filter="{{.ID}} {{.Kind}} {{.Title}} {{.Meta}} {{.Body}} {{.NoteValue}}">
    <div>
      <div class="head">
        <span class="title">{{if eq .Kind "appeal"}}COURT{{else if eq .Kind "report"}}REPORT{{else}}BUG {{end}} #{{.ID}}</span>
        <span class="status st-{{.StatusClass}}">{{.StatusText}}</span>
        <span class="title">{{.Title}}</span>
        {{if or (eq .Kind "report") (eq .Kind "appeal")}}
        <a href="/admins/moderation?section=inspector&q={{.Title}}" target="_blank" rel="noopener" style="font:700 11px var(--mono);background:var(--paper);color:var(--ink);border:1.5px solid var(--ink);padding:2px 8px;text-decoration:none;display:inline-flex;align-items:center;gap:4px;">🔍 查看个人空间/画像</a>
        {{end}}
      </div>
      <div class="meta">{{.Meta}} · {{.CreatedAt.Format "2006-01-02 15:04"}}</div>
      <div class="body {{if eq .Kind "report"}}report-render{{end}}" data-raw-report="{{.Body}}" data-target-uid="{{.Title}}">{{.Body}}</div>
      {{if .NoteValue}}<div class="meta">备注: {{.NoteValue}}</div>{{end}}
    </div>
    <div class="ops">
      <form class="actions" method="post" action="{{.ActionURL}}">
        <input type="hidden" name="id" value="{{.ID}}">
        <select name="status">
          {{range .StatusOptions}}<option value="{{.Value}}" {{if .Selected}}selected{{end}}>{{.Label}}</option>{{end}}
        </select>
        <input type="text" name="{{if eq .Kind "appeal"}}admin_note{{else if eq .Kind "report"}}result{{else}}note{{end}}" placeholder="{{.NotePlaceholder}}" value="{{.NoteValue}}">
        {{if .ShowUnban}}<label><input type="checkbox" name="unban_on_approve" value="1"> 通过时解封</label>{{end}}
        {{if eq .Kind "report"}}<label style="font-size:11px;margin-top:2px;"><input type="checkbox" name="takedown_resource" value="1"> 顺带下架/删除涉案资源</label>{{end}}
        <button type="submit">更新状态</button>
      </form>
      {{if eq .Kind "report"}}
      <form class="actions ban" method="post" action="/admins/moderation/report/ban" onsubmit="return confirmBanAction(this, '{{.Title}}');">
        <input type="hidden" name="id" value="{{.ID}}">
        <div class="ban-title">⚡ 封禁与处置</div>
        <div style="display:flex;gap:10px;flex-wrap:wrap;">
          <label><input type="checkbox" name="ban_user" value="1" checked> 封号</label>
          <label><input type="checkbox" name="ban_device" value="1"> 封设备</label>
          <label><input type="checkbox" name="takedown_resource" value="1" checked> 下架/删除资源</label>
        </div>
        <input type="number" name="duration_hours" value="0" min="0" placeholder="时长(小时,0=永久)">
        <input type="text" name="reason" placeholder="封禁原因（默认：举报处理）">
        <button type="submit">执行处置</button>
      </form>
      {{end}}
    </div>
  </div>
  {{else}}<div class="empty">暂无已处理条目</div>{{end}}
  </div>
  {{end}}

  {{if eq .ActiveSection "inspector"}}
  <div class="list-head">
    <h2>🔍 用户画像与动态审查 · 查成分</h2>
    <span class="hint">输入 UID / NCUID / 用户名 / 昵称 快速审查用户的个人主页、社交画像及历史动态</span>
  </div>

  <form method="GET" action="/admins/moderation" style="display:flex;gap:10px;margin-bottom:24px;border:var(--line) solid var(--ink);padding:6px;background:var(--paper);">
    <input type="hidden" name="section" value="inspector">
    <input type="text" name="q" value="{{.Inspector.Query}}" placeholder="输入用户公开 UID / 永久 NCUID / 用户名 / 昵称 / 账号 ID..." style="flex:1;border:none!important;padding:10px 14px;font:700 13px var(--mono);outline:none;background:transparent;color:var(--ink);">
    <button type="submit" style="background:var(--ink);color:var(--paper);border:none;padding:0 24px;font:900 13px var(--mono);cursor:pointer;">查成分</button>
  </form>

  {{if .Inspector.MatchedUsers}}
  <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:20px;padding:12px;border:1px dashed var(--ink);background:var(--soft);">
    <span style="font:800 11px var(--mono);color:var(--muted);">匹配用户 ({{len .Inspector.MatchedUsers}}):</span>
    {{range .Inspector.MatchedUsers}}
    <a href="/admins/moderation?section=inspector&user_id={{.ID}}&q={{$.Inspector.Query}}" style="padding:4px 10px;border:1.5px solid var(--ink);font:700 12px var(--mono);background:{{if and $.Inspector.TargetUser (eq $.Inspector.TargetUser.ID .ID)}}var(--ink);color:var(--paper){{else}}var(--paper);color:var(--ink){{end}};">
      {{if .DisplayName}}{{.DisplayName}} ({{.Username}}){{else}}{{.Username}}{{end}} · {{.UID}}
    </a>
    {{end}}
  </div>
  {{end}}

  {{if .Inspector.TargetUser}}
  <!-- Profile Summary Card -->
  <div class="card" style="margin-bottom:24px;grid-template-columns:1fr;background:var(--paper);border:var(--line) solid var(--ink);padding:0;overflow:hidden;">
    {{if .Inspector.TargetUser.CoverURL}}
    <div style="height:140px;background:url('{{.Inspector.TargetUser.CoverURL}}') center/cover no-repeat;border-bottom:var(--line) solid var(--ink);"></div>
    {{else}}
    <div style="height:80px;background:repeating-linear-gradient(45deg, #eee, #eee 10px, #f5f5f5 10px, #f5f5f5 20px);border-bottom:var(--line) solid var(--ink);"></div>
    {{end}}

    <div style="padding:20px;position:relative;">
      <div style="display:flex;gap:20px;align-items:flex-start;flex-wrap:wrap;">
        <div style="width:72px;height:72px;border:2px solid var(--ink);background:#eee;overflow:hidden;flex-shrink:0;margin-top:-36px;box-shadow:3px 3px 0 var(--ink);">
          {{if .Inspector.TargetUser.AvatarURL}}
          <img src="{{.Inspector.TargetUser.AvatarURL}}" style="width:100%;height:100%;object-fit:cover;">
          {{else}}
          <div style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;font:900 24px var(--mono);">{{slice .Inspector.TargetUser.Username 0 1}}</div>
          {{end}}
        </div>

        <div style="flex:1;min-width:260px;">
          <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:6px;">
            <span style="font:900 18px var(--sans);">{{if .Inspector.TargetUser.DisplayName}}{{.Inspector.TargetUser.DisplayName}}{{else}}{{.Inspector.TargetUser.Username}}{{end}}</span>
            <span style="font:700 13px var(--mono);color:var(--muted);">@{{.Inspector.TargetUser.Username}}</span>
            {{if .Inspector.TargetUser.UserTitle}}<span class="status st-pending" style="border-color:#4f46e5;color:#4f46e5;font-weight:800;">🏷️ {{.Inspector.TargetUser.UserTitle}}</span>{{end}}
            {{if .Inspector.IsBanned}}
            <span class="status st-rejected">⚡ 已封禁</span>
            {{else}}
            <span class="status st-approved">🟢 正常</span>
            {{end}}
          </div>

          <div class="meta" style="margin-bottom:8px;font-size:12px;">
            公开 UID: <strong>{{.Inspector.TargetUser.UID}}</strong> · 
            永久 NCUID: <strong>{{.Inspector.TargetUser.Ncuid}}</strong> · 
            账号 ID: <code style="font-family:var(--mono);">{{.Inspector.TargetUser.ID}}</code>
          </div>

          {{if .Inspector.TargetUser.Signature}}
          <div style="font-size:13px;padding:8px 12px;background:var(--soft);border-left:3px solid var(--ink);margin-bottom:12px;line-height:1.5;">
            “{{.Inspector.TargetUser.Signature}}”
          </div>
          {{end}}

          <!-- Metric Grid -->
          <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:10px;margin-top:12px;">
            <div style="border:1px solid var(--ink);padding:8px;text-align:center;background:var(--soft);">
              <div style="font:10px var(--mono);color:var(--muted);">信誉分 (MAX 200)</div>
              <div style="font:900 16px var(--mono);color:{{if lt .Inspector.TargetUser.ReputationScore 100}}var(--err){{else}}var(--ok){{end}};">{{.Inspector.TargetUser.ReputationScore}} 分</div>
            </div>
            <div style="border:1px solid var(--ink);padding:8px;text-align:center;background:var(--soft);">
              <div style="font:10px var(--mono);color:var(--muted);">金币余额</div>
              <div style="font:900 16px var(--mono);">💰 {{.Inspector.TargetUser.CoinBalance}}</div>
            </div>
            <div style="border:1px solid var(--ink);padding:8px;text-align:center;background:var(--soft);">
              <div style="font:10px var(--mono);color:var(--muted);">被封禁次数</div>
              <div style="font:900 16px var(--mono);color:{{if gt .Inspector.TargetUser.BanCount 0}}var(--err){{else}}var(--ink){{end}};">{{.Inspector.TargetUser.BanCount}} 次</div>
            </div>
            <div style="border:1px solid var(--ink);padding:8px;text-align:center;background:var(--soft);">
              <div style="font:10px var(--mono);color:var(--muted);">好友总数</div>
              <div style="font:900 16px var(--mono);">👥 {{.Inspector.FriendsCount}}</div>
            </div>
            <div style="border:1px solid var(--ink);padding:8px;text-align:center;background:var(--soft);">
              <div style="font:10px var(--mono);color:var(--muted);">加入群聊数</div>
              <div style="font:900 16px var(--mono);">💬 {{.Inspector.GroupsCount}}</div>
            </div>
            <div style="border:1px solid var(--ink);padding:8px;text-align:center;background:var(--soft);">
              <div style="font:10px var(--mono);color:var(--muted);">发布动态数</div>
              <div style="font:900 16px var(--mono);">📱 {{.Inspector.MomentsCount}}</div>
            </div>
          </div>

          <div class="meta" style="margin-top:12px;font-size:11px;">
            注册时间: {{.Inspector.TargetUser.CreatedAt.Format "2006-01-02 15:04:05"}} · 
            状态: {{if .Inspector.TargetUser.PresenceStatus}}{{.Inspector.TargetUser.PresenceStatus}}{{else}}离线{{end}}
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Moments Section -->
  <div class="list-head" style="margin-top:30px;">
    <h2>📱 该用户空间动态 ({{.Inspector.MomentsCount}} 条)</h2>
    <span class="hint">第 {{.Inspector.MomentPage}} / {{.Inspector.MomentTotalPages}} 页 · 每页 15 条</span>
  </div>

  <div id="cardList">
  {{range .Inspector.Moments}}
  <div class="card" style="margin-bottom:16px;">
    <div>
      <div class="head">
        <span class="title">动态 #{{.Moment.ID}}</span>
        <span class="status st-pending">👍 {{.LikesCount}} 赞</span>
        <span class="status st-open">💬 {{.CommentsCount}} 评论</span>
      </div>
      <div class="meta">发布时间: {{.Moment.Created.Format "2006-01-02 15:04:05"}}</div>
      
      {{if .Moment.Body}}
      <div class="body" style="background:#fff;border:1px solid var(--ink);font-size:13px;line-height:1.6;">{{.Moment.Body}}</div>
      {{end}}

      {{if .Moment.ImageURL}}
      <div style="margin:10px 0;">
        <a href="{{.Moment.ImageURL}}" target="_blank" rel="noopener">
          <img src="{{.Moment.ImageURL}}" style="max-height:220px;max-width:100%;border:1.5px solid var(--ink);box-shadow:2px 2px 0 var(--ink);display:block;">
        </a>
      </div>
      {{end}}

      {{if .Comments}}
      <div style="margin-top:12px;border:1px solid #ddd;padding:10px;background:var(--soft);">
        <div style="font:800 11px var(--mono);color:var(--muted);margin-bottom:6px;">💬 评论列表 ({{len .Comments}}):</div>
        {{range .Comments}}
        <div style="font-size:12px;padding:4px 0;border-bottom:1px dashed #e0e0e0;line-height:1.5;">
          <strong>{{if .FromName}}{{.FromName}}{{else}}{{.FromUID}}{{end}}</strong> ({{.FromUID}}): 
          <span>{{.Body}}</span> 
          <span style="color:var(--muted);font-size:10px;margin-left:6px;">{{.Created.Format "01-02 15:04"}}</span>
        </div>
        {{end}}
      </div>
      {{end}}
    </div>

    <div class="ops">
      <form class="actions" method="post" action="/admins/moderation/inspector/moment/delete" onsubmit="return confirm('确定要作为审核员强制删除该条空间动态吗？此操作不可逆！');">
        <input type="hidden" name="moment_id" value="{{.Moment.ID}}">
        <input type="hidden" name="user_id" value="{{$.Inspector.TargetUser.ID}}">
        <input type="hidden" name="q" value="{{$.Inspector.Query}}">
        <input type="hidden" name="m_page" value="{{$.Inspector.MomentPage}}">
        <button type="submit" style="background:var(--err);color:#fff;">🗑️ 删除此动态</button>
      </form>
    </div>
  </div>
  {{else}}
  <div class="empty">该用户暂未发布任何空间动态</div>
  {{end}}
  </div>

  <!-- Moments Pagination -->
  {{if gt .Inspector.MomentTotalPages 1}}
  <div class="pager" style="margin-top:20px;">
    {{if gt .Inspector.MomentPage 1}}
    <a class="pg" href="/admins/moderation?section=inspector&user_id={{.Inspector.TargetUser.ID}}&q={{.Inspector.Query}}&m_page={{sub .Inspector.MomentPage 1}}">← 上一页动态</a>
    {{else}}
    <span class="pg off">← 上一页动态</span>
    {{end}}
    <span class="pg-info">动态第 {{.Inspector.MomentPage}} / {{.Inspector.MomentTotalPages}} 页</span>
    {{if lt .Inspector.MomentPage .Inspector.MomentTotalPages}}
    <a class="pg" href="/admins/moderation?section=inspector&user_id={{.Inspector.TargetUser.ID}}&q={{.Inspector.Query}}&m_page={{add .Inspector.MomentPage 1}}">下一页动态 →</a>
    {{else}}
    <span class="pg off">下一页动态 →</span>
    {{end}}
  </div>
  {{end}}

  {{else}}
  {{if .Inspector.Query}}
  <div class="empty">未找到匹配 "{{.Inspector.Query}}" 的任何用户，请尝试输入完整的 UID 或用户名</div>
  {{else}}
  <div class="empty">请在上方搜索框输入用户 UID、NCUID、用户名或昵称开始查成分</div>
  {{end}}
  {{end}}
  {{end}}

  {{if ne .ActiveSection "inspector"}}
  <div class="pager">
    {{if gt .Page 1}}<a class="pg" href="/admins/moderation?section={{.ActiveSection}}&page={{sub .Page 1}}">← 上一页</a>{{else}}<span class="pg off">← 上一页</span>{{end}}
    <span class="pg-info">第 {{.Page}} / {{.TotalPages}} 页 · 共 {{.Total}} 条</span>
    {{if lt .Page .TotalPages}}<a class="pg" href="/admins/moderation?section={{.ActiveSection}}&page={{add .Page 1}}">下一页 →</a>{{else}}<span class="pg off">下一页 →</span>{{end}}
  </div>
  {{end}}
</main>
<footer class="footer">OLDCHAT / MODERATION CONSOLE</footer>


<script>
function escapeHTML(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function renderReportBody(raw, targetUid) {
  if (!raw) return '';
  var text = String(raw).trim();
  var lines = text.split('\n');
  var meta = {
    media_urls: [],
    image_urls: [],
    audio_urls: [],
    video_urls: [],
    content_lines: []
  };
  var nonMetaLines = [];
  var isMusicReport = false;
  var currentSection = '';

  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (!line) continue;

    if (line.indexOf('[音乐举报]') !== -1 || line.indexOf('[音乐广场举报]') !== -1) {
      isMusicReport = true;
      var subMatch = line.match(/(?:理由|reason)[:：]\s*([^ \n]+)/i);
      if (subMatch) meta.reason = subMatch[1];
      var idMatch = line.match(/(?:歌曲ID|item_id|id)[:：=]\s*([^ \n]+)/i);
      if (idMatch) meta.song_id = idMatch[1];
      var nameMatch = line.match(/(?:歌名|name)[:：=]\s*([^ \n]+)/i);
      if (nameMatch) meta.song_name = nameMatch[1];
      var artMatch = line.match(/(?:歌手|artist|上传者|owner)[:：=]\s*([^ \n]+)/i);
      if (artMatch) meta.song_artist = artMatch[1];
      continue;
    }

    // 1. 支持中文方括号标签语法（例如 【涉案来源】来源：群聊 (GRP-3TTO6Q) 或 【附件地址】/v1/uploads/...）
    var bMatch = line.match(/^【([^】]+)】\s*(.*)$/);
    if (bMatch) {
      var bKey = bMatch[1].trim();
      var bVal = bMatch[2].trim();
      if (bVal.indexOf('：') === 0 || bVal.indexOf(':') === 0) {
        bVal = bVal.substring(1).trim();
      }

      if (bKey === '涉案来源' || bKey === '来源') {
        meta.source = bVal;
        if (bVal.indexOf('个人空间') !== -1 || bVal.indexOf('用户主页') !== -1) {
          meta.is_user_space = true;
        }
        var grp = bVal.match(/\b(GRP-[A-Za-z0-9_-]+)\b/i);
        if (grp) meta.group_id = grp[1];
        continue;
      } else if (bKey === '群组标识' || bKey === '群标识' || bKey === '群组' || bKey === '群聊' || bKey === '群ID' || bKey === '群id') {
        if (bVal) meta.group_id = bVal;
        continue;
      } else if (bKey === '消息类型' || bKey === '类型') {
        if (bVal) meta.msg_type = bVal;
        continue;
      } else if (bKey === '消息编号' || bKey === '消息ID' || bKey === '消息id') {
        if (bVal) meta.msg_id = bVal;
        continue;
      } else if (bKey === '发送时间' || bKey === '时间') {
        if (bVal) meta.send_time = bVal;
        continue;
      } else if (bKey === '附件地址' || bKey === '附件' || bKey === '媒体地址' || bKey === '图片地址' || bKey === '资源地址' || bKey === '媒体' || bKey === '图片') {
        if (bVal) {
          if (meta.media_urls.indexOf(bVal) === -1) meta.media_urls.push(bVal);
          meta.media_url = bVal;
        }
        continue;
      } else if (bKey === '封面地址' || bKey === '封面') {
        if (bVal) meta.cover_url = bVal;
        continue;
      } else if (bKey === '用户主页举报' || bKey === '个人空间举报') {
        meta.is_user_space = true;
        meta.user_space_note = bVal || '对该用户的个人空间资料、昵称、签名或空间动态违规提请法庭审理。';
        continue;
      } else if (bKey === '举报人详细证词' || bKey === '详细证词' || bKey === '举报人证词' || bKey === '证词') {
        currentSection = 'testimony';
        if (bVal) meta.testimony = (meta.testimony ? meta.testimony + '\n' : '') + bVal;
        continue;
      } else if (bKey === '消息正文' || bKey === '正文' || bKey === '涉案内容' || bKey === '内容') {
        currentSection = 'content';
        if (bVal) meta.content_lines.push(bVal);
        continue;
      } else if (bKey === '涉案记录' || bKey === '证据与证词记录') {
        currentSection = 'record';
        continue;
      }
    }

    var colonIdx = line.indexOf(':');
    if (colonIdx === -1) colonIdx = line.indexOf('：');

    if (colonIdx !== -1) {
      var key = line.substring(0, colonIdx).trim().toLowerCase();
      var val = line.substring(colonIdx + 1).trim();
      if (key === '群id' || key === 'group_id' || key === 'groupid' || key === '群聊') {
        meta.group_id = val;
        continue;
      } else if (key === '消息类型' || key === 'msg_type' || key === 'type') {
        meta.msg_type = val;
        continue;
      } else if (key === '消息id' || key === 'msg_id' || key === 'message_id') {
        meta.msg_id = val;
        continue;
      } else if (key === '媒体' || key === 'media' || key === 'media_url' || key === 'url' || key === '图片' || key === '音频' || key === '附件地址' || key === '附件') {
        meta.media_url = val;
        if (meta.media_urls.indexOf(val) === -1) meta.media_urls.push(val);
        continue;
      } else if (key === '封面' || key === 'cover' || key === 'cover_url') {
        meta.cover_url = val;
        continue;
      } else if (key === '歌名' || key === 'song_name' || key === 'name') {
        meta.song_name = val;
        continue;
      } else if (key === '歌手' || key === 'artist' || key === '上传者') {
        meta.song_artist = val;
        continue;
      } else if (key === '歌曲id' || key === 'song_id') {
        meta.song_id = val;
        continue;
      } else if (key === '内容' || key === 'content' || key === 'body') {
        meta.content = val;
        continue;
      } else if (key === '来源' || key === '涉案来源') {
        meta.source = val;
        if (val.indexOf('个人空间') !== -1 || val.indexOf('用户主页') !== -1) {
          meta.is_user_space = true;
        }
        var grp2 = val.match(/\b(GRP-[A-Za-z0-9_-]+)\b/i);
        if (grp2) meta.group_id = grp2[1];
        continue;
      }
    }

    if (currentSection === 'testimony') {
      meta.testimony = (meta.testimony ? meta.testimony + '\n' : '') + line;
    } else if (currentSection === 'content') {
      meta.content_lines.push(line);
    } else {
      nonMetaLines.push(line);
    }
  }

  if (!meta.group_id) {
    var grpMatch = text.match(/\b(GRP-[A-Za-z0-9_-]+)\b/i) || text.match(/群(?:聊)?(?:ID|id|Id)?[:：\s(]+([A-Za-z0-9_-]+)/i);
    if (grpMatch) {
      meta.group_id = grpMatch[1].trim();
    }
  }

  if (text.indexOf('个人空间') !== -1 || text.indexOf('用户主页') !== -1) {
    meta.is_user_space = true;
  }

  // 全文本智能多媒体 URL 提取
  var urlRegex = /((?:https?:\/\/[^\s\n"']+|\/v1\/uploads\/[^\s\n"']+))/gi;
  var matchUrl;
  while ((matchUrl = urlRegex.exec(text)) !== null) {
    var u = matchUrl[1];
    var uLower = u.toLowerCase();
    if (uLower.match(/\.(jpg|jpeg|png|gif|webp|bmp)($|\?)/) || uLower.indexOf('/v1/uploads/media/') !== -1 || uLower.indexOf('/v1/uploads/images/') !== -1 || meta.msg_type === 'image') {
      if (meta.image_urls.indexOf(u) === -1) meta.image_urls.push(u);
    } else if (uLower.match(/\.(mp3|m4a|wav|aac|amr|ogg|flac)($|\?)/) || meta.msg_type === 'voice' || meta.msg_type === 'music') {
      if (meta.audio_urls.indexOf(u) === -1) meta.audio_urls.push(u);
    } else if (uLower.match(/\.(mp4|webm|mov|mkv)($|\?)/) || meta.msg_type === 'video') {
      if (meta.video_urls.indexOf(u) === -1) meta.video_urls.push(u);
    } else {
      if (meta.media_urls.indexOf(u) === -1) meta.media_urls.push(u);
    }
  }

  for (var mIdx = 0; mIdx < meta.media_urls.length; mIdx++) {
    var mu = meta.media_urls[mIdx];
    var muLower = mu.toLowerCase();
    if (muLower.match(/\.(jpg|jpeg|png|gif|webp|bmp)($|\?)/) || meta.msg_type === 'image' || muLower.indexOf('/v1/uploads/media/') !== -1) {
      if (meta.image_urls.indexOf(mu) === -1) meta.image_urls.push(mu);
    } else if (muLower.match(/\.(mp3|m4a|wav|aac|amr|ogg|flac)($|\?)/) || meta.msg_type === 'voice' || meta.msg_type === 'music') {
      if (meta.audio_urls.indexOf(mu) === -1) meta.audio_urls.push(mu);
    } else if (muLower.match(/\.(mp4|webm|mov|mkv)($|\?)/) || meta.msg_type === 'video') {
      if (meta.video_urls.indexOf(mu) === -1) meta.video_urls.push(mu);
    }
  }

  // 判定主要主诉 / 举报原因
  var mainReason = meta.reason || '';
  if (!mainReason && nonMetaLines.length > 0) {
    mainReason = nonMetaLines[0];
  }
  var extraContent = nonMetaLines.length > 1 ? nonMetaLines.slice(1).join('\n') : '';

  var contentRaw = meta.content || (meta.content_lines.length > 0 ? meta.content_lines.join('\n') : (nonMetaLines.length === 0 ? '' : (!mainReason ? nonMetaLines.join('\n') : extraContent)));
  var parsedJSON = null;
  if (contentRaw && contentRaw.charAt(0) === '{' && contentRaw.charAt(contentRaw.length - 1) === '}') {
    try {
      parsedJSON = JSON.parse(contentRaw);
    } catch(e){}
  }

  if (parsedJSON) {
    if (parsedJSON.media_url && meta.image_urls.indexOf(parsedJSON.media_url) === -1) {
      if (meta.msg_type === 'image' || parsedJSON.media_url.match(/\.(jpg|jpeg|png|gif|webp|bmp)($|\?)/i)) {
        meta.image_urls.push(parsedJSON.media_url);
      }
    }
    if (parsedJSON.image_url && meta.image_urls.indexOf(parsedJSON.image_url) === -1) meta.image_urls.push(parsedJSON.image_url);
    if (parsedJSON.audio_url && meta.audio_urls.indexOf(parsedJSON.audio_url) === -1) meta.audio_urls.push(parsedJSON.audio_url);
    if (parsedJSON.song_url && meta.audio_urls.indexOf(parsedJSON.song_url) === -1) meta.audio_urls.push(parsedJSON.song_url);
    if (parsedJSON.cover_url && !meta.cover_url) meta.cover_url = parsedJSON.cover_url;
    if (parsedJSON.title && !meta.song_name) meta.song_name = parsedJSON.title;
    if (parsedJSON.artist && !meta.song_artist) meta.song_artist = parsedJSON.artist;
  }

  // 拼接格式化 HTML
  var html = '';

  // 1. 顶部标签与上下文
  html += '<div style="margin-bottom:8px;display:flex;align-items:center;gap:6px;flex-wrap:wrap;">';
  if (mainReason) {
    html += '<span style="font-weight:800;font-family:var(--mono);background:var(--ink);color:var(--paper);padding:2px 8px;font-size:12px;">🚨 诉求: ' + escapeHTML(mainReason) + '</span>';
  }
  if (meta.source) {
    html += '<span style="font-weight:700;font-family:var(--mono);border:1px solid var(--ink);padding:2px 6px;font-size:11px;background:#f5f5f5;">' + escapeHTML(meta.source) + '</span>';
  }
  if (meta.group_id) {
    html += '<span style="font-weight:700;font-family:var(--mono);border:1px solid var(--ink);padding:2px 6px;font-size:11px;background:#fff;">群聊: ' + escapeHTML(meta.group_id) + '</span>';
  }
  if (meta.msg_type) {
    html += '<span style="font-weight:700;font-family:var(--mono);border:1px solid var(--ink);padding:2px 6px;font-size:11px;background:#fff;">类型: ' + escapeHTML(meta.msg_type) + '</span>';
  }
  if (meta.msg_id) {
    html += '<span style="font-weight:700;font-family:var(--mono);border:1px solid var(--ink);padding:2px 6px;font-size:11px;background:#fff;color:var(--muted);">MsgID: ' + escapeHTML(meta.msg_id) + '</span>';
  }
  if (meta.send_time) {
    html += '<span style="font-weight:700;font-family:var(--mono);border:1px solid var(--ink);padding:2px 6px;font-size:11px;background:#fff;color:var(--muted);">时间: ' + escapeHTML(meta.send_time) + '</span>';
  }
  if (meta.song_name || meta.song_artist || isMusicReport) {
    var sInfo = (meta.song_name || '') + (meta.song_artist ? (' · ' + meta.song_artist) : '');
    html += '<span style="font-weight:700;font-family:var(--mono);border:1px solid var(--ink);padding:2px 6px;font-size:11px;background:#fff;">🎵 ' + escapeHTML(sInfo || '音乐举报') + '</span>';
  }
  html += '</div>';

  // 2. 个人空间专属直达卡片
  if (meta.is_user_space || (targetUid && targetUid.trim())) {
    var directUid = (targetUid && targetUid.trim()) || '';
    if (meta.is_user_space) {
      html += '<div style="margin:8px 0;padding:8px 12px;background:#eef2ff;border:1.5px solid #4f46e5;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px;">';
      html += '  <div style="font-size:12px;font-weight:700;color:#3730a3;">';
      html += '    👤 涉案来源：个人空间主页 / 动态 / 资料举报' + (directUid ? (' (目标用户: ' + escapeHTML(directUid) + ')') : '');
      html += '  </div>';
      if (directUid) {
        html += '  <a href="/admins/moderation?section=inspector&q=' + encodeURIComponent(directUid) + '" target="_blank" rel="noopener" style="padding:4px 10px;background:#4f46e5;color:#fff;font:700 12px var(--mono);text-decoration:none;border-radius:2px;display:inline-flex;align-items:center;gap:4px;">';
        html += '    🔍 进入该用户个人空间与动态画像 (Inspector) →';
        html += '  </a>';
      }
      html += '</div>';
    }
  }

  // 3. 用户主页举报说明与举报人详细证词
  if (meta.user_space_note) {
    html += '<div style="background:#fff;border:1px solid var(--ink);padding:8px 10px;margin-bottom:8px;font-size:12px;color:var(--ink);">';
    html += '<b>📑 案由说明：</b>' + escapeHTML(meta.user_space_note);
    html += '</div>';
  }

  if (meta.testimony) {
    html += '<div style="background:#fffbeb;border:1.5px solid #d97706;padding:8px 10px;margin-bottom:8px;font-size:12px;color:#92400e;">';
    html += '<b style="color:#b45309;">📝 举报人详细证词：</b>';
    html += '<div style="margin-top:4px;white-space:pre-wrap;font-weight:600;line-height:1.4;">' + escapeHTML(meta.testimony) + '</div>';
    html += '</div>';
  }

  // 4. 消息正文与引用记录
  if (parsedJSON) {
    html += '<div style="background:#fff;border:1px solid var(--ink);padding:10px;margin-bottom:8px;">';
    if (parsedJSON.quote) {
      var q = parsedJSON.quote;
      var qUser = q.from_uid || q.from_ncuid || '被引用者';
      var qText = q.text || q.body || (q.msg_type ? ('[' + q.msg_type + ']') : '');
      html += '<div style="border-left:3px solid var(--ink);background:#f0f0f0;padding:6px 10px;margin-bottom:8px;font-size:12px;">';
      html += '<div style="font-weight:700;color:var(--muted);font-family:var(--mono);margin-bottom:2px;">💬 引用 ' + escapeHTML(qUser) + ':</div>';
      html += '<div style="color:#222;">' + escapeHTML(qText) + '</div>';
      html += '</div>';
    }
    var mText = parsedJSON.text !== undefined ? parsedJSON.text : (parsedJSON.body || '');
    if (mText) {
      html += '<div style="font-size:13px;font-weight:600;color:var(--ink);line-height:1.5;">' + escapeHTML(mText) + '</div>';
    }
    html += '</div>';
  } else if (contentRaw && contentRaw !== '[图片]' && contentRaw !== '[语音]' && contentRaw !== '[视频]' && contentRaw !== meta.testimony && contentRaw !== meta.user_space_note) {
    html += '<div style="background:#fff;border:1px solid var(--ink);padding:10px;margin-bottom:8px;font-size:13px;font-weight:600;color:var(--ink);">' + escapeHTML(contentRaw) + '</div>';
  }

  // 5. 涉案图片证据多图列表展示
  if (meta.image_urls.length > 0) {
    html += '<div style="margin-top:8px;margin-bottom:8px;">';
    html += '<div style="font-size:12px;font-weight:800;font-family:var(--mono);margin-bottom:6px;color:var(--err);">🖼 涉案图片证据 (' + meta.image_urls.length + ' 张，点击新窗口看大图)：</div>';
    html += '<div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-start;">';
    for (var imgI = 0; imgI < meta.image_urls.length; imgI++) {
      var imgUrl = meta.image_urls[imgI];
      html += '<div style="border:2px solid var(--ink);background:#fff;padding:4px;box-shadow:2px 2px 0px rgba(0,0,0,0.15);text-align:center;">';
      html += '  <a href="' + escapeHTML(imgUrl) + '" target="_blank" rel="noopener" title="在新窗口查看原图">';
      html += '    <img src="' + escapeHTML(imgUrl) + '" alt="涉案图片" style="max-width:280px;max-height:280px;object-fit:contain;background:#f9f9f9;display:block;margin:0 auto;" onerror="this.outerHTML=\'<div style=\\\'color:#b91c1c;padding:12px;font-size:11px;font-weight:700;\\\'>⚠️ 图片加载失败<br><span style=\\\'font-size:10px;word-break:break-all;\\\'>'+escapeHTML(this.src)+'</span></div>\'">';
      html += '  </a>';
      html += '  <div style="font-size:11px;font-family:var(--mono);color:var(--muted);margin-top:4px;word-break:break-all;max-width:280px;">';
      html += '    <a href="' + escapeHTML(imgUrl) + '" target="_blank" rel="noopener" style="color:#4f46e5;font-weight:700;text-decoration:underline;">查看原图 ↗</a>';
      html += '  </div>';
      html += '</div>';
    }
    html += '</div>';
    html += '</div>';
  }

  // 6. 封面图
  if (meta.cover_url && meta.image_urls.indexOf(meta.cover_url) === -1) {
    html += '<div style="margin-top:8px;margin-bottom:8px;display:flex;align-items:center;gap:10px;">';
    html += '<img src="' + escapeHTML(meta.cover_url) + '" alt="封面" style="width:64px;height:64px;object-fit:cover;border:1px solid var(--ink);">';
    html += '<div style="font-size:11px;font-family:var(--mono);color:var(--muted);">封面预览</div>';
    html += '</div>';
  }

  // 7. 音频在线试听
  if (meta.audio_urls.length > 0) {
    for (var aIdx = 0; aIdx < meta.audio_urls.length; aIdx++) {
      var aUrl = meta.audio_urls[aIdx];
      html += '<div style="margin-top:8px;margin-bottom:8px;border:1px solid var(--ink);padding:10px;background:#fff;">';
      html += '<div style="font-size:12px;font-weight:800;font-family:var(--mono);margin-bottom:6px;display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:6px;">';
      html += '<span>🎵 涉案音频/歌曲在线试听：</span>';
      if (meta.song_name) {
        html += '<span style="font-size:11px;color:var(--muted);">' + escapeHTML(meta.song_name) + (meta.song_artist ? (' (' + escapeHTML(meta.song_artist) + ')') : '') + '</span>';
      }
      html += '</div>';
      html += '<audio controls preload="none" src="' + escapeHTML(aUrl) + '" style="width:100%;height:36px;outline:none;"></audio>';
      html += '<div style="font-size:10px;font-family:var(--mono);color:var(--muted);margin-top:4px;word-break:break-all;">源链接: <a href="' + escapeHTML(aUrl) + '" target="_blank" rel="noopener" style="text-decoration:underline;">' + escapeHTML(aUrl) + '</a></div>';
      html += '</div>';
    }
  }

  // 8. 视频在线播放
  if (meta.video_urls.length > 0) {
    for (var vIdx = 0; vIdx < meta.video_urls.length; vIdx++) {
      var vUrl = meta.video_urls[vIdx];
      html += '<div style="margin-top:8px;margin-bottom:8px;">';
      html += '<div style="font-size:11px;font-weight:800;font-family:var(--mono);margin-bottom:4px;">🎬 涉案视频证据：</div>';
      html += '<video controls preload="metadata" src="' + escapeHTML(vUrl) + '" style="max-width:300px;max-height:220px;border:2px solid var(--ink);background:#000;display:block;"></video>';
      html += '</div>';
    }
  }

  // 9. 群聊上下文调阅按钮
  if (meta.group_id) {
    var ctxId = 'ctx_' + Math.random().toString(36).substring(2, 9);
    html += '<div style="margin-top:10px;margin-bottom:6px;">';
    html += '<button type="button" class="btn-ctx" onclick="loadChatContext(\'' + escapeHTML(meta.group_id) + '\', \'' + escapeHTML(meta.msg_id || '') + '\', \'' + ctxId + '\', this)">💬 加载该群聊天上下文 (前后各 10 条)</button>';
    html += '<div id="' + ctxId + '" class="ctx-container" style="display:none;"></div>';
    html += '</div>';
  }

  // 10. 原始报文折叠
  html += '<details style="margin-top:8px;font-size:11px;font-family:var(--mono);">';
  html += '<summary style="cursor:pointer;color:var(--muted);font-weight:700;">[ 查看/隐藏原始报文 ]</summary>';
  html += '<pre style="white-space:pre-wrap;word-break:break-all;background:#f5f5f5;border:1px solid #ccc;padding:8px;margin-top:4px;font-size:11px;line-height:1.4;">' + escapeHTML(text) + '</pre>';
  html += '</details>';

  return html;
}

window.chatContextState = window.chatContextState || {};

function renderCtxItem(msg) {
  var html = '<div class="ctx-item ' + (msg.is_target ? 'is-target' : '') + '">';
  html += '<div class="ctx-item-header">';
  html += '<span><strong class="ctx-item-sender">' + escapeHTML(msg.sender_name || '未知') + '</strong> <span style="color:var(--muted);font-size:10px;">(' + escapeHTML(msg.sender_uid || msg.sender_id || '') + ')</span></span>';
  html += '<span>' + (msg.is_target ? '<span class="ctx-item-target-badge">🎯 涉案举报消息</span> ' : '') + '<span style="font-size:10px;">' + escapeHTML(msg.created_at || '') + '</span></span>';
  html += '</div>';

  if (msg.quote) {
    var qUser = msg.quote.from_name || msg.quote.from_uid || '被引用者';
    var qText = msg.quote.text || msg.quote.body || (msg.quote.type ? ('[' + msg.quote.type + ']') : '');
    html += '<div class="ctx-item-quote">💬 引用 <b>' + escapeHTML(qUser) + '</b>: ' + escapeHTML(qText) + '</div>';
  }

  if (msg.text) {
    html += '<div class="ctx-item-body">' + escapeHTML(msg.text) + '</div>';
  }

  if (msg.media_url) {
    var lower = String(msg.media_url).toLowerCase();
    if (lower.match(/\.(jpg|jpeg|png|gif|webp|bmp)($|\?)/) || msg.msg_type === 'image') {
      html += '<div style="margin-top:4px;"><a href="' + escapeHTML(msg.media_url) + '" target="_blank" rel="noopener"><img src="' + escapeHTML(msg.thumb_url || msg.media_url) + '" style="max-width:140px;max-height:140px;object-fit:cover;border:1px solid #ccc;background:#fff;"></a></div>';
    } else if (lower.match(/\.(mp3|m4a|wav|aac|amr|ogg|flac)($|\?)/) || msg.msg_type === 'voice' || msg.msg_type === 'audio') {
      html += '<div style="margin-top:4px;"><audio controls preload="none" src="' + escapeHTML(msg.media_url) + '" style="height:30px;width:100%;max-width:260px;"></audio></div>';
    }
  }

  html += '</div>';
  return html;
}

function loadChatContext(groupId, msgId, containerId, btn) {
  var container = document.getElementById(containerId);
  if (!container) return;

  if (container.style.display !== 'none' && container.getAttribute('data-loaded') === 'true') {
    container.style.display = 'none';
    btn.textContent = '💬 查看该群聊天上下文 (展开)';
    return;
  }

  if (container.getAttribute('data-loaded') === 'true') {
    container.style.display = 'block';
    btn.textContent = '🔼 收起该群聊天上下文';
    return;
  }

  btn.disabled = true;
  var origText = btn.textContent;
  btn.textContent = '⏳ 正在加载群聊上下文...';

  var url = '/admins/moderation/chat-context?group_id=' + encodeURIComponent(groupId) + '&msg_id=' + encodeURIComponent(msgId || '') + '&limit=10';
  fetch(url)
    .then(function(res) {
      if (!res.ok) throw new Error('网络请求失败 (' + res.status + ')');
      return res.json();
    })
    .then(function(data) {
      btn.disabled = false;
      if (!data.ok) {
        alert(data.message || '加载上下文失败');
        btn.textContent = origText;
        return;
      }

      container.setAttribute('data-loaded', 'true');
      container.style.display = 'block';
      btn.textContent = '🔼 收起该群聊天上下文';

      window.chatContextState[containerId] = {
        groupId: groupId,
        targetMsgId: msgId,
        minSeq: data.min_seq,
        maxSeq: data.max_seq,
        hasMoreBefore: data.has_more_before,
        hasMoreAfter: data.has_more_after
      };

      renderContextBox(containerId, data.messages);
    })
    .catch(function(err) {
      btn.disabled = false;
      btn.textContent = origText;
      alert('加载上下文出错: ' + err.message);
    });
}

function renderContextBox(containerId, messages) {
  var container = document.getElementById(containerId);
  if (!container) return;
  var state = window.chatContextState[containerId] || {};

  var html = '<div class="ctx-head">';
  html += '<span>💬 群聊消息上下文 (群: ' + escapeHTML(state.groupId) + ')</span>';
  html += '<span style="color:var(--muted);font-size:11px;">共展示 ' + messages.length + ' 条消息</span>';
  html += '</div>';

  if (state.hasMoreBefore) {
    html += '<button type="button" class="ctx-load-more" onclick="loadMoreChatMessages(\'' + containerId + '\', \'before\', this)">⬆️ 加载更早的 10 条消息</button>';
  }

  html += '<div class="ctx-list" id="ctx_list_' + containerId + '">';
  for (var i = 0; i < messages.length; i++) {
    html += renderCtxItem(messages[i]);
  }
  html += '</div>';

  if (state.hasMoreAfter) {
    html += '<button type="button" class="ctx-load-more" onclick="loadMoreChatMessages(\'' + containerId + '\', \'after\', this)">⬇️ 加载更晚的 10 条消息</button>';
  }

  container.innerHTML = html;

  setTimeout(function() {
    var targetEl = container.querySelector('.is-target');
    if (targetEl) {
      targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, 100);
}

function loadMoreChatMessages(containerId, direction, btn) {
  var state = window.chatContextState[containerId];
  if (!state) return;

  btn.disabled = true;
  var origText = btn.textContent;
  btn.textContent = '⏳ 正在加载更多...';

  var url = '/admins/moderation/chat-context?group_id=' + encodeURIComponent(state.groupId) + '&limit=10';
  if (direction === 'before') {
    url += '&before_seq=' + state.minSeq;
  } else {
    url += '&after_seq=' + state.maxSeq;
  }

  fetch(url)
    .then(function(res) { return res.json(); })
    .then(function(data) {
      btn.disabled = false;
      btn.textContent = origText;
      if (!data.ok || !data.messages || data.messages.length === 0) {
        btn.style.display = 'none';
        return;
      }

      var listEl = document.getElementById('ctx_list_' + containerId);
      if (!listEl) return;

      var newItemsHTML = '';
      for (var i = 0; i < data.messages.length; i++) {
        newItemsHTML += renderCtxItem(data.messages[i]);
      }

      if (direction === 'before') {
        listEl.insertAdjacentHTML('afterbegin', newItemsHTML);
        state.minSeq = data.min_seq;
        state.hasMoreBefore = data.has_more_before;
        if (!data.has_more_before) btn.style.display = 'none';
      } else {
        listEl.insertAdjacentHTML('beforeend', newItemsHTML);
        state.maxSeq = data.max_seq;
        state.hasMoreAfter = data.has_more_after;
        if (!data.has_more_after) btn.style.display = 'none';
      }
    })
    .catch(function(err) {
      btn.disabled = false;
      btn.textContent = origText;
      alert('加载更多失败: ' + err.message);
    });
}

// 页面加载后自动对所有举报卡片进行富文本渲染
window.addEventListener('DOMContentLoaded', function() {
  var nodes = document.querySelectorAll('.report-render');
  for (var i = 0; i < nodes.length; i++) {
    var node = nodes[i];
    var raw = node.getAttribute('data-raw-report') || node.textContent || '';
    var targetUid = node.getAttribute('data-target-uid') || '';
    if (raw) {
      node.innerHTML = renderReportBody(raw, targetUid);
    }
  }
});

function fillInput(id, val) {
  var el = document.getElementById(id);
  if (el) { el.value = val; el.focus(); }
}

function fillAppealPreset(id, status, note, unban) {
  var sel = document.getElementById('status_' + id);
  var not = document.getElementById('note_' + id);
  var unb = document.getElementById('unban_' + id);
  if (sel) sel.value = status;
  if (not) not.value = note;
  if (unb) unb.checked = unban;
}

function fillBugPreset(id, status, note) {
  var sel = document.getElementById('bug_status_' + id);
  var not = document.getElementById('bug_note_' + id);
  if (sel) sel.value = status;
  if (not) not.value = note;
}

function setBanDuration(id, hours) {
  var dur = document.getElementById(id) || document.getElementById('duration_' + id) || document.getElementById('ban_hours_' + id);
  if (dur) dur.value = hours;
}

function onCourtVerdictChange(caseId, verdict) {
  var box = document.getElementById('ban_options_' + caseId);
  var tip = document.getElementById('verdict_tip_' + caseId);
  var btn = document.getElementById('verdict_btn_' + caseId);
  if (!box || !tip || !btn) return;
  if (verdict === 'keep') {
    box.style.display = 'none';
    tip.innerHTML = '🟢 裁定无罪：维持保留，不执行任何封禁处罚';
    tip.style.color = '#15803d';
    btn.innerText = '✅ 确认裁定无罪并结案';
    btn.style.background = '#15803d';
  } else {
    box.style.display = 'block';
    tip.innerHTML = '🔴 裁定违规：将对被举报人执行封禁处罚';
    tip.style.color = '#b91c1c';
    btn.innerText = '🚨 确认裁定违规并执行封禁';
    btn.style.background = '#b91c1c';
  }
}

function copyCrashLog(id) {
  var el = document.getElementById(id);
  if (!el) return;
  var text = el.innerText || el.textContent;
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(function() {
      alert('已复制堆栈日志');
    }).catch(function() {
      fallbackCopy(text);
    });
  } else {
    fallbackCopy(text);
  }
}

function fallbackCopy(text) {
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand('copy');
    alert('已复制堆栈日志');
  } catch (e) {
    alert('复制失败，请手动选取复制');
  }
  document.body.removeChild(ta);
}

function confirmAction(msg) {
  return window.confirm(msg);
}

function confirmBanAction(form, target) {
  var banUser = form.querySelector('input[name="ban_user"]');
  var banDevice = form.querySelector('input[name="ban_device"]');
  var takedown = form.querySelector('input[name="takedown_resource"]');
  var dur = form.querySelector('input[name="duration_hours"]');
  var dVal = dur ? dur.value : '0';
  var timeStr = (dVal === '0' || dVal === '') ? '永久' : (dVal + ' 小时');
  var actions = [];
  if (banUser && banUser.checked) actions.push('封号');
  if (banDevice && banDevice.checked) actions.push('封设备');
  if (takedown && takedown.checked) actions.push('下架/删除涉案资源');
  if (actions.length === 0) {
    alert('请至少勾选「封号」、「封设备」或「下架/删除资源」其中一项！');
    return false;
  }
  var extraNotice = (takedown && takedown.checked) ? '\n⚠️ 将同步删除涉案图片/下架音乐广场歌曲/下架资源大厅文件/撤回消息！' : '';
  return window.confirm('【确认执行处置】\n目标: ' + target + '\n操作: ' + actions.join(' + ') + ' (' + timeStr + ')' + extraNotice + '\n\n确定执行吗？');
}

(function() {
  var qInput = document.getElementById('quickFilter');
  var clearBtn = document.getElementById('clearFilter');
  var cardList = document.getElementById('cardList');
  var filterStats = document.getElementById('filterStats');
  if (!qInput || !cardList) return;

  var cards = cardList.querySelectorAll('.card');
  var total = cards.length;

  qInput.addEventListener('input', function() {
    var query = this.value.trim().toLowerCase();
    var matched = 0;
    for (var i = 0; i < cards.length; i++) {
      var card = cards[i];
      var filterData = (card.getAttribute('data-filter') || '').toLowerCase();
      if (!query || filterData.indexOf(query) !== -1) {
        card.style.display = 'grid';
        matched++;
      } else {
        card.style.display = 'none';
      }
    }
    if (filterStats) {
      if (query) {
        filterStats.innerHTML = '匹配: ' + matched + ' / ' + total + ' 条';
      } else {
        filterStats.innerHTML = '第 {{.Page}} / {{.TotalPages}} 页 · 共 {{.Total}} 条';
      }
    }
  });

  if (clearBtn) {
    clearBtn.addEventListener('click', function() {
      qInput.value = '';
      qInput.dispatchEvent(new Event('input'));
      qInput.focus();
    });
  }
})();

function showModerationToast(msg, isErr) {
  var toast = document.getElementById('modToast');
  if (!toast) {
    toast = document.createElement('div');
    toast.id = 'modToast';
    toast.style.cssText = 'position:fixed;bottom:24px;right:24px;background:#111;color:#fff;padding:12px 20px;font:700 13px var(--mono);border:2px solid var(--ink);box-shadow:4px 4px 0 var(--ink);z-index:99999;transition:opacity 0.3s,transform 0.3s;transform:translateY(20px);opacity:0;pointer-events:none;max-width:360px;word-break:break-all;';
    document.body.appendChild(toast);
  }
  toast.textContent = msg;
  toast.style.background = isErr ? 'var(--err)' : '#0f172a';
  toast.style.opacity = '1';
  toast.style.transform = 'translateY(0)';
  if (window.modToastTimer) clearTimeout(window.modToastTimer);
  window.modToastTimer = setTimeout(function() {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(20px)';
  }, 3500);
}

document.addEventListener('submit', function(e) {
  var form = e.target;
  if (!form || !form.action || form.action.indexOf('/admins/moderation/') === -1) {
    return;
  }
  if (e.defaultPrevented) return;
  e.preventDefault();

  var btn = form.querySelector('button[type="submit"]');
  var origText = btn ? btn.textContent : '提交';
  if (btn) {
    btn.disabled = true;
    btn.textContent = '⏳ 处理中...';
  }

  var params;
  try {
    params = new URLSearchParams(new FormData(form));
  } catch (err) {
    var fd = new FormData(form);
    params = new URLSearchParams();
    fd.forEach(function(val, key) {
      params.append(key, val);
    });
  }
  var card = form.closest('.card') || form.closest('.court-card');

  fetch(form.action, {
    method: 'POST',
    body: params,
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      'Accept': 'application/json'
    }
  })
  .then(function(res) {
    return res.json().catch(function() {
      return { ok: res.ok, message: res.ok ? '操作已成功' : ('请求返回: ' + res.status) };
    });
  })
  .then(function(data) {
    if (btn) {
      btn.disabled = false;
      btn.textContent = origText;
    }
    if (data.ok !== false) {
      showModerationToast('✅ ' + (data.message || '操作成功，状态已更新'), false);
      if (card) {
        card.style.transition = 'background 0.4s, opacity 0.4s';
        card.style.background = '#f8fafc';
        card.style.borderColor = '#94a3b8';
        var stBadge = card.querySelector('.head .status');
        if (stBadge) {
          stBadge.textContent = '已处理/更新';
          stBadge.className = 'status st-approved';
        }
      }
    } else {
      showModerationToast('❌ 操作失败: ' + (data.error || data.message || '未知错误'), true);
      alert('操作失败: ' + (data.error || data.message || '未知错误'));
    }
  })
  .catch(function(err) {
    if (btn) {
      btn.disabled = false;
      btn.textContent = origText;
    }
    showModerationToast('❌ 网络异常: ' + err.message, true);
    alert('网络请求异常: ' + err.message);
  });
});
</script>
</body>
</html>
`
