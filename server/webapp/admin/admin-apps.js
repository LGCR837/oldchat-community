/**
 * OldChat Admin 2.0 - CIP Apps Views (CIP小程序管理)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var escapeHtml = C.escapeHtml;
  var formatDate = C.formatDate;
  var api = C.api;
  var toast = C.toast;

  function renderApps() {
    api('/v1/admin/lua-apps').then(function (res) {
      var apps = res.apps || [];
      var rows = apps.map(function (app) {
        var isEnabled = app.enabled !== false;
        return '<tr>' +
          '<td><img src="' + escapeHtml(app.icon || '/app-assets/cip-default.png') + '" style="width:36px;height:36px;border-radius:6px;object-fit:cover;background:#eee;"></td>' +
          '<td><strong>' + escapeHtml(app.name || app.id) + '</strong><div style="font-size:11px;color:var(--text-dim);">' + escapeHtml(app.id) + ' (v' + escapeHtml(app.version || '1.0') + ')</div></td>' +
          '<td>' + escapeHtml(app.author || '官方') + '</td>' +
          '<td>' + escapeHtml(app.description || '—') + '</td>' +
          '<td>' + (isEnabled ? '<span class="badge badge-success">已启用</span>' : '<span class="badge badge-danger">已禁用</span>') + '</td>' +
          '<td>' +
            '<div style="display:flex;gap:4px;">' +
              '<button class="btn btn-sm ' + (isEnabled ? 'btn-danger' : 'btn-success') + '" onclick="AdminApps.toggleApp(\'' + app.id + '\',' + !isEnabled + ')">' + (isEnabled ? '禁用' : '启用') + '</button>' +
              '<button class="btn btn-sm btn-danger" onclick="AdminApps.deleteApp(\'' + app.id + '\')">删除</button>' +
            '</div>' +
          '</td>' +
        '</tr>';
      }).join('');

      var html = '<div class="card">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('lock', '', 18) + 'CIP 小程序生态管理 (共 ' + apps.length + ' 款应用)</h3></div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>图标</th><th>小程序名称 / ID</th><th>开发者</th><th>功能介绍</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6" style="text-align:center;padding:30px;color:var(--text-muted);">暂无已上传的小程序</td></tr>') +
        '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { toast('加载小程序失败: ' + err.message, 'error'); });
  }

  function toggleApp(appId, enable) {
    api('/v1/admin/lua-apps/toggle', { method: 'POST', body: { id: appId, enabled: enable } }).then(function () {
      toast('小程序状态已切换', 'success');
      renderApps();
    }).catch(function (e) { toast('操作失败: ' + e.message, 'error'); });
  }

  function deleteApp(appId) {
    if (!confirm('确认彻底下架并删除此 CIP 小程序？')) return;
    api('/v1/admin/lua-apps/delete', { method: 'POST', body: { id: appId } }).then(function () {
      toast('小程序已删除', 'success');
      renderApps();
    }).catch(function (e) { toast('删除失败: ' + e.message, 'error'); });
  }

  global.AdminApps = {
    renderApps: renderApps,
    toggleApp: toggleApp,
    deleteApp: deleteApp
  };
})(window);
