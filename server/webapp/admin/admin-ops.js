/**
 * OldChat Admin 2.0 - Operations & Monitoring Views (举报处理、法庭仲裁、资源与日志)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var escapeHtml = C.escapeHtml;
  var formatDate = C.formatDate;
  var api = C.api;
  var toast = C.toast;

  function renderReports() {
    api('/v1/admin/reports').then(function (res) {
      var list = (res.data && res.data.reports) || res.reports || [];
      var rows = list.map(function (r) {
        var status = r.Status || r.status || 'pending';
        var isPending = status === 'pending';
        var statusBadge = isPending ? '<span class="badge badge-warning">待处理</span>' :
          (status === 'resolved' ? '<span class="badge badge-success">已处理</span>' : '<span class="badge badge-secondary">已驳回</span>');
        return '<tr>' +
          '<td><code>' + escapeHtml(r.ReporterUID || r.reporter_uid || '匿名') + '</code></td>' +
          '<td><strong style="color:var(--danger);">' + escapeHtml(r.TargetUID || r.target_uid || '—') + '</strong></td>' +
          '<td><span class="badge badge-primary">' + escapeHtml(r.Reason || r.reason || '违规') + '</span> ' + escapeHtml(r.Description || r.description || '') + '</td>' +
          '<td>' + statusBadge + '<div style="font-size:11px;color:var(--text-muted);">' + escapeHtml(r.Result || r.result || '') + '</div></td>' +
          '<td>' + formatDate(r.CreatedAt || r.created_at) + '</td>' +
          '<td>' +
            (isPending ?
              '<div style="display:flex;gap:4px;flex-wrap:wrap;">' +
                '<button class="btn btn-sm btn-danger" onclick="AdminOps.resolveReport(\'' + (r.ID || r.id) + '\',\'ban_user\')">封号</button>' +
                '<button class="btn btn-sm btn-danger" onclick="AdminOps.resolveReport(\'' + (r.ID || r.id) + '\',\'ban_device\')">封设备</button>' +
                '<button class="btn btn-sm" onclick="AdminOps.resolveReport(\'' + (r.ID || r.id) + '\',\'dismiss\')">驳回</button>' +
              '</div>' : '已归档') +
          '</td>' +
        '</tr>';
      }).join('');

      var html = '<div class="card">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('shield', '', 18) + '用户举报工单直接处置 (共 ' + list.length + ' 条举报)</h3></div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>举报人</th><th>被举报目标 (UID)</th><th>举报理由与描述</th><th>状态/结果</th><th>举报时间</th><th>直接操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6" style="text-align:center;padding:30px;color:var(--text-muted);">暂无用户举报</td></tr>') +
        '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { toast('加载举报失败: ' + err.message, 'error'); });
  }

  function resolveReport(id, action) {
    var note = prompt('请输入处置说明/备注 (可选):', action === 'dismiss' ? '经核实无违规，予以驳回' : '违规属实，执行封禁处罚');
    if (note === null) return;
    api('/v1/admin/reports/resolve', { method: 'POST', body: { id: id, action: action, note: note } }).then(function () {
      toast('举报工单已处置完成！', 'success');
      renderReports();
    }).catch(function (e) { toast('处置失败: ' + e.message, 'error'); });
  }

  function renderCourt() {
    api('/v1/admin/court').then(function (res) {
      var cases = (res.data && res.data.cases) || res.cases || [];
      var rows = cases.map(function (c) {
        var caseId = c.ID || c.id || '';
        var title = c.Title || c.title || ('案件 #' + caseId);
        var reporter = c.ReporterName || c.reporter_name || c.ReporterUID || c.reporter_uid || '原告';
        var defendant = c.DefendantName || c.defendant_name || c.DefendantUID || c.defendant_uid || '被告';
        var banVotes = c.BanVoteCount !== undefined ? c.BanVoteCount : (c.ban_vote_count || 0);
        var keepVotes = c.KeepVoteCount !== undefined ? c.KeepVoteCount : (c.keep_vote_count || 0);
        var status = c.Status || c.status || 'open';
        var isOpen = status === 'open' || status === 'voting';
        var statusBadge = isOpen ? '<span class="badge badge-warning">审理中 (' + status + ')</span>' :
          (status === 'guilty' ? '<span class="badge badge-danger">判定有罪</span>' : '<span class="badge badge-success">' + escapeHtml(status) + '</span>');
        var createdAt = formatDate(c.CreatedAt || c.created_at);
        return '<tr>' +
          '<td><strong>' + escapeHtml(title) + '</strong><div style="font-size:11px;color:var(--text-dim);">' + escapeHtml(caseId) + '</div></td>' +
          '<td>' + escapeHtml(reporter) + ' vs <strong style="color:var(--danger);">' + escapeHtml(defendant) + '</strong></td>' +
          '<td><span class="badge badge-danger">封禁票: ' + banVotes + '</span> / <span class="badge badge-success">保留票: ' + keepVotes + '</span></td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + escapeHtml(createdAt) + '</td>' +
          '<td>' +
            (isOpen ?
              '<div style="display:flex;gap:4px;flex-wrap:wrap;">' +
                '<button class="btn btn-sm btn-danger" onclick="AdminOps.resolveCourt(\'' + caseId + '\',\'guilty\')">判有罪封禁</button>' +
                '<button class="btn btn-sm btn-success" onclick="AdminOps.resolveCourt(\'' + caseId + '\',\'innocent\')">判无罪</button>' +
                '<button class="btn btn-sm" onclick="AdminOps.resolveCourt(\'' + caseId + '\',\'dismiss\')">撤销结案</button>' +
              '</div>' : '已结案') +
          '</td>' +
        '</tr>';
      }).join('');

      var html = '<div class="card">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('scale', '', 18) + '公共法庭案件直接裁决 (共 ' + cases.length + ' 起案件)</h3></div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>案件名称 / ID</th><th>原告 vs 被告</th><th>陪审团投票统计</th><th>状态</th><th>发起时间</th><th>管理员裁决</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6" style="text-align:center;padding:30px;color:var(--text-muted);">暂无法庭案件</td></tr>') +
        '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { if (err.message !== 'UNAUTHORIZED') toast('加载法庭失败: ' + err.message, 'error'); });
  }

  function resolveCourt(caseId, action) {
    var promptMsg = action === 'guilty' ? '确定判定被告有罪并封号？请输入封禁天数与裁决理由(例如: 7 违规证据确凿):' : '请输入裁决意见/结案理由:';
    var input = prompt(promptMsg, action === 'guilty' ? '7 违规事实清楚，依法封禁' : (action === 'innocent' ? '证据不足，裁定无罪' : '管理员撤销案件'));
    if (input === null) return;
    var banHours = 24 * 7;
    var note = input;
    if (action === 'guilty') {
      var parts = input.trim().split(/\s+/);
      var days = parseInt(parts[0], 10);
      if (!isNaN(days) && days > 0) {
        banHours = days * 24;
        note = parts.slice(1).join(' ') || '违规事实清楚，依法封禁';
      }
    }
    api('/v1/admin/court/resolve', { method: 'POST', body: { case_id: caseId, action: action, note: note, ban_hours: banHours } }).then(function () {
      toast('法庭案件已完成直接仲裁！', 'success');
      renderCourt();
    }).catch(function (e) { toast('裁决失败: ' + e.message, 'error'); });
  }

  function renderResources() {
    api('/v1/admin/resources').then(function (res) {
      $('#contentBody').innerHTML = '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('database', '', 18) + '资源配额与下载限速策略</h3></div>' +
        '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px;">' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">全站资源空间配额 (GB)</label><input type="number" id="resQuotaGB" class="input" value="' + (res.quota_gb || 20) + '"></div>' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">多媒体下载限速 (MB/s)</label><input type="number" id="resMediaRate" class="input" value="' + (res.media_rate_mb || 5) + '"></div>' +
        '</div><div style="display:flex;justify-content:flex-end;margin-top:20px;"><button class="btn btn-primary" id="btnSaveResources">' + AdminIcons.get('check', '', 16) + '保存配额配置</button></div></div>';
      C.renderAllIcons();
      $('#btnSaveResources').onclick = function () {
        api('/v1/admin/resources', { method: 'POST', body: { quota_gb: parseFloat($('#resQuotaGB').value || '20'), media_rate_mb: parseFloat($('#resMediaRate').value || '5') } }).then(function () {
          toast('资源配置已保存！', 'success');
        }).catch(function (err) { toast('保存失败: ' + err.message, 'error'); });
      };
    }).catch(function (err) { if (err.message !== 'UNAUTHORIZED') toast('加载资源失败: ' + err.message, 'error'); });
  }

  function renderLogs() {
    api('/v1/admin/logs').then(function (res) {
      var logText = (res.logs || []).map(escapeHtml).join('\n');
      $('#contentBody').innerHTML = '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('terminal', '', 18) + '服务端实时日志输出</h3><button class="btn btn-sm" onclick="window.AdminApp.switchTab(\'logs\')">' + AdminIcons.get('refresh', '', 14) + '刷新日志</button></div>' +
        '<div style="background:#000;color:#00ff66;font-family:var(--font-mono);font-size:12px;padding:16px;border-radius:var(--radius-md);height:480px;overflow-y:auto;white-space:pre-wrap;line-height:1.6;">' + (logText || '暂无日志') + '</div></div>';
      C.renderAllIcons();
    }).catch(function (err) { if (err.message !== 'UNAUTHORIZED') toast('获取日志失败: ' + err.message, 'error'); });
  }

  global.AdminOps = {
    renderReports: renderReports,
    resolveReport: resolveReport,
    renderCourt: renderCourt,
    resolveCourt: resolveCourt,
    renderResources: renderResources,
    renderLogs: renderLogs
  };
})(window);
