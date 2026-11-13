/**
 * OldChat Console —— 客户端与版本发布
 * 发版、装包上传、客户端入口清单，供接手部署的人只靠后台完成发布。
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var $$ = C.$$;
  var escapeHtml = C.escapeHtml;
  var api = C.api;
  var toast = C.toast;

  var state = {
    release: null,
    channel: 'formal',
    clients: []
  };

  var EXT_ACCEPT = '.apk,.aab,.zip,.exe,.msi,.dmg,.ipa';

  function fmtSize(n) {
    n = Number(n) || 0;
    if (n <= 0) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB'];
    var i = 0;
    var v = n;
    while (v >= 1024 && i < units.length - 1) { v = v / 1024; i++; }
    return (i === 0 ? String(v) : v.toFixed(v >= 100 ? 0 : 1)) + ' ' + units[i];
  }

  function fmtTime(unix) {
    if (!unix) return '—';
    try {
      return C.formatDate(new Date(unix * 1000).toISOString());
    } catch (e) {
      return '—';
    }
  }

  function channelLabel(ch) {
    return ch === 'dev' ? '测试版' : '正式版';
  }

  function findChannel(ch) {
    var list = (state.release && state.release.channels) || [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].channel === ch) return list[i];
    }
    return { channel: ch, exists: false, version_name: '', version_code: 0, apk: '', apk_url: '', notes: '', release_notes: '' };
  }

  /* ---------------- 版本发布 ---------------- */

  function renderRelease() {
    api('/v1/admin/release').then(function (res) {
      state.release = res;
      paintRelease();
    }).catch(function (err) {
      toast('加载发版信息失败: ' + err.message, 'error');
    });
  }

  function paintRelease() {
    var data = state.release || {};
    var files = data.files || [];

    var html = '';

    // 通道概览
    html += '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('cloud', '', 18) + '发布通道</h3>' +
      '<a class="btn btn-sm" href="/update" target="_blank" rel="noopener">打开下载页</a></div>' +
      '<div class="card-body"><div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px;">' +
      channelSummary('formal') + channelSummary('dev') +
      '</div>' +
      '<div class="hint" style="margin-top:12px;">当前读取目录 <span class="mono">' + escapeHtml(data.dir || 'update') + '</span>，与客户端更新检查、下载页共用同一份配置。</div>' +
      '</div></div>';

    // 上传
    html += '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('plus', '', 18) + '上传安装包</h3></div>' +
      '<div class="card-body">' +
      '<div class="row"><input type="file" id="relFile" class="input" accept="' + EXT_ACCEPT + '" style="flex:1;min-width:220px;">' +
      '<button class="btn btn-primary" id="btnRelUpload">' + AdminIcons.get('cloud', '', 16) + '开始上传</button></div>' +
      '<div id="relProgressWrap" style="display:none;margin-top:12px;">' +
      '<div style="height:6px;background:var(--bg-subtle);border:1px solid var(--line);">' +
      '<div id="relProgressBar" style="height:100%;width:0;background:var(--accent);"></div></div>' +
      '<div class="hint" id="relProgressText">0%</div>' +
      '</div>' +
      '<div class="hint" style="margin-top:10px;">支持 apk、aab、zip、exe、msi、dmg、ipa，单包上限 ' + (data.max_mb || 512) + ' MB。同名文件不会覆盖，会自动追加时间戳。上传完成后仍需在下方选中该文件并保存版本信息。</div>' +
      '</div></div>';

    // 版本信息
    html += '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('edit', '', 18) + '版本信息</h3></div>' +
      '<div class="card-body"><div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px;">' +
      '<div><label class="field-label">通道</label><select id="relChannel" class="select">' +
      '<option value="formal">正式版 (update.json)</option><option value="dev">测试版 (dev_update.json)</option></select></div>' +
      '<div><label class="field-label">版本名</label><input type="text" id="relVersionName" class="input" placeholder="1.4.5.5"></div>' +
      '<div><label class="field-label">版本号 (versionCode)</label><input type="number" id="relVersionCode" class="input" placeholder="545"></div>' +
      '<div><label class="field-label">安装包</label><select id="relApk" class="select"></select></div>' +
      '</div>' +
      '<div style="margin-top:14px;"><label class="field-label">更新说明</label><textarea id="relNotes" class="textarea" rows="4" placeholder="每行一条，显示在下载页与客户端更新提示"></textarea></div>' +
      '<div style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px;">' +
      '<button class="btn btn-primary" id="btnRelSave">' + AdminIcons.get('check', '', 16) + '保存版本信息</button></div>' +
      '</div></div>';

    // 文件列表
    html += '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('database', '', 18) + '目录文件</h3>' +
      '<span class="muted text-xs">共 ' + files.length + ' 个</span></div><div class="card-body">';
    if (!files.length) {
      html += '<div class="empty-state">目录为空，先上传一个安装包</div>';
    } else {
      html += '<div class="table-wrap"><table class="table"><thead><tr><th>文件名</th><th>大小</th><th>修改时间</th><th>引用</th><th></th></tr></thead><tbody>';
      for (var i = 0; i < files.length; i++) {
        var f = files[i];
        var refBadge = f.referenced
          ? '<span class="badge badge-primary">' + channelLabel(f.referenced) + '</span>'
          : '<span class="muted text-xs">—</span>';
        html += '<tr><td class="mono truncate">' + escapeHtml(f.name) + '</td>' +
          '<td class="nowrap">' + fmtSize(f.size) + '</td>' +
          '<td class="nowrap muted text-xs">' + fmtTime(f.modified_at) + '</td>' +
          '<td>' + refBadge + '</td>' +
          '<td class="nowrap"><button class="btn btn-sm btn-danger" data-del="' + escapeHtml(f.name) + '">删除</button></td></tr>';
      }
      html += '</tbody></table></div>';
    }
    html += '</div></div>';

    $('#contentBody').innerHTML = html;
    C.renderAllIcons();

    bindRelease();
  }

  function channelSummary(ch) {
    var c = findChannel(ch);
    var ready = c.exists && (c.apk || c.apk_url);
    var badge = ready
      ? '<span class="badge badge-success">已发布</span>'
      : '<span class="badge badge-warning">未发布</span>';
    var version = c.version_name ? ('v' + c.version_name) : '—';
    var code = c.version_code > 0 ? ('#' + c.version_code) : '—';
    return '<div class="box">' +
      '<div class="box-title">' + channelLabel(ch) + ' ' + badge + '</div>' +
      '<div class="mono" style="font-size:15px;margin:6px 0 4px;">' + escapeHtml(version) + '</div>' +
      '<div class="hint">版本号 ' + escapeHtml(code) + '</div>' +
      '<div class="hint mono truncate">' + escapeHtml(c.apk || c.apk_url || '尚未指定安装包') + '</div>' +
      '</div>';
  }

  function bindRelease() {
    var sel = $('#relChannel');
    if (sel) {
      sel.value = state.channel;
      sel.onchange = function () {
        state.channel = sel.value;
        fillReleaseForm();
      };
    }
    fillReleaseForm();

    var btnUpload = $('#btnRelUpload');
    if (btnUpload) btnUpload.onclick = uploadArtifact;

    var btnSave = $('#btnRelSave');
    if (btnSave) btnSave.onclick = saveRelease;

    $$('#contentBody [data-del]').forEach(function (btn) {
      btn.onclick = function () {
        var name = btn.getAttribute('data-del');
        if (!confirm('确定删除 ' + name + ' 吗？此操作不可恢复。')) return;
        api('/v1/admin/release/delete', { method: 'POST', body: { name: name } }).then(function () {
          toast('已删除 ' + name, 'success');
          renderRelease();
        }).catch(function (err) { toast('删除失败: ' + err.message, 'error'); });
      };
    });
  }

  function fillReleaseForm() {
    var c = findChannel(state.channel);
    var nameEl = $('#relVersionName');
    var codeEl = $('#relVersionCode');
    var notesEl = $('#relNotes');
    var apkEl = $('#relApk');
    if (nameEl) nameEl.value = c.version_name || '';
    if (codeEl) codeEl.value = c.version_code > 0 ? String(c.version_code) : '';
    if (notesEl) notesEl.value = c.notes || c.release_notes || '';
    if (!apkEl) return;

    var files = ((state.release && state.release.files) || []).filter(function (f) {
      return /\.(apk|aab|zip|exe|msi|dmg|ipa)$/i.test(f.name);
    });
    var options = '<option value="">不指定</option>';
    for (var i = 0; i < files.length; i++) {
      options += '<option value="' + escapeHtml(files[i].name) + '">' + escapeHtml(files[i].name) + ' (' + fmtSize(files[i].size) + ')</option>';
    }
    apkEl.innerHTML = options;

    var current = c.apk || '';
    if (current && !files.some(function (f) { return f.name === current; })) {
      apkEl.innerHTML += '<option value="' + escapeHtml(current) + '">' + escapeHtml(current) + '（未在目录中找到）</option>';
    }
    apkEl.value = current;

    // 刚上传完的文件直接选中，并从文件名里预填版本号，省去手工翻找
    if (state.pendingApk) {
      var pending = state.pendingApk;
      state.pendingApk = '';
      for (var k = 0; k < apkEl.options.length; k++) {
        if (apkEl.options[k].value === pending) { apkEl.value = pending; break; }
      }
      var matched = /-(\d+)\.[A-Za-z0-9]+$/.exec(pending);
      if (matched && codeEl && !codeEl.value) codeEl.value = matched[1];
    }
  }

  function saveRelease() {
    var name = ($('#relVersionName') ? $('#relVersionName').value : '').trim();
    var code = parseInt(($('#relVersionCode') ? $('#relVersionCode').value : '0') || '0', 10);
    var apk = $('#relApk') ? $('#relApk').value : '';
    var notes = $('#relNotes') ? $('#relNotes').value : '';
    var current = findChannel(state.channel);

    if (name && !/^[0-9A-Za-z._-]+$/.test(name)) {
      toast('版本名只能包含字母、数字、点、下划线和连字符', 'error');
      return;
    }
    if (!name && code <= 0) {
      toast('版本名与版本号至少填写一个', 'error');
      return;
    }
    var body = {
      channel: state.channel,
      version_name: name,
      version_code: isNaN(code) ? 0 : code,
      apk: apk,
      apk_url: current.apk_url || '',
      notes: notes,
      release_notes: current.release_notes || ''
    };
    api('/v1/admin/release/save', { method: 'POST', body: body }).then(function () {
      toast(channelLabel(state.channel) + ' 版本信息已保存', 'success');
      renderRelease();
    }).catch(function (err) { toast('保存失败: ' + err.message, 'error'); });
  }

  function uploadArtifact() {
    var input = $('#relFile');
    if (!input || !input.files || !input.files.length) {
      toast('请先选择文件', 'error');
      return;
    }
    var file = input.files[0];
    var wrap = $('#relProgressWrap');
    var bar = $('#relProgressBar');
    var text = $('#relProgressText');
    var btn = $('#btnRelUpload');

    if (wrap) wrap.style.display = 'block';
    if (btn) btn.disabled = true;

    var form = new FormData();
    form.append('file', file, file.name);

    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/v1/admin/release/upload', true);
    xhr.withCredentials = true;

    xhr.upload.onprogress = function (e) {
      if (!e.lengthComputable) return;
      var pct = Math.round((e.loaded / e.total) * 100);
      if (bar) bar.style.width = pct + '%';
      if (text) text.textContent = pct + '% · ' + fmtSize(e.loaded) + ' / ' + fmtSize(e.total);
    };

    xhr.onload = function () {
      if (btn) btn.disabled = false;
      var data = null;
      try { data = JSON.parse(xhr.responseText); } catch (e) { data = null; }
      if (xhr.status >= 200 && xhr.status < 300 && data) {
        var finalName = data.name || file.name;
        if (bar) bar.style.width = '100%';
        if (text) text.textContent = '上传完成 · ' + fmtSize(data.size || file.size);
        toast('已上传 ' + finalName, 'success');
        if (input) input.value = '';
        state.pendingApk = finalName;
        renderRelease();
      } else if (xhr.status === 401) {
        if (text) text.textContent = '会话已失效，请重新登录';
        toast('管理会话已失效', 'error');
      } else {
        if (text) text.textContent = '上传失败';
        toast('上传失败: ' + ((data && (data.error || data.message)) || ('HTTP ' + xhr.status)), 'error');
      }
    };

    xhr.onerror = function () {
      if (btn) btn.disabled = false;
      if (text) text.textContent = '网络中断，上传未完成';
      toast('上传失败：网络中断', 'error');
    };

    xhr.send(form);
  }

  /* ---------------- 客户端清单 ---------------- */

  function renderClients() {
    api('/v1/admin/clients').then(function (res) {
      state.clients = res.clients || [];
      paintClients();
    }).catch(function (err) {
      toast('加载客户端清单失败: ' + err.message, 'error');
    });
  }

  function paintClients() {
    var rows = '';
    for (var i = 0; i < state.clients.length; i++) {
      var c = state.clients[i];
      rows += '<tr data-row="' + i + '">' +
        '<td><input class="input" data-f="name" value="' + escapeHtml(c.name || '') + '" placeholder="Android 客户端"></td>' +
        '<td><input class="input" data-f="platform" value="' + escapeHtml(c.platform || '') + '" placeholder="Android"></td>' +
        '<td><input class="input" data-f="version" value="' + escapeHtml(c.version || '') + '" placeholder="1.4.5.5"></td>' +
        '<td><input class="input" data-f="description" value="' + escapeHtml(c.description || '') + '" placeholder="一句话说明"></td>' +
        '<td><input class="input mono" data-f="download_url" value="' + escapeHtml(c.download_url || '') + '" placeholder="暂未提供"></td>' +
        '<td style="text-align:center;"><label class="switch"><input type="checkbox" data-f="enabled"' + (c.enabled ? ' checked' : '') + '><span class="slider"></span></label></td>' +
        '<td class="nowrap"><button class="btn btn-sm btn-danger" data-delrow="' + i + '">删除</button></td>' +
        '</tr>';
    }
    if (!state.clients.length) {
      rows = '<tr><td colspan="7"><div class="empty-state">还没有客户端条目，点右上角新增</div></td></tr>';
    }

    var html = '<div class="card"><div class="card-header"><h3 class="card-title">' + AdminIcons.get('device', '', 18) + '客户端入口</h3>' +
      '<div class="row-tight"><button class="btn btn-sm" id="btnClientAdd">' + AdminIcons.get('plus', '', 16) + '新增</button>' +
      '<button class="btn btn-sm btn-primary" id="btnClientSave">' + AdminIcons.get('check', '', 16) + '保存</button></div></div>' +
      '<div class="card-body">' +
      '<div class="table-wrap"><table class="table"><thead><tr>' +
      '<th style="width:18%">名称</th><th style="width:12%">平台</th><th style="width:12%">版本</th>' +
      '<th style="width:26%">说明</th><th style="width:24%">下载地址</th><th style="width:6%">显示</th><th></th>' +
      '</tr></thead><tbody id="clientRows">' + rows + '</tbody></table></div>' +
      '<div class="hint" style="margin-top:12px;">下载地址可以先留空，页面侧显示为“暂未提供”；后续填上完整链接即可。</div>' +
      '</div></div>';

    $('#contentBody').innerHTML = html;
    C.renderAllIcons();

    var addBtn = $('#btnClientAdd');
    if (addBtn) addBtn.onclick = function () {
      collectClients();
      state.clients.push({ id: '', name: '', platform: '', version: '', description: '', download_url: '', enabled: true });
      paintClients();
    };

    var saveBtn = $('#btnClientSave');
    if (saveBtn) saveBtn.onclick = function () {
      collectClients();
      if (!state.clients.length) { toast('至少保留一个条目', 'error'); return; }
      for (var i = 0; i < state.clients.length; i++) {
        if (!state.clients[i].name) { toast('第 ' + (i + 1) + ' 行缺少名称', 'error'); return; }
      }
      api('/v1/admin/clients', { method: 'POST', body: { clients: state.clients } }).then(function (res) {
        state.clients = res.clients || state.clients;
        toast('客户端清单已保存', 'success');
        paintClients();
      }).catch(function (err) { toast('保存失败: ' + err.message, 'error'); });
    };

    $$('#clientRows [data-delrow]').forEach(function (btn) {
      btn.onclick = function () {
        collectClients();
        var idx = parseInt(btn.getAttribute('data-delrow'), 10);
        if (isNaN(idx)) return;
        if (!confirm('删除客户端条目「' + (state.clients[idx].name || '未命名') + '」？')) return;
        state.clients.splice(idx, 1);
        paintClients();
      };
    });
  }

  function collectClients() {
    $$('#clientRows [data-row]').forEach(function (tr) {
      var idx = parseInt(tr.getAttribute('data-row'), 10);
      if (isNaN(idx) || !state.clients[idx]) return;
      var entry = state.clients[idx];
      $$('[data-f]', tr).forEach(function (el) {
        var field = el.getAttribute('data-f');
        if (field === 'enabled') entry.enabled = el.checked;
        else entry[field] = el.value.trim();
      });
    });
  }

  global.AdminRelease = {
    renderRelease: renderRelease,
    renderClients: renderClients
  };
})(window);
