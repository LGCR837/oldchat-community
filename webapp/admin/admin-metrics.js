/**
 * OldChat Admin 2.0 - API Monitor & Metrics (调用监视、排行与实时流水)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var escapeHtml = C.escapeHtml;
  var api = C.api;
  var toast = C.toast;

  var autoRefreshTimer = null;
  var logFilter = 'all';
  var logQuery = '';

  function renderMetrics() {
    loadOverviewAndLogs();
  }

  function loadOverviewAndLogs() {
    Promise.all([
      api('/v1/admin/metrics/overview'),
      api('/v1/admin/metrics/logs?status=' + logFilter + '&q=' + encodeURIComponent(logQuery))
    ]).then(function (results) {
      var ov = results[0] || {};
      var logs = (results[1] && results[1].logs) || [];

      var qps = (ov.qps || 0).toFixed(1);
      var total = ov.total_requests || 0;
      var avgMs = (ov.avg_latency_ms || 0).toFixed(1);
      var errRate = (ov.error_rate || 0).toFixed(2);
      var topRoutes = ov.top_routes || [];
      var topIps = ov.top_ips || [];

      // 1. KPI 统计
      var kpiHtml = '<div class="stats-grid">' +
        '<div class="stat-card"><div class="stat-icon blue">' + AdminIcons.get('activity', '', 24) + '</div><div class="stat-info"><span class="stat-label">实时 QPS</span><span class="stat-value">' + qps + ' <small style="font-size:12px;font-weight:normal;color:var(--text-muted);">req/s</small></span></div></div>' +
        '<div class="stat-card"><div class="stat-icon green">' + AdminIcons.get('database', '', 24) + '</div><div class="stat-info"><span class="stat-label">今日总请求量</span><span class="stat-value">' + total + '</span></div></div>' +
        '<div class="stat-card"><div class="stat-icon yellow">' + AdminIcons.get('sliders', '', 24) + '</div><div class="stat-info"><span class="stat-label">平均响应耗时</span><span class="stat-value">' + avgMs + ' <small style="font-size:12px;font-weight:normal;color:var(--text-muted);">ms</small></span></div></div>' +
        '<div class="stat-card"><div class="stat-icon ' + (errRate > 5 ? 'red' : 'green') + '">' + AdminIcons.get('shield', '', 24) + '</div><div class="stat-info"><span class="stat-label">请求异常率</span><span class="stat-value">' + errRate + '%</span></div></div>' +
      '</div>';

      // 2. Top 接口排行榜
      var routeRows = topRoutes.map(function (r, idx) {
        var method = r.method || r.Method || 'GET';
        var path = r.path || r.Path || '/';
        var count = r.count !== undefined ? r.count : (r.Count || 0);
        var errCount = r.error_count !== undefined ? r.error_count : (r.ErrorCount || 0);
        var avgTime = r.avg_time_ms !== undefined ? r.avg_time_ms : (r.AvgTimeMs || 0);
        var pct = (r.percentage !== undefined ? r.percentage : (r.Percentage || 0)).toFixed(1);
        var mCls = method === 'POST' ? 'badge-success' : (method === 'DELETE' ? 'badge-danger' : 'badge-primary');
        return '<tr>' +
          '<td style="font-weight:700;color:var(--text-dim);width:30px;">#' + (idx + 1) + '</td>' +
          '<td><span class="badge ' + mCls + '">' + escapeHtml(method) + '</span> <strong>' + escapeHtml(path) + '</strong></td>' +
          '<td><strong>' + count + '</strong> 次</td>' +
          '<td style="width:180px;"><div style="background:rgba(255,255,255,0.06);border-radius:4px;height:8px;overflow:hidden;"><div style="background:var(--primary);width:' + pct + '%;height:100%;"></div></div><div style="font-size:10px;color:var(--text-dim);margin-top:2px;">' + pct + '%</div></td>' +
          '<td>' + avgTime.toFixed(1) + ' ms</td>' +
          '<td>' + (errCount > 0 ? '<span class="badge badge-danger">' + errCount + ' 错</span>' : '<span style="color:var(--text-dim);">0</span>') + '</td>' +
        '</tr>';
      }).join('');

      // 3. Top IP 榜单
      var ipRows = topIps.map(function (ipItem, idx) {
        var ip = ipItem.ip || ipItem.IP || '—';
        var count = ipItem.count !== undefined ? ipItem.count : (ipItem.Count || 0);
        var lastSeen = ipItem.last_seen || ipItem.LastSeen || '—';
        return '<tr>' +
          '<td style="font-weight:700;color:var(--text-dim);width:30px;">#' + (idx + 1) + '</td>' +
          '<td><code>' + escapeHtml(ip) + '</code></td>' +
          '<td><strong>' + count + '</strong> 次</td>' +
          '<td>' + escapeHtml(lastSeen) + '</td>' +
        '</tr>';
      }).join('');

      // 4. 实时调用流水
      var logRows = logs.map(function (l) {
        var st = l.status !== undefined ? l.status : (l.Status || 200);
        var method = l.method || l.Method || 'GET';
        var path = l.path || l.Path || '/';
        var dur = l.duration_ms !== undefined ? l.duration_ms : (l.DurationMs || 0);
        var ip = l.ip || l.IP || '—';
        var ua = l.user_agent || l.UserAgent || '—';
        var timeStr = l.time || l.Time || '—';

        var stCls = st >= 500 ? 'badge-danger' : (st >= 400 ? 'badge-warning' : 'badge-success');
        var mCls = method === 'POST' ? 'badge-success' : (method === 'DELETE' ? 'badge-danger' : 'badge-primary');
        return '<tr>' +
          '<td><span style="font-family:var(--font-mono);font-size:11px;color:var(--text-muted);">' + escapeHtml(timeStr) + '</span></td>' +
          '<td><span class="badge ' + stCls + '">' + st + '</span></td>' +
          '<td><span class="badge ' + mCls + '">' + escapeHtml(method) + '</span></td>' +
          '<td><code>' + escapeHtml(path) + '</code></td>' +
          '<td>' + dur.toFixed(1) + 'ms</td>' +
          '<td><code>' + escapeHtml(ip) + '</code></td>' +
          '<td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;color:var(--text-dim);" title="' + escapeHtml(ua) + '">' + escapeHtml(ua) + '</td>' +
        '</tr>';
      }).join('');

      var isAuto = !!autoRefreshTimer;
      var html = kpiHtml +
        '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:20px;margin-bottom:20px;">' +
          '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('activity', '', 18) + '热门接口调用排行 (Top 10 Endpoints)</h3></div>' +
            '<div class="table-wrap"><table class="table"><thead><tr><th>#</th><th>接口路径</th><th>调用量</th><th>占比</th><th>平均耗时</th><th>异常</th></tr></thead><tbody>' + (routeRows || '<tr><td colspan="6" style="text-align:center;">暂无数据</td></tr>') + '</tbody></table></div>' +
          '</div>' +
          '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('users', '', 18) + '活跃客户端 IP 统计 (Top 10 IPs)</h3></div>' +
            '<div class="table-wrap"><table class="table"><thead><tr><th>#</th><th>客户端 IP</th><th>请求量</th><th>最后调用时间</th></tr></thead><tbody>' + (ipRows || '<tr><td colspan="4" style="text-align:center;">暂无活跃 IP</td></tr>') + '</tbody></table></div>' +
          '</div>' +
        '</div>' +
        '<div class="card">' +
          '<div class="card-header">' +
            '<h3 class="card-title">' + AdminIcons.get('terminal', '', 18) + '实时请求调用流水日志 (最近 ' + logs.length + ' 条)</h3>' +
            '<div style="display:flex;align-items:center;gap:10px;">' +
              '<div style="display:flex;align-items:center;gap:6px;font-size:13px;"><label class="switch"><input type="checkbox" id="chkAutoMetrics" ' + (isAuto ? 'checked' : '') + '><span class="slider"></span></label><span>3s 自动刷新</span></div>' +
              '<select id="selLogFilter" class="select" style="width:110px;padding:4px 8px;font-size:12px;"><option value="all" ' + (logFilter === 'all' ? 'selected' : '') + '>全部请求</option><option value="errors" ' + (logFilter === 'errors' ? 'selected' : '') + '>仅错误 (4xx/5xx)</option></select>' +
              '<input type="text" id="logSearchInput" class="input" style="max-width:180px;padding:4px 8px;font-size:12px;" placeholder="过滤 Path / IP" value="' + escapeHtml(logQuery) + '">' +
              '<button class="btn btn-sm" id="btnRefreshMetrics">' + AdminIcons.get('refresh', '', 14) + '刷新</button>' +
            '</div>' +
          '</div>' +
          '<div class="table-wrap" style="max-height:480px;overflow-y:auto;"><table class="table"><thead><tr><th>时间</th><th>状态</th><th>方法</th><th>请求路由</th><th>耗时</th><th>客户端 IP</th><th>User-Agent</th></tr></thead><tbody>' + (logRows || '<tr><td colspan="7" style="text-align:center;padding:30px;color:var(--text-muted);">暂无调用流水</td></tr>') + '</tbody></table></div>' +
        '</div>';

      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
      bindMetricsEvents();
    }).catch(function (err) {
      if (err.message !== 'UNAUTHORIZED') toast('加载监控数据失败: ' + err.message, 'error');
    });
  }

  function bindMetricsEvents() {
    $('#btnRefreshMetrics').onclick = function () { loadOverviewAndLogs(); };
    $('#selLogFilter').onchange = function () {
      logFilter = this.value;
      loadOverviewAndLogs();
    };
    var input = $('#logSearchInput');
    input.onkeydown = function (e) {
      if (e.keyCode === 13) {
        logQuery = input.value.trim();
        loadOverviewAndLogs();
      }
    };
    $('#chkAutoMetrics').onchange = function () {
      if (this.checked) {
        if (!autoRefreshTimer) {
          autoRefreshTimer = setInterval(loadOverviewAndLogs, 3000);
          toast('已开启 3 秒实时自动轮询监视', 'info');
        }
      } else {
        if (autoRefreshTimer) {
          clearInterval(autoRefreshTimer);
          autoRefreshTimer = null;
          toast('已停止自动刷新', 'info');
        }
      }
    };
  }

  function stopAutoRefresh() {
    if (autoRefreshTimer) {
      clearInterval(autoRefreshTimer);
      autoRefreshTimer = null;
    }
  }

  global.AdminMetrics = {
    renderMetrics: renderMetrics,
    stopAutoRefresh: stopAutoRefresh
  };
})(window);
