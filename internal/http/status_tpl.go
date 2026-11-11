package httpapi

import "html/template"

type statusPageData struct {
	UptimeStr   string
	Goroutines  int
	HeapAllocMB float64
	HeapSysMB   float64
	NumGC       uint32
	TotalReqs   int64
	TotalErrors int64
	AvgTimeMs   float64
	QPS         float64
	WSCount     int
}

const statusHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>服务性能与运行状态 — Oldchat</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, "Segoe UI", "DejaVu Sans", "Noto Sans SC", monospace, sans-serif;
      font-size: 13px;
      line-height: 1.5;
      color: #111;
      background: #f4f6f8;
      padding: 16px;
    }
    a { color: #004488; text-decoration: none; }
    a:hover { text-decoration: underline; }
    .container { max-width: 860px; margin: 0 auto; }
    
    .top-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 10px;
      margin-bottom: 14px;
      border-bottom: 2px solid #2b5b84;
    }
    .top-bar h1 { font-size: 18px; font-weight: bold; color: #111; }
    .top-bar .nav a { margin-left: 12px; font-weight: 600; }

    .grid-4 {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
      gap: 10px;
      margin-bottom: 14px;
    }
    .card {
      background: #fff;
      border: 1px solid #ced4da;
      padding: 12px 14px;
    }
    .card-label { font-size: 11px; color: #6c757d; font-weight: bold; text-transform: uppercase; margin-bottom: 4px; }
    .card-val { font-size: 20px; font-weight: bold; color: #212529; font-family: "DejaVu Sans Mono", monospace; }
    .card-sub { font-size: 11px; color: #868e96; margin-top: 2px; }

    .badge-ok { color: #0a7a0a; font-weight: bold; }

    .section-title {
      font-size: 14px;
      font-weight: bold;
      margin: 18px 0 8px;
      color: #212529;
      border-bottom: 1px solid #d0d4dc;
      padding-bottom: 4px;
    }

    table.data-table {
      width: 100%;
      border-collapse: collapse;
      background: #fff;
      border: 1px solid #dee2e6;
      font-size: 12px;
      font-family: "DejaVu Sans Mono", Consolas, monospace;
      margin-bottom: 14px;
    }
    table.data-table th, table.data-table td {
      border: 1px solid #dee2e6;
      padding: 6px 10px;
      text-align: left;
    }
    table.data-table th { background: #e9ecef; font-weight: bold; }
    .tag-method { display: inline-block; padding: 1px 4px; font-weight: bold; font-size: 10px; background: #e7f1ff; color: #0d6efd; border: 1px solid #b6d4fe; }
    .tag-post { background: #e6f4ea; color: #137333; border-color: #ceead6; }

    .footer { margin-top: 20px; font-size: 11px; color: #868e96; text-align: center; }
  </style>
</head>
<body>
<div class="container">
  <div class="top-bar">
    <h1>Oldchat 服务性能与运行状态</h1>
    <div class="nav">
      <a href="/">官网主页</a>
      <a href="/update/">下载中心</a>
    </div>
  </div>

  <div class="grid-4">
    <div class="card">
      <div class="card-label">运行健康状态</div>
      <div class="card-val badge-ok" id="val-status">🟢 正常</div>
      <div class="card-sub" id="val-uptime">运行时间：{{.UptimeStr}}</div>
    </div>
    <div class="card">
      <div class="card-label">实时吞吐 (QPS)</div>
      <div class="card-val" id="val-qps">{{printf "%.1f" .QPS}} <span style="font-size:12px;font-weight:normal;">req/s</span></div>
      <div class="card-sub" id="val-avg-time">平均响应：{{printf "%.1f" .AvgTimeMs}} ms</div>
    </div>
    <div class="card">
      <div class="card-label">累计请求总量</div>
      <div class="card-val" id="val-total-reqs">{{.TotalReqs}}</div>
      <div class="card-sub" id="val-err-count">异常拦截：{{.TotalErrors}} 次</div>
    </div>
    <div class="card">
      <div class="card-label">Go 运行时内存 / 协程</div>
      <div class="card-val" id="val-mem">{{printf "%.1f" .HeapAllocMB}} <span style="font-size:12px;font-weight:normal;">MB</span></div>
      <div class="card-sub" id="val-goroutines">协程：{{.Goroutines}} ｜ GC：{{.NumGC}} 次</div>
    </div>
  </div>

  <div class="section-title">接口吞吐与延迟排行 (Top Routes)</div>
  <table class="data-table">
    <thead>
      <tr>
        <th style="width:70px;">方法</th>
        <th>接口路径</th>
        <th style="width:90px;text-align:right;">调用量</th>
        <th style="width:90px;text-align:right;">平均延迟</th>
        <th style="width:70px;text-align:right;">异常</th>
      </tr>
    </thead>
    <tbody id="routes-tbody">
      <tr><td colspan="5" style="text-align:center;color:#888;">加载中...</td></tr>
    </tbody>
  </table>
</div>

<script>
  function renderStatus() {
    fetch('/v1/system/status')
      .then(function(r) { return r.json(); })
      .then(function(data) {
        if (!data) return;
        document.getElementById('val-uptime').textContent = '运行时间：' + (data.uptime_str || '--');
        document.getElementById('val-qps').innerHTML = (data.qps || 0).toFixed(1) + ' <span style="font-size:12px;font-weight:normal;">req/s</span>';
        document.getElementById('val-avg-time').textContent = '平均响应：' + (data.avg_time_ms || 0).toFixed(1) + ' ms';
        document.getElementById('val-total-reqs').textContent = data.total_reqs || 0;
        document.getElementById('val-err-count').textContent = '异常拦截：' + (data.total_errors || 0) + ' 次';
        document.getElementById('val-mem').innerHTML = (data.heap_alloc_mb || 0).toFixed(1) + ' <span style="font-size:12px;font-weight:normal;">MB</span>';
        document.getElementById('val-goroutines').textContent = '协程：' + (data.goroutines || 0) + ' ｜ GC：' + (data.num_gc || 0) + ' 次';

        // 渲染路由表
        if (data.routes && data.routes.length > 0) {
          var html = '';
          for (var i = 0; i < data.routes.length && i < 15; i++) {
            var item = data.routes[i];
            var mClass = item.method === 'POST' ? 'tag-method tag-post' : 'tag-method';
            html += '<tr>' +
              '<td><span class="' + mClass + '">' + item.method + '</span></td>' +
              '<td>' + item.path + '</td>' +
              '<td style="text-align:right;">' + item.count + '</td>' +
              '<td style="text-align:right;">' + item.avg_time_ms.toFixed(1) + ' ms</td>' +
              '<td style="text-align:right;' + (item.error_count > 0 ? 'color:#c00;font-weight:bold;' : '') + '">' + item.error_count + '</td>' +
              '</tr>';
          }
          document.getElementById('routes-tbody').innerHTML = html;
        }
      })
      .catch(function(e) {});
  }

  renderStatus();
  setInterval(renderStatus, 3000);
</script>
</body>
</html>
`

var statusTpl = template.Must(template.New("status").Parse(statusHTML))
