/**
 * OldChat Admin 2.0 - Social & Operations Views (全服广播、封禁申诉、Bug反馈)
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

  function renderBroadcast() {
    api('/v1/admin/notifications').then(function (res) {
      var list = res.notifications || [];
      var rows = list.map(function (n) {
        var target = n.TargetUserID || n.target_user_id;
        var targetBadge = target ? '<span class="badge badge-primary">定向: ' + escapeHtml(target) + '</span>' : '<span class="badge badge-success">全服广播</span>';
        var impBadge = (n.Important || n.important) ? ' <span class="badge badge-danger">紧急重要</span>' : '';
        return '<tr>' +
          '<td><strong>' + escapeHtml(n.title || n.Title || '系统公告') + '</strong>' + impBadge + '</td>' +
          '<td>' + escapeHtml(n.body || n.Body || n.content || '') + '</td>' +
          '<td>' + targetBadge + '</td>' +
          '<td>' + formatDate(n.created_at || n.CreatedAt) + '</td>' +
          '<td><button class="btn btn-sm btn-danger" onclick="AdminSocial.deleteNotification(\'' + (n.id || n.ID || '') + '\')">删除/下架</button></td>' +
        '</tr>';
      }).join('');

      var html = '<div class="card">' +
        '<div class="card-header">' +
          '<h3 class="card-title">' + AdminIcons.get('activity', '', 18) + '全服系统公告与通知发布中心</h3>' +
          '<button class="btn btn-primary btn-sm" id="btnOpenSendBroadcast">' + AdminIcons.get('plus', '', 14) + '+ 发布新公告 / 通知</button>' +
        '</div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>公告标题</th><th>公告正文内容</th><th>发送范围</th><th>发布时间</th><th>操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="5" style="text-align:center;padding:30px;color:var(--text-muted);">暂无历史广播公告</td></tr>') +
        '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();

      $('#btnOpenSendBroadcast').onclick = function () {
        var body = '<div style="display:grid;gap:12px;">' +
          '<div><label style="font-weight:600;font-size:12px;">公告标题 *</label><input type="text" id="bcTitle" class="input" placeholder="例如：OldChat 系统维护与新版本上线通知"></div>' +
          '<div><label style="font-weight:600;font-size:12px;">推送范围 (留空为全服广播，填 UID 为定向私信)</label><input type="text" id="bcTargetUID" class="input" placeholder="留空则全服在线用户即刻收到推送"></div>' +
          '<div><label style="font-weight:600;font-size:12px;">公告正文 *</label><textarea id="bcContent" class="input" rows="4" placeholder="输入通知详细内容..."></textarea></div>' +
          '<div style="display:flex;align-items:center;gap:8px;"><input type="checkbox" id="bcImportant" style="width:16px;height:16px;"><label for="bcImportant" style="font-size:13px;cursor:pointer;">标记为紧急高亮公告（置顶气泡并强提醒）</label></div>' +
        '</div>';
        var footer = '<button class="btn" onclick="AdminCore.hideModal()">取消</button><button class="btn btn-primary" id="btnSubmitBroadcast">立即全网推流发布</button>';
        showModal('📢 发布系统公告与通知', body, footer);

        $('#btnSubmitBroadcast').onclick = function () {
          var title = $('#bcTitle').value.trim();
          var content = $('#bcContent').value.trim();
          var targetUID = $('#bcTargetUID').value.trim();
          var important = $('#bcImportant').checked;
          if (!title || !content) { toast('标题和正文必填', 'error'); return; }
          api('/v1/admin/notifications/send', { method: 'POST', body: { title: title, content: content, target_uid: targetUID, important: important } }).then(function (res) {
            toast(res.message || '公告发布成功！', 'success');
            hideModal();
            renderBroadcast();
          }).catch(function (e) { toast('发布失败: ' + e.message, 'error'); });
        };
      };
    }).catch(function (err) { toast('加载广播失败: ' + err.message, 'error'); });
  }

  function deleteNotification(id) {
    if (!confirm('确认删除并下架此条系统广播？')) return;
    api('/v1/admin/notifications/delete', { method: 'POST', body: { id: id } }).then(function () {
      toast('广播已下架删除', 'success');
      renderBroadcast();
    }).catch(function (e) { toast('删除失败: ' + e.message, 'error'); });
  }

  function renderAppeals() {
    api('/v1/admin/appeals').then(function (res) {
      var list = res.appeals || [];
      var rows = list.map(function (a) {
        var isPending = (a.Status || a.status) === 'pending';
        var statusBadge = isPending ? '<span class="badge badge-warning">待审批</span>' :
          ((a.Status || a.status) === 'approved' ? '<span class="badge badge-success">已解封</span>' : '<span class="badge badge-danger">已驳回</span>');
        return '<tr>' +
          '<td><strong>' + escapeHtml(a.Username || a.username || '') + '</strong><div style="font-size:11px;color:var(--text-dim);">' + escapeHtml(a.UID || a.uid || '') + '</div></td>' +
          '<td><span style="color:var(--danger);">' + escapeHtml(a.BanReason || a.ban_reason || '违规封禁') + '</span></td>' +
          '<td>' + escapeHtml(a.AppealText || a.appeal_text || '—') + '<div style="font-size:11px;color:var(--text-muted);">联系方式: ' + escapeHtml(a.Contact || a.contact || '无') + '</div></td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + formatDate(a.CreatedAt || a.created_at) + '</td>' +
          '<td>' + (isPending ? '<div style="display:flex;gap:4px;"><button class="btn btn-sm btn-success" onclick="AdminSocial.respondAppeal(\'' + a.ID + '\',\'approved\')">解封通过</button><button class="btn btn-sm btn-danger" onclick="AdminSocial.respondAppeal(\'' + a.ID + '\',\'rejected\')">驳回</button></div>' : '已归档') + '</td>' +
        '</tr>';
      }).join('');

      var html = '<div class="card">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('shield', '', 18) + '封禁申诉审批仲裁 (共 ' + list.length + ' 条申诉)</h3></div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>申诉用户</th><th>封禁原因</th><th>申诉理由与陈述</th><th>当前状态</th><th>提交时间</th><th>审批操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6" style="text-align:center;padding:30px;color:var(--text-muted);">暂无封禁申诉</td></tr>') +
        '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { toast('加载申诉失败: ' + err.message, 'error'); });
  }

  function respondAppeal(id, action) {
    var note = prompt('请输入审批意见/备注 (可选):', action === 'approved' ? '审核通过，准予解封' : '申诉理由不充分，维持原判');
    if (note === null) return;
    api('/v1/admin/appeals/respond', { method: 'POST', body: { id: id, status: action, admin_note: note } }).then(function () {
      toast(action === 'approved' ? '已解封该用户！' : '申诉已驳回', 'success');
      renderAppeals();
    }).catch(function (e) { toast('审批失败: ' + e.message, 'error'); });
  }

  function renderBugs() {
    api('/v1/admin/bugs').then(function (res) {
      var list = res.bugs || [];
      var rows = list.map(function (b) {
        var status = b.Status || b.status || 'open';
        var statusHtml = status === 'resolved' ? '<span class="badge badge-success">已修复</span>' :
          (status === 'closed' ? '<span class="badge badge-secondary">已关闭</span>' : '<span class="badge badge-warning">待处理</span>');
        return '<tr>' +
          '<td><code>' + escapeHtml(b.UserUID || b.user_uid || '匿名') + '</code></td>' +
          '<td>' + escapeHtml(b.Content || b.content || '') + '</td>' +
          '<td><span class="badge badge-primary">' + escapeHtml(b.DeviceModel || b.device_model || '—') + '</span> / Android: ' + escapeHtml(b.AndroidVersion || b.android_version || '—') + '</td>' +
          '<td>' + statusHtml + '</td>' +
          '<td>' + formatDate(b.CreatedAt || b.created_at) + '</td>' +
          '<td>' +
            '<div style="display:flex;gap:4px;">' +
              '<button class="btn btn-sm btn-success" onclick="AdminSocial.setBugStatus(\'' + b.ID + '\',\'resolved\')">标记已修</button>' +
              '<button class="btn btn-sm" onclick="AdminSocial.setBugStatus(\'' + b.ID + '\',\'closed\')">关闭</button>' +
            '</div>' +
          '</td>' +
        '</tr>';
      }).join('');

      var html = '<div class="card">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('sliders', '', 18) + 'Bug 反馈与建议箱 (共 ' + list.length + ' 条工单)</h3></div>' +
        '<div class="table-wrap"><table class="table"><thead><tr><th>提交者</th><th>反馈内容</th><th>设备与系统</th><th>状态</th><th>提交时间</th><th>操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="6" style="text-align:center;padding:30px;color:var(--text-muted);">暂无 Bug 反馈</td></tr>') +
        '</tbody></table></div></div>';
      $('#contentBody').innerHTML = html;
      C.renderAllIcons();
    }).catch(function (err) { toast('加载Bug失败: ' + err.message, 'error'); });
  }

  function setBugStatus(id, status) {
    var note = prompt('请输入处理备注:', status === 'resolved' ? '已在最新版本中修复' : '无需修复或已忽略');
    if (note === null) return;
    api('/v1/admin/bugs/status', { method: 'POST', body: { id: id, status: status, admin_note: note } }).then(function () {
      toast('Bug工单状态已更新', 'success');
      renderBugs();
    }).catch(function (e) { toast('更新失败: ' + e.message, 'error'); });
  }

  global.AdminSocial = {
    renderBroadcast: renderBroadcast,
    deleteNotification: deleteNotification,
    renderAppeals: renderAppeals,
    respondAppeal: respondAppeal,
    renderBugs: renderBugs,
    setBugStatus: setBugStatus
  };
})(window);
