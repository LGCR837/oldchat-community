/**
 * OldChat Admin 2.0 - Main Controller (主控制器与交互路由)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var V = global.AdminViews;
  var S = global.AdminSocial || {};
  var Mkt = global.AdminMarket || {};
  var Apps = global.AdminApps || {};
  var Set = global.AdminSettings || {};
  var Ops = global.AdminOps || {};
  var M = global.AdminMetrics || {};
  var R = global.AdminRouteSwitch || {};
  var $ = C.$;
  var $$ = C.$$;
  var api = C.api;
  var toast = C.toast;

  function switchTab(tab) {
    C.state.currentTab = tab;
    $$('.nav-item').forEach(function (el) {
      el.classList.toggle('active', el.getAttribute('data-tab') === tab);
    });
    var titles = {
      'dashboard': '概览仪表盘',
      'users': '用户与设备全方位操控',
      'broadcast': '全服广播与通知发布',
      'appeals': '用户封禁申诉审批',
      'bugs': 'Bug 反馈与建议处理',
      'groups': '群组治理与安全',
      'market': '称号商城与表情广场',
      'apps': 'CIP 小程序生态治理',
      'metrics': '接口监控与调用排行',
      'routes': '接口路由开关与熔断管控',
      'court': '公共法庭案件审查',
      'settings': '系统全站参数热调优',
      'resources': '资源配额与限速策略',
      'logs': '服务端实时运行日志'
    };
    var titleEl = $('#pageTitle');
    if (titleEl) {
      titleEl.textContent = titles[tab] || '管理控制台';
    }
    if (global.AdminMetrics && tab !== 'metrics') {
      global.AdminMetrics.stopAutoRefresh();
    }
    loadTabData(tab);
  }

  function loadTabData(tab) {
    var body = $('#contentBody');
    if (!body) return;
    body.innerHTML = '<div style="padding:40px;text-align:center;color:var(--text-muted);">' + AdminIcons.get('refresh', 'rotating', 24) + '<div style="margin-top:12px;">数据加载中...</div></div>';
    C.renderAllIcons();

    if (tab === 'dashboard') V.renderDashboard();
    else if (tab === 'users') V.renderUsers(1, '');
    else if (tab === 'broadcast' && S.renderBroadcast) S.renderBroadcast();
    else if (tab === 'appeals' && S.renderAppeals) S.renderAppeals();
    else if (tab === 'bugs' && S.renderBugs) S.renderBugs();
    else if (tab === 'groups') V.renderGroups();
    else if (tab === 'market' && Mkt.renderMarket) Mkt.renderMarket();
    else if (tab === 'apps' && Apps.renderApps) Apps.renderApps();
    else if (tab === 'metrics' && M.renderMetrics) M.renderMetrics();
    else if (tab === 'routes' && R.renderRouteSwitch) R.renderRouteSwitch();
    else if (tab === 'reports' && Ops.renderReports) Ops.renderReports();
    else if (tab === 'court' && Ops.renderCourt) Ops.renderCourt();
    else if (tab === 'settings' && Set.renderSettings) Set.renderSettings();
    else if (tab === 'resources' && Ops.renderResources) Ops.renderResources();
    else if (tab === 'logs' && Ops.renderLogs) Ops.renderLogs();
  }

  function banUserPrompt(uid, name) {
    var reason = prompt('确定要封禁用户【' + name + '】(' + uid + ') 吗？\n请输入封禁原因说明：', '违规行为');
    if (reason === null) return;
    api('/v1/admin/users/ban', { method: 'POST', body: { uid: uid, reason: reason } }).then(function () {
      toast('用户已成功封禁', 'success');
      loadTabData(C.state.currentTab);
    }).catch(function (err) { toast('封禁失败: ' + err.message, 'error'); });
  }

  function unbanUser(uid) {
    api('/v1/admin/users/unban', { method: 'POST', body: { uid: uid } }).then(function () {
      toast('用户已成功解封', 'success');
      loadTabData(C.state.currentTab);
    }).catch(function (err) { toast('解封失败: ' + err.message, 'error'); });
  }

  function dismissGroupPrompt(gid, name) {
    if (!confirm('确定要强制解散群组【' + name + '】吗？此操作不可逆！')) return;
    api('/v1/admin/groups/dismiss', { method: 'POST', body: { group_id: gid } }).then(function () {
      toast('群组已成功解散', 'success');
      loadTabData(C.state.currentTab);
    }).catch(function (err) { toast('解散失败: ' + err.message, 'error'); });
  }

  function init() {
    C.applyTheme(C.state.theme);
    var logoEl = $('#appLogo');
    if (logoEl) logoEl.innerHTML = AdminIcons.get('logo', '', 20);

    $$('.nav-item').forEach(function (el) {
      el.onclick = function () { switchTab(el.getAttribute('data-tab')); };
    });

    var themeBtn = $('#btnThemeToggle');
    if (themeBtn) {
      themeBtn.onclick = function () {
        C.applyTheme(C.state.theme === 'dark' ? 'light' : 'dark');
      };
    }
    var refreshBtn = $('#btnGlobalRefresh');
    if (refreshBtn) {
      refreshBtn.onclick = function () { loadTabData(C.state.currentTab); };
    }
    var logoutBtn = $('#btnLogout');
    if (logoutBtn) {
      logoutBtn.onclick = function () {
        api('/v1/admin/logout', { method: 'POST' }).finally(function () {
          window.location.href = '/admins/login';
        });
      };
    }

    C.renderAllIcons();
    switchTab('dashboard');
  }

  global.AdminApp = {
    switchTab: switchTab,
    banUserPrompt: banUserPrompt,
    unbanUser: unbanUser,
    dismissGroupPrompt: dismissGroupPrompt
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);
