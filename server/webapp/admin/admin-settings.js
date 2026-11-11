/**
 * OldChat Admin 2.0 - System Settings Views (全站参数动态调优)
 */
(function (global) {
  'use strict';
  var C = global.AdminCore;
  var $ = C.$;
  var escapeHtml = C.escapeHtml;
  var api = C.api;
  var toast = C.toast;

  function renderSettings() {
    api('/v1/admin/settings').then(function (res) {
      var s = res.settings || {};
      var html = '<div class="card">' +
        '<div class="card-header"><h3 class="card-title">' + AdminIcons.get('sliders', '', 18) + '全站核心参数动态配置与热调优</h3></div>' +
        '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px;">' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">每日注册人数上限 (0 为不限制)</label><input type="number" id="cfgRegLimit" class="input" value="' + (s.registration_limit || 0) + '"></div>' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">短视频与视频消息功能</label><select id="cfgVideoEnabled" class="input"><option value="true"' + (s.video_enabled ? ' selected' : '') + '>开启 (允许发送与播放)</option><option value="false"' + (!s.video_enabled ? ' selected' : '') + '>关闭 (节省带宽)</option></select></div>' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">公开访问域名 / BaseURL</label><input type="text" id="cfgBaseUrl" class="input" placeholder="https://your-domain.example" value="' + escapeHtml(s.public_base_url || '') + '"></div>' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">媒体/图片下载限速 (MB/s)</label><input type="number" id="cfgMediaRate" class="input" value="' + (s.media_rate_mb || 5) + '"></div>' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">安装包更新下载限速 (MB/s)</label><input type="number" id="cfgUpdateRate" class="input" value="' + (s.update_rate_mb || 5) + '"></div>' +
          '<div><label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">音乐大厅音频限速 (MB/s)</label><input type="number" id="cfgMusicRate" class="input" value="' + (s.music_rate_mb || 5) + '"></div>' +
        '</div>' +
        '<div style="display:flex;justify-content:flex-end;margin-top:24px;">' +
          '<button class="btn btn-primary" id="btnSaveSystemSettings">' + AdminIcons.get('check', '', 16) + '保存并立即应用全站配置</button>' +
        '</div>' +
      '</div>';

      $('#contentBody').innerHTML = html;
      C.renderAllIcons();

      $('#btnSaveSystemSettings').onclick = function () {
        var body = {
          registration_limit: parseInt($('#cfgRegLimit').value || '0', 10),
          video_enabled: $('#cfgVideoEnabled').value === 'true',
          public_base_url: $('#cfgBaseUrl').value.trim(),
          media_rate_mb: parseInt($('#cfgMediaRate').value || '5', 10),
          update_rate_mb: parseInt($('#cfgUpdateRate').value || '5', 10),
          music_rate_mb: parseInt($('#cfgMusicRate').value || '5', 10)
        };
        api('/v1/admin/settings', { method: 'POST', body: body }).then(function () {
          toast('全站系统配置已热更新并保存生效！', 'success');
        }).catch(function (e) { toast('保存配置失败: ' + e.message, 'error'); });
      };
    }).catch(function (err) { toast('加载系统配置失败: ' + err.message, 'error'); });
  }

  global.AdminSettings = {
    renderSettings: renderSettings
  };
})(window);
