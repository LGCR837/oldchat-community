/**
 * OldChat Admin 2.0 - Market & Emoji Plaza Views (称号商城、表情管理)
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

  function renderMarket() {
    api('/v1/admin/titles/catalog').then(function (res) {
      var titles = res.titles || [];
      var titleRows = titles.map(function (t) {
        return '<tr>' +
          '<td><strong>' + escapeHtml(t.Title || t.title) + '</strong></td>' +
          '<td>' + (t.Price !== undefined ? t.Price : t.price) + ' 金币</td>' +
          '<td>' + ((t.Active === undefined || t.Active) ? '<span class="badge badge-success">上架中</span>' : '<span class="badge badge-danger">已下架</span>') + '</td>' +
          '<td>' +
            '<div style="display:flex;gap:4px;">' +
              '<button class="btn btn-sm" onclick="AdminMarket.openEditTitleModal(\'' + t.ID + '\',\'' + escapeHtml(t.Title || t.title) + '\',' + (t.Price || 0) + ')">编辑</button>' +
              '<button class="btn btn-sm btn-danger" onclick="AdminMarket.deleteTitle(\'' + t.ID + '\')">删除</button>' +
            '</div>' +
          '</td>' +
        '</tr>';
      }).join('');

      var html = '<div style="display:grid;gap:20px;">' +
        '<div class="card">' +
          '<div class="card-header">' +
            '<h3 class="card-title">' + AdminIcons.get('database', '', 18) + '称号商店商品管理 (共 ' + titles.length + ' 种称号)</h3>' +
            '<button class="btn btn-primary btn-sm" id="btnOpenAddTitle">' + AdminIcons.get('plus', '', 14) + '+ 新增上架称号</button>' +
          '</div>' +
          '<div class="table-wrap"><table class="table"><thead><tr><th>称号名称</th><th>售价 (金币)</th><th>状态</th><th>操作</th></tr></thead><tbody>' +
          (titleRows || '<tr><td colspan="4" style="text-align:center;">暂无称号</td></tr>') +
          '</tbody></table></div>' +
        '</div>' +
        '<div class="card">' +
          '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('sliders', '', 18) + '全站表情广场违规审核</h3></div>' +
          '<div id="emojiPlazaContainer"><div style="text-align:center;padding:20px;color:var(--text-muted);">正在加载表情广场...</div></div>' +
        '</div>' +
      '</div>';

      $('#contentBody').innerHTML = html;
      C.renderAllIcons();

      $('#btnOpenAddTitle').onclick = openAddTitleModal;
      loadEmojiPlaza();
    }).catch(function (err) { toast('加载商城失败: ' + err.message, 'error'); });
  }

  function openAddTitleModal() {
    var body = '<div style="display:grid;gap:12px;">' +
      '<div><label style="font-weight:600;font-size:12px;">称号名称 *</label><input type="text" id="addTitleName" class="input" placeholder="例如 [至尊霸主]"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">金币售价 *</label><input type="number" id="addTitlePrice" class="input" value="1000"></div>' +
    '</div>';
    var footer = '<button class="btn" onclick="AdminCore.hideModal()">取消</button><button class="btn btn-primary" id="btnSubmitAddTitle">确认上架</button>';
    showModal('+ 上架新称号', body, footer);

    $('#btnSubmitAddTitle').onclick = function () {
      var t = $('#addTitleName').value.trim();
      var p = parseInt($('#addTitlePrice').value || '0', 10);
      if (!t) { toast('称号名称必填', 'error'); return; }
      api('/v1/admin/titles/catalog/add', { method: 'POST', body: { title: t, price: p } }).then(function () {
        toast('称号上架成功！', 'success');
        hideModal();
        renderMarket();
      }).catch(function (e) { toast('上架失败: ' + e.message, 'error'); });
    };
  }

  function openEditTitleModal(id, title, price) {
    var body = '<div style="display:grid;gap:12px;">' +
      '<div><label style="font-weight:600;font-size:12px;">称号名称</label><input type="text" id="editTitleName" class="input" value="' + escapeHtml(title) + '"></div>' +
      '<div><label style="font-weight:600;font-size:12px;">金币售价</label><input type="number" id="editTitlePrice" class="input" value="' + price + '"></div>' +
    '</div>';
    var footer = '<button class="btn" onclick="AdminCore.hideModal()">取消</button><button class="btn btn-primary" id="btnSubmitEditTitle">保存修改</button>';
    showModal('编辑称号 — ' + escapeHtml(title), body, footer);

    $('#btnSubmitEditTitle').onclick = function () {
      var t = $('#editTitleName').value.trim();
      var p = parseInt($('#editTitlePrice').value || '0', 10);
      api('/v1/admin/titles/catalog/update', { method: 'POST', body: { id: id, title: t, price: p } }).then(function () {
        toast('称号更新成功！', 'success');
        hideModal();
        renderMarket();
      }).catch(function (e) { toast('更新失败: ' + e.message, 'error'); });
    };
  }

  function deleteTitle(id) {
    if (!confirm('确认删除该商城称号？')) return;
    api('/v1/admin/titles/catalog/delete', { method: 'POST', body: { id: id } }).then(function () {
      toast('称号已删除', 'success');
      renderMarket();
    }).catch(function (e) { toast('删除失败: ' + e.message, 'error'); });
  }

  function loadEmojiPlaza() {
    api('/v1/admin/emoji-plaza').then(function (res) {
      var list = res.emojis || [];
      var rows = list.map(function (e) {
        var preview = e.CoverURL || e.MediaURL || e.cover_url || e.media_url || '';
        return '<tr>' +
          '<td><img src="' + escapeHtml(preview) + '" style="width:40px;height:40px;object-fit:cover;border-radius:4px;background:#eee;"></td>' +
          '<td><strong>' + escapeHtml(e.Name || e.name) + '</strong></td>' +
          '<td>' + escapeHtml(e.OwnerName || e.owner_name || '—') + ' (' + escapeHtml(e.OwnerUID || e.owner_uid || '') + ')</td>' +
          '<td>' + formatDate(e.CreatedAt || e.created_at) + '</td>' +
          '<td><button class="btn btn-sm btn-danger" onclick="AdminMarket.deleteEmoji(\'' + e.ID + '\')">违规下架</button></td>' +
        '</tr>';
      }).join('');
      $('#emojiPlazaContainer').innerHTML = '<div class="table-wrap"><table class="table"><thead><tr><th>预览</th><th>表情名称</th><th>上传者</th><th>创建时间</th><th>操作</th></tr></thead><tbody>' +
        (rows || '<tr><td colspan="5" style="text-align:center;padding:20px;">暂无表情包</td></tr>') +
        '</tbody></table></div>';
    }).catch(function (err) { $('#emojiPlazaContainer').innerHTML = '<div style="color:var(--danger);padding:20px;">加载表情失败: ' + escapeHtml(err.message) + '</div>'; });
  }

  function deleteEmoji(id) {
    if (!confirm('确认下架删除此表情包？')) return;
    api('/v1/admin/emoji-plaza/delete', { method: 'POST', body: { id: id } }).then(function () {
      toast('表情包已下架删除', 'success');
      loadEmojiPlaza();
    }).catch(function (e) { toast('下架失败: ' + e.message, 'error'); });
  }

  global.AdminMarket = {
    renderMarket: renderMarket,
    openEditTitleModal: openEditTitleModal,
    deleteTitle: deleteTitle,
    deleteEmoji: deleteEmoji
  };
})(window);
