/**
 * OldChat Admin 2.0 - Core Views (仪表盘、用户全方位操控、群组治理)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var escapeHtml = C.escapeHtml;
  var formatDate = C.formatDate;
  var api = C.api;
  var toast = C.toast;
  var showModal = C.showModal;
  var hideModal = C.hideModal;

  function renderDashboard() {
    api('/v1/admin/stats').then(function (stats) {
      C.state.stats = stats;
      var onlineText = (stats.online_users || 0) + ' <span style="font-size:13px;font-weight:normal;color:var(--text-muted);">(' + (stats.online_connections || stats.online_users || 0) + ' 连)</span>';
      var html = '<div class="stats-grid">' +
        '<div class="stat-card"><div class="stat-icon blue">' + AdminIcons.get('users', '', 24) + '</div><div class="stat-info"><span class="stat-label">总注册用户</span><span class="stat-value">' + (stats.total_users || 0) + '</span></div></div>' +
        '<div class="stat-card"><div class="stat-icon green">' + AdminIcons.get('activity', '', 24) + '</div><div class="stat-info"><span class="stat-label">实时在线 (用户 / 连接)</span><span class="stat-value">' + onlineText + '</span></div></div>' +
        '<div class="stat-card"><div class="stat-icon yellow">' + AdminIcons.get('shield', '', 24) + '</div><div class="stat-info"><span class="stat-label">待处理举报与申诉</span><span class="stat-value">' + (stats.pending_reports || 0) + '</span></div></div>' +
        '<div class="stat-card"><div class="stat-icon red">' + AdminIcons.get('lock', '', 24) + '</div><div class="stat-info"><span class="stat-label">已封禁用户</span><span class="stat-value">' + (stats.banned_users || 0) + '</span></div></div>' +
      '</div>' +
      '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:20px;">' +
        '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('sliders', '', 18) + '快捷管理入口</h3></div><div style="display:flex;flex-wrap:wrap;gap:10px;">' +
          '<button class="btn btn-primary" onclick="window.AdminApp.switchTab(\'users\')">' + AdminIcons.get('user', '', 16) + '用户与设备操控</button>' +
          '<button class="btn" onclick="window.AdminApp.switchTab(\'broadcast\')">' + AdminIcons.get('activity', '', 16) + '发布全服广播</button>' +
          '<button class="btn" onclick="window.AdminApp.switchTab(\'reports\')">' + AdminIcons.get('shield', '', 16) + '举报工单处理</button>' +
          '<button class="btn" onclick="window.AdminApp.switchTab(\'court\')">' + AdminIcons.get('scale', '', 16) + '公共法庭案件</button>' +
          '<button class="btn" onclick="window.AdminApp.switchTab(\'market\')">' + AdminIcons.get('database', '', 16) + '称号与表情商城</button>' +
          '<button class="btn" onclick="window.AdminApp.switchTab(\'settings\')">' + AdminIcons.get('sliders', '', 16) + '全站参数设置</button>' +
        '</div></div>' +
        '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('activity', '', 18) + '服务器系统状态</h3></div><div style="font-size:13px;line-height:2;color:var(--text-muted);">' +
          '<div><strong style="color:var(--text-main);">运行时间：</strong>' + escapeHtml(stats.uptime || '运行中') + '</div>' +
          '<div><strong style="color:var(--text-main);">服务版本：</strong>' + escapeHtml(stats.server_version || 'v2.0.0') + '</div>' +
          '<div><strong style="color:var(--text-main);">Goroutines：</strong>' + (stats.goroutines || 0) + '</div>' +
        '</div></div>' +
      '</div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { if (err.message !== 'UNAUTHORIZED') toast('加载统计失败: ' + err.message, 'error'); });
  }

  function renderUsers(page, keyword) {
    page = page || 1;
    keyword = keyword !== undefined ? keyword : (C.state.userKeyword || '');
    C.state.userKeyword = keyword;
    api('/v1/admin/users?page=' + page + '&q=' + encodeURIComponent(keyword)).then(function (res) {
      var users = res.users || [];
      var total = res.total || 0;
      var pageSize = res.page_size || 50;
      var totalPages = Math.max(1, Math.ceil(total / pageSize));
      page = Math.min(page, totalPages);

      var rows = users.map(function (u) {
        var username = u.Username || u.username || '未命名';
        var uid = u.UID || u.uid || u.ID || '';
        var title = u.UserTitle || u.user_title || '';
        var coins = u.CoinBalance !== undefined ? u.CoinBalance : (u.coin_balance || 0);
        var rep = u.ReputationScore !== undefined ? u.ReputationScore : (u.reputation_score || 0);
        var isBanned = (u.Banned === 1) || !!u.is_banned;
        var createdAt = formatDate(u.CreatedAt || u.created_at);
        var safeU = encodeURIComponent(JSON.stringify(u));
        return '<tr>' +
          '<td><strong>' + escapeHtml(username) + '</strong>' + (title ? ' <span class="badge badge-primary">' + escapeHtml(title) + '</span>' : '') + '<div style="font-size:11px;color:var(--text-dim);">' + escapeHtml(uid) + '</div></td>' +
          '<td>' + escapeHtml(u.Email || u.email || '—') + '</td>' +
          '<td>' + coins + ' 金币 / 信誉: ' + rep + '</td>' +
          '<td>' + (isBanned ? '<span class="badge badge-danger">已封禁</span>' : '<span class="badge badge-success">正常</span>') + '</td>' +
          '<td>' + escapeHtml(createdAt) + '</td>' +
          '<td>' +
            '<div style="display:flex;gap:4px;flex-wrap:wrap;">' +
              '<button class="btn btn-sm btn-primary" onclick="AdminViews.openUserControlModal(\'' + safeU + '\')">操控</button>' +
              (isBanned ? '<button class="btn btn-sm btn-success" onclick="window.AdminApp.unbanUser(\'' + uid + '\')">解封</button>' :
              '<button class="btn btn-sm btn-danger" onclick="window.AdminApp.banUserPrompt(\'' + uid + '\',\'' + escapeHtml(username) + '\')">封禁</button>') +
            '</div>' +
          '</td>' +
        '</tr>';
      }).join('');

      var hasPrev = page > 1, hasNext = page < totalPages;
      var html = '<div class="card">' +
        '<div class="card-header">' +
          '<h3 class="card-title">' + AdminIcons.get('users', '', 18) + '用户账户与设备管控 (共 ' + total + ' 位用户)</h3>' +
          '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">' +
            '<button class="btn btn-success btn-sm" id="btnOpenCreateUserModal">' + AdminIcons.get('plus', '', 14) + '+ 直接创建新账户</button>' +
            '<button class="btn btn-sm" id="btnViewBannedDevices">' + AdminIcons.get('lock', '', 14) + '封禁设备清单</button>' +
            '<div style="position:relative;width:200px;"><input type="text" id="userSearchInput" class="input" style="padding-left:30px;" placeholder="搜索用户名/UID/邮箱" value="' + escapeHtml(keyword) + '"><div style="position:absolute;left:8px;top:50%;transform:translateY(-50%);color:var(--text-muted);pointer-events:none;">' + AdminIcons.get('search', '', 14) + '</div></div>' +
            '<button class="btn btn-primary btn-sm" id="btnDoUserSearch">搜索</button>' +
            (keyword ? '<button class="btn btn-sm" id="btnResetUserSearch">清空</button>' : '') +
          '</div>' +
        '</div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>用户 (用户名 / UID)</th><th>邮箱</th><th>资产 / 信誉</th><th>状态</th><th>注册时间</th><th>操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6" style="text-align:center;padding:30px;color:var(--text-muted);">未找到匹配的用户</td></tr>') +
        '</tbody></table></div>' +
        '<div style="display:flex;align-items:center;justify-content:space-between;margin-top:16px;flex-wrap:wrap;gap:12px;">' +
          '<div style="font-size:13px;color:var(--text-muted);">第 ' + page + ' / ' + totalPages + ' 页 (共 ' + total + ' 条)</div>' +
          '<div style="display:flex;align-items:center;gap:6px;">' +
            '<button class="btn btn-sm" ' + (hasPrev ? 'onclick="AdminViews.renderUsers(1)"' : 'disabled style="opacity:0.4;"') + '>首页</button>' +
            '<button class="btn btn-sm" ' + (hasPrev ? 'onclick="AdminViews.renderUsers(' + (page - 1) + ')"' : 'disabled style="opacity:0.4;"') + '>上一页</button>' +
            '<button class="btn btn-sm" ' + (hasNext ? 'onclick="AdminViews.renderUsers(' + (page + 1) + ')"' : 'disabled style="opacity:0.4;"') + '>下一页</button>' +
            '<button class="btn btn-sm" ' + (hasNext ? 'onclick="AdminViews.renderUsers(' + totalPages + ')"' : 'disabled style="opacity:0.4;"') + '>尾页</button>' +
          '</div>' +
        '</div>' +
      '</div>';

      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
      var input = $('#userSearchInput');
      var doSearch = function () { renderUsers(1, input.value.trim()); };
      $('#btnDoUserSearch').onclick = doSearch;
      input.onkeydown = function (e) { if (e.keyCode === 13) doSearch(); };
      if ($('#btnResetUserSearch')) $('#btnResetUserSearch').onclick = function () { renderUsers(1, ''); };
      $('#btnOpenCreateUserModal').onclick = openCreateUserModal;
      $('#btnViewBannedDevices').onclick = openBannedDevicesModal;
    }).catch(function (err) { if (err.message !== 'UNAUTHORIZED') toast('加载用户失败: ' + err.message, 'error'); });
  }

  function openCreateUserModal() {
    var body = '<div style="display:grid;gap:12px;">' +
      '<div><label style="font-weight:600;font-size:12px;">用户名 *</label><input type="text" id="newUsrName" class="input" placeholder="输入用户名"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">登录邮箱 *</label><input type="email" id="newUsrEmail" class="input" placeholder="name@example.com"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">初始密码 * (至少6位)</label><input type="text" id="newUsrPwd" class="input" value="12345678"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">自定义 UID (留空自动生成)</label><input type="text" id="newUsrUID" class="input" placeholder="例如 U-888888"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">初始金币</label><input type="number" id="newUsrCoins" class="input" value="100"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">初始专属称号</label><input type="text" id="newUsrTitle" class="input" placeholder="例如 [至尊VIP]"></div>' +
    '</div>';
    var footer = '<button class="btn" onclick="AdminCore.hideModal()">取消</button><button class="btn btn-primary" id="btnSubmitCreateUser">确认创建</button>';
    showModal('+ 直接创建新用户', body, footer);

    $('#btnSubmitCreateUser').onclick = function () {
      var reqBody = {
        username: $('#newUsrName').value.trim(),
        email: $('#newUsrEmail').value.trim(),
        password: $('#newUsrPwd').value.trim(),
        uid: $('#newUsrUID').value.trim(),
        coins: parseInt($('#newUsrCoins').value || '0', 10),
        title: $('#newUsrTitle').value.trim()
      };
      if (!reqBody.username || !reqBody.email || !reqBody.password) { toast('用户名、邮箱和密码必填', 'error'); return; }
      api('/v1/admin/users/create', { method: 'POST', body: reqBody }).then(function (res) {
        toast('用户创建成功！UID: ' + res.data.uid, 'success');
        hideModal();
        renderUsers(1);
      }).catch(function (err) { toast('创建失败: ' + err.message, 'error'); });
    };
  }

  function openUserControlModal(safeUserStr) {
    var u = JSON.parse(decodeURIComponent(safeUserStr));
    var uid = u.UID || u.uid || u.ID;
    var name = u.Username || u.username;
    var coins = u.CoinBalance !== undefined ? u.CoinBalance : (u.coin_balance || 0);
    var title = u.UserTitle || u.user_title || '';
    var body = '<div style="display:grid;gap:14px;max-height:480px;overflow-y:auto;">' +
      '<div style="background:var(--bg-sub);padding:10px;border-radius:4px;border:1px solid #ddd;font-size:12px;display:flex;gap:15px;flex-wrap:wrap;">' +
        '<div>邮箱: <strong>' + escapeHtml(u.Email || u.email || '—') + '</strong></div>' +
        '<div>金币余额: <strong>' + coins + '</strong></div>' +
        '<div>当前称号: <strong>' + (title || '无') + '</strong></div>' +
      '</div>' +
      '<div style="border:1px solid #ccc;padding:10px;background:#fff;"><h4 style="margin:0 0 8px 0;font-size:13px;">🔑 强制重置密码</h4>' +
        '<div style="display:flex;gap:8px;"><input type="text" id="ctrlNewPwd" class="input" placeholder="输入新密码" value="12345678"><button class="btn btn-primary btn-sm" id="btnCtrlResetPwd">重置密码</button></div></div>' +
      '<div style="border:1px solid #ccc;padding:10px;background:#fff;"><h4 style="margin:0 0 8px 0;font-size:13px;">💰 增减金币资产</h4>' +
        '<div style="display:flex;gap:8px;"><input type="number" id="ctrlCoinAmount" class="input" placeholder="增减数量(+500或-100)"><button class="btn btn-primary btn-sm" id="btnCtrlAdjustCoin">更新金币</button></div></div>' +
      '<div style="border:1px solid #ccc;padding:10px;background:#fff;"><h4 style="margin:0 0 8px 0;font-size:13px;">👑 赋予专属头衔 / 称号</h4>' +
        '<div style="display:flex;gap:8px;"><input type="text" id="ctrlNewTitle" class="input" placeholder="输入称号" value="' + escapeHtml(title) + '"><button class="btn btn-primary btn-sm" id="btnCtrlSetTitle">赋予称号</button></div></div>' +
      '<div style="border:1px solid #ccc;padding:10px;background:#fff;"><h4 style="margin:0 0 8px 0;font-size:13px;">⚡ 会话与硬件安全</h4>' +
        '<div style="display:flex;gap:8px;flex-wrap:wrap;">' +
          '<button class="btn btn-danger btn-sm" id="btnCtrlKick">强制踢下线</button>' +
          '<button class="btn btn-danger btn-sm" id="btnCtrlBanDevice">封禁当前物理设备</button>' +
        '</div></div>' +
    '</div>';
    var footer = '<button class="btn" onclick="AdminCore.hideModal()">关闭</button>';
    showModal('用户全方位操控 — ' + escapeHtml(name) + ' (' + escapeHtml(uid) + ')', body, footer);

    $('#btnCtrlResetPwd').onclick = function () {
      var p = $('#ctrlNewPwd').value.trim();
      if (p.length < 6) { toast('密码至少6位', 'error'); return; }
      api('/v1/admin/users/reset-password', { method: 'POST', body: { uid: uid, new_password: p } }).then(function () {
        toast('密码已重置并吊销旧会话', 'success');
      }).catch(function (e) { toast('重置失败: ' + e.message, 'error'); });
    };
    $('#btnCtrlAdjustCoin').onclick = function () {
      var amt = parseInt($('#ctrlCoinAmount').value || '0', 10);
      if (amt === 0) { toast('请输入增减数量', 'error'); return; }
      api('/v1/admin/users/adjust-coins', { method: 'POST', body: { uid: uid, amount: amt } }).then(function (res) {
        toast('金币更新成功，当前余额: ' + res.coin_balance, 'success');
        renderUsers(1);
      }).catch(function (e) { toast('更新失败: ' + e.message, 'error'); });
    };
    $('#btnCtrlSetTitle').onclick = function () {
      var t = $('#ctrlNewTitle').value.trim();
      api('/v1/admin/users/set-title', { method: 'POST', body: { uid: uid, title: t } }).then(function () {
        toast('称号设置成功', 'success');
        renderUsers(1);
      }).catch(function (e) { toast('设置失败: ' + e.message, 'error'); });
    };
    $('#btnCtrlKick').onclick = function () {
      api('/v1/admin/users/kick', { method: 'POST', body: { uid: uid } }).then(function () {
        toast('该用户所有在线会话已被踢除', 'success');
      }).catch(function (e) { toast('下线失败: ' + e.message, 'error'); });
    };
    $('#btnCtrlBanDevice').onclick = function () {
      var reason = prompt('请输入封禁设备原因:', '违规操作，管理员封禁设备');
      if (reason === null) return;
      api('/v1/admin/devices/ban', { method: 'POST', body: { device_id: uid, reason: reason } }).then(function () {
        toast('物理设备封禁生效', 'success');
      }).catch(function (e) { toast('封禁设备失败: ' + e.message, 'error'); });
    };
  }

  function openBannedDevicesModal() {
    api('/v1/admin/devices/banned').then(function (res) {
      var list = res.devices || [];
      var rows = list.map(function (d) {
        return '<tr><td><code>' + escapeHtml(d.DeviceID || d.device_id || '') + '</code></td><td>' + escapeHtml(d.Reason || d.reason || '') + '</td><td>' + formatDate(d.CreatedAt || d.created_at) + '</td><td><button class="btn btn-sm btn-success" onclick="AdminViews.unbanDevice(\'' + (d.DeviceID || d.device_id) + '\')">解封</button></td></tr>';
      }).join('');
      var body = '<div style="max-height:400px;overflow-y:auto;"><table class="table"><thead><tr><th>设备ID</th><th>原因</th><th>封禁时间</th><th>操作</th></tr></thead><tbody>' + (rows || '<tr><td colspan="4" style="text-align:center;">暂无封禁设备</td></tr>') + '</tbody></table></div>';
      var footer = '<button class="btn" onclick="AdminCore.hideModal()">关闭</button>';
      showModal('已封禁设备清单 (共 ' + list.length + ' 台)', body, footer);
    }).catch(function (err) { toast('加载设备失败: ' + err.message, 'error'); });
  }

  function unbanDevice(devId) {
    api('/v1/admin/devices/unban', { method: 'POST', body: { device_id: devId } }).then(function () {
      toast('设备已成功解封！', 'success');
      hideModal();
      openBannedDevicesModal();
    }).catch(function (e) { toast('解封失败: ' + e.message, 'error'); });
  }

  function renderGroups(page) {
    page = page || 1;
    api('/v1/admin/groups?page=' + page).then(function (res) {
      var groups = res.groups || [];
      var rows = groups.map(function (g) {
        var gname = g.Name || g.name || '未命名群组';
        var gid = g.ID || g.id || '';
        var owner = g.OwnerName || g.owner_name || g.OwnerUID || g.owner_uid || '—';
        var members = g.MemberCount !== undefined ? g.MemberCount : (g.member_count || 0);
        var isBanned = (g.Banned === 1) || (g.banned === 1);
        var createdAt = formatDate(g.CreatedAt || g.created_at);
        return '<tr>' +
          '<td><strong>' + escapeHtml(gname) + '</strong><div style="font-size:11px;color:var(--text-dim);">' + escapeHtml(gid) + '</div></td>' +
          '<td>' + escapeHtml(owner) + '</td>' +
          '<td>' + members + ' 人</td>' +
          '<td>' + (isBanned ? '<span class="badge badge-danger">已封禁</span>' : '<span class="badge badge-success">正常</span>') + '</td>' +
          '<td>' + escapeHtml(createdAt) + '</td>' +
          '<td><button class="btn btn-sm btn-danger" onclick="window.AdminApp.dismissGroupPrompt(\'' + gid + '\',\'' + escapeHtml(gname) + '\')">' + AdminIcons.get('trash', '', 14) + '解散</button></td>' +
        '</tr>';
      }).join('');
      var html = '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('messageSquare', '', 18) + '群组治理列表 (第 ' + page + ' 页)</h3></div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>群名称 / ID</th><th>群主</th><th>群成员</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody>' + (rows || '<tr><td colspan="6" style="text-align:center;">暂无群组</td></tr>') + '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { if (err.message !== 'UNAUTHORIZED') toast('加载群组失败: ' + err.message, 'error'); });
  }

  global.AdminViews = {
    renderDashboard: renderDashboard,
    renderUsers: renderUsers,
    renderGroups: renderGroups,
    openUserControlModal: openUserControlModal,
    unbanDevice: unbanDevice
  };
})(window);
