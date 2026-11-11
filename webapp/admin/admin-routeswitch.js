/**
 * OldChat Admin 2.0 - Complete Route Switcher (带 v2 支持标记与安全可关性指引)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var escapeHtml = C.escapeHtml;
  var api = C.api;
  var toast = C.toast;

  var currentFilter = 'all'; // 'all', 'v2', 'safe_v1', 'critical', 'optional'
  var currentSearchQuery = '';
  var cachedRoutes = [];
  var cachedCustomRoutes = [];

  function renderRouteSwitch() {
    api('/v1/admin/routes').then(function (res) {
      cachedRoutes = res.routes || [];
      cachedCustomRoutes = res.custom_routes || [];
      renderFilteredView();
    }).catch(function (err) {
      if (err.message !== 'UNAUTHORIZED') toast('加载路由清单失败: ' + err.message, 'error');
    });
  }

  function renderFilteredView() {
    var filtered = cachedRoutes.filter(function (r) {
      if (currentFilter === 'v2' && r.version !== 'v2') return false;
      if (currentFilter === 'safe_v1' && !(r.version === 'v1' && r.safety_level === 'safe_to_disable')) return false;
      if (currentFilter === 'critical' && r.safety_level !== 'critical') return false;
      if (currentFilter === 'optional' && r.safety_level !== 'optional') return false;

      if (currentSearchQuery) {
        var q = currentSearchQuery.toLowerCase();
        var match = (r.name && r.name.toLowerCase().indexOf(q) !== -1) ||
                    (r.path && r.path.toLowerCase().indexOf(q) !== -1) ||
                    (r.category && r.category.toLowerCase().indexOf(q) !== -1) ||
                    (r.description && r.description.toLowerCase().indexOf(q) !== -1);
        if (!match) return false;
      }
      return true;
    });

    var totalCount = cachedRoutes.length;
    var v2Count = cachedRoutes.filter(function (r) { return r.version === 'v2'; }).length;
    var safeV1Count = cachedRoutes.filter(function (r) { return r.version === 'v1' && r.safety_level === 'safe_to_disable'; }).length;
    var criticalCount = cachedRoutes.filter(function (r) { return r.safety_level === 'critical'; }).length;
    var disabledCount = cachedRoutes.filter(function (r) { return !r.enabled; }).length + cachedCustomRoutes.length;

    var categories = {};
    filtered.forEach(function (r) {
      var catKey = (r.version === 'v2' ? '【v2 新架构】' : '【v1 接口】') + r.category;
      if (!categories[catKey]) categories[catKey] = [];
      categories[catKey].push(r);
    });

    var groupsHtml = Object.keys(categories).map(function (catKey) {
      var items = categories[catKey];
      var itemsHtml = items.map(function (item) {
        var isV2 = item.version === 'v2';
        var verBadge = '<span class="badge ' + (isV2 ? 'badge-primary' : 'badge-warning') + '" style="font-weight:700;font-size:11px;">' + (isV2 ? 'v2' : 'v1') + '</span>';
        var mCls = item.method === 'POST' ? 'badge-success' : (item.method === 'ALL' ? 'badge-warning' : 'badge-primary');

        // 安全与 v2 接入标签
        var safetyTag = '';
        if (item.safety_level === 'safe_to_disable') {
          safetyTag = '<span class="badge badge-success" style="font-size:11px;">✅ 可安全关闭 (已有v2)</span>';
        } else if (item.safety_level === 'critical') {
          safetyTag = '<span class="badge badge-danger" style="font-size:11px;">🔴 核心通道 (慎关)</span>';
        } else {
          safetyTag = '<span class="badge badge-warning" style="font-size:11px;">⚡ 业务级可选</span>';
        }

        return '<div style="background:var(--bg-main);padding:14px;border-radius:var(--radius-md);border:1px solid var(--border-color);display:flex;align-items:center;justify-content:space-between;gap:12px;">' +
          '<div style="flex:1;min-width:0;">' +
            '<div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">' +
              verBadge +
              '<span class="badge ' + mCls + '">' + escapeHtml(item.method) + '</span>' +
              '<strong style="font-size:14px;">' + escapeHtml(item.name) + '</strong>' +
              safetyTag +
              (!item.enabled ? ' <span class="badge badge-danger">已关闭</span>' : '') +
            '</div>' +
            '<div style="font-family:var(--font-mono);font-size:12px;color:var(--text-muted);margin-top:4px;">' + escapeHtml(item.path) + '</div>' +
            '<div style="font-size:12px;color:var(--text-dim);margin-top:2px;">' + escapeHtml(item.description || '') + '</div>' +
          '</div>' +
          '<label class="switch">' +
            '<input type="checkbox" id="sw_' + escapeHtml(item.key) + '" ' + (item.enabled ? 'checked' : '') + ' onchange="AdminRouteSwitch.toggleRoute(\'' + escapeHtml(item.key) + '\', this.checked)">' +
            '<span class="slider"></span>' +
          '</label>' +
        '</div>';
      }).join('');

      return '<div class="card" style="margin-bottom:18px;">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('sliders', '', 18) + escapeHtml(catKey) + ' <small style="font-size:12px;font-weight:normal;color:var(--text-muted);">(' + items.length + ' 个接口)</small></h3></div>' +
        '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:12px;">' + itemsHtml + '</div>' +
      '</div>';
    }).join('');

    var customRows = cachedCustomRoutes.map(function (k) {
      return '<tr>' +
        '<td><code>' + escapeHtml(k) + '</code></td>' +
        '<td><span class="badge badge-danger">已熔断关闭</span></td>' +
        '<td><button class="btn btn-sm btn-success" onclick="AdminRouteSwitch.removeCustomRule(\'' + escapeHtml(k) + '\')">' + AdminIcons.get('unlock', '', 14) + '解除封锁</button></td>' +
      '</tr>';
    }).join('');

    var filterBar = '<div class="card" style="margin-bottom:18px;">' +
      '<div style="display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;">' +
        '<div style="display:flex;align-items:center;flex-wrap:wrap;gap:8px;">' +
          '<button class="btn btn-sm ' + (currentFilter === 'all' ? 'btn-primary' : '') + '" onclick="AdminRouteSwitch.setFilter(\'all\')">全部接口 (' + totalCount + ')</button>' +
          '<button class="btn btn-sm ' + (currentFilter === 'v2' ? 'btn-primary' : '') + '" onclick="AdminRouteSwitch.setFilter(\'v2\')">🌟 v2 架构 (' + v2Count + ')</button>' +
          '<button class="btn btn-sm ' + (currentFilter === 'safe_v1' ? 'btn-success' : '') + '" onclick="AdminRouteSwitch.setFilter(\'safe_v1\')">✅ 可安全关闭的 v1 (' + safeV1Count + ')</button>' +
          '<button class="btn btn-sm ' + (currentFilter === 'critical' ? 'btn-danger' : '') + '" onclick="AdminRouteSwitch.setFilter(\'critical\')">🔴 核心通道/慎关 (' + criticalCount + ')</button>' +
        '</div>' +
        '<div style="display:flex;align-items:center;gap:10px;">' +
          (disabledCount > 0 ? '<span class="badge badge-danger" style="font-size:12px;padding:4px 8px;">⚠️ ' + disabledCount + ' 个关闭</span>' : '<span class="badge badge-success" style="font-size:12px;padding:4px 8px;">✅ 全开放</span>') +
          '<input type="text" id="routeSearchInput" class="input" style="max-width:200px;padding:6px 10px;font-size:13px;" placeholder="搜索接口名 / Path..." value="' + escapeHtml(currentSearchQuery) + '">' +
        '</div>' +
      '</div>' +
    '</div>';

    var customCard = '<div class="card">' +
      '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('shield', '', 18) + '自定义任意路由路径精准熔断</h3></div>' +
      '<div style="display:flex;flex-wrap:wrap;gap:10px;margin-bottom:16px;">' +
        '<select id="customRouteMethod" class="select" style="width:110px;"><option value="ALL">ALL (全部)</option><option value="POST">POST</option><option value="GET">GET</option><option value="DELETE">DELETE</option></select>' +
        '<input type="text" id="customRoutePath" class="input" style="flex:1;min-width:240px;" placeholder="输入要关闭的路径或前缀，例如 /v2/special">' +
        '<button class="btn btn-danger" id="btnAddCustomRoute">' + AdminIcons.get('lock', '', 14) + '一键关闭此接口</button>' +
      '</div>' +
      '<div class="table-wrap"><table class="table"><thead><tr><th>已封锁路由规则</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
      (customRows || '<tr><td colspan="3" style="text-align:center;color:var(--text-muted);padding:20px;">暂无自定义封锁规则</td></tr>') +
      '</tbody></table></div>' +
    '</div>';

    $('#contentBody').innerHTML = filterBar + (groupsHtml || '<div class="card" style="text-align:center;padding:40px;color:var(--text-muted);">未找到符合条件的接口</div>') + customCard;
    C.renderAllIcons();
    bindEvents();
  }

  function bindEvents() {
    var input = $('#routeSearchInput');
    if (input) {
      input.oninput = function () {
        currentSearchQuery = this.value.trim();
        renderFilteredView();
      };
    }
    var addBtn = $('#btnAddCustomRoute');
    if (addBtn) {
      addBtn.onclick = function () {
        var path = ($('#customRoutePath').value || '').trim();
        var method = $('#customRouteMethod').value;
        if (!path) { toast('请输入要封锁的路径', 'error'); return; }
        if (path.indexOf('/admin') !== -1) { toast('管理后台接口受保护，不可关闭', 'error'); return; }
        api('/v1/admin/routes/toggle', { method: 'POST', body: { key: method + ' ' + path, enabled: false } }).then(function () {
          toast('已成功关闭路由: ' + method + ' ' + path, 'warning');
          renderRouteSwitch();
        }).catch(function (err) { toast('关闭失败: ' + err.message, 'error'); });
      };
    }
  }

  function toggleRoute(key, enabled) {
    api('/v1/admin/routes/toggle', { method: 'POST', body: { key: key, enabled: enabled } }).then(function () {
      toast('接口状态已更新：' + (enabled ? '已开启' : '已关闭熔断'), enabled ? 'success' : 'warning');
      cachedRoutes.forEach(function (r) {
        if (r.key === key) r.enabled = enabled;
      });
      renderFilteredView();
    }).catch(function (err) {
      toast('操作失败: ' + err.message, 'error');
      renderRouteSwitch();
    });
  }

  function removeCustomRule(key) {
    api('/v1/admin/routes/toggle', { method: 'POST', body: { key: key, enabled: true } }).then(function () {
      toast('已解除封锁并恢复开启: ' + key, 'success');
      renderRouteSwitch();
    }).catch(function (err) { toast('解除失败: ' + err.message, 'error'); });
  }

  function setFilter(f) {
    currentFilter = f;
    renderFilteredView();
  }

  global.AdminRouteSwitch = {
    renderRouteSwitch: renderRouteSwitch,
    toggleRoute: toggleRoute,
    removeCustomRule: removeCustomRule,
    setFilter: setFilter
  };
})(window);
