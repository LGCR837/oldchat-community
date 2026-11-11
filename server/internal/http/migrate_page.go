package httpapi

import (
	"encoding/json"
	"strings"

	"metrochat/internal/config"
)

const appMigrateHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>旧聊 · 数据迁移</title>
  <link rel="stylesheet" href="/app-assets/app.css" />
  <style>
    .mig-card { max-width: 720px; }
    .mig-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
    .mig-tab { flex: 1; padding: 9px 10px; font-size: 13px; font-weight: bold; border: 1px solid var(--border, #d0d4dc); background: #fff; color: #495057; cursor: pointer; }
    .mig-tab.active { background: #2b5b84; border-color: #2b5b84; color: #fff; }
    .mig-group { margin-bottom: 14px; }
    .mig-group-title { font-size: 12px; font-weight: bold; color: #495057; letter-spacing: .5px; margin-bottom: 8px; border-bottom: 1px solid #e6e8ec; padding-bottom: 4px; }
    .mig-note { font-size: 12px; color: #6c757d; line-height: 1.5; margin: 0 0 10px; }
    .mig-head { display: flex; justify-content: space-between; align-items: baseline; font-size: 13px; font-weight: bold; margin-bottom: 6px; }
    .mig-bar { height: 10px; background: #eceff3; border: 1px solid #d0d4dc; overflow: hidden; }
    .mig-bar-fill { height: 100%; width: 0%; background: #2b5b84; transition: width .4s ease; }
    .mig-stats { display: flex; flex-wrap: wrap; gap: 8px 18px; font-size: 12px; color: #495057; margin: 12px 0 14px; }
    .mig-stats b { color: #111; }
    .mig-events { list-style: none; margin: 0; padding: 0; max-height: 380px; overflow-y: auto; border-top: 1px solid #e6e8ec; }
    .mig-events li { display: flex; gap: 8px; padding: 6px 2px; font-size: 12.5px; border-bottom: 1px solid #f1f3f6; line-height: 1.5; }
    .mig-events time { color: #8c98a4; font-variant-numeric: tabular-nums; }
    .mig-events .stage { color: #2b5b84; font-weight: bold; white-space: nowrap; }
    .mig-events li.warn .msg { color: #8a6d3b; }
    .mig-events li.error .msg { color: #b02a37; }
    .mig-events li.ok .msg { color: #1d6f42; }
    .mig-hint { font-size: 12px; color: #8c98a4; margin-top: 12px; text-align: center; }
    .mig-done { border: 1px solid #c3e6cb; background: #f3fbf5; padding: 10px 12px; font-size: 13px; margin-bottom: 14px; }
    .mig-failed { border: 1px solid #f5c2c7; background: #fdf3f4; padding: 10px 12px; font-size: 13px; margin-bottom: 14px; color: #b02a37; }
    .mig-summary { font-size: 12px; color: #495057; margin-top: 10px; }
  </style>
</head>
<body>
  <div class="auth-page">
    <div class="auth-card mig-card">
      <div class="auth-brand">
        <h1>旧聊</h1>
        <p class="auth-subtitle">OldChat · 从旧服务器迁移数据</p>
      </div>

      <div id="setup">
        <div class="mig-tabs">
          <button type="button" class="mig-tab active" data-mode="register">迁移并注册</button>
          <button type="button" class="mig-tab" data-mode="existing">我已注册，只导入</button>
        </div>

        <p class="mig-note">服务器会用你提供的旧账号密码代你登录一次，把账号资料、头像封面、好友、私聊、群聊与朋友圈搬到本服务器。密码只在内存里用一次，不写入数据库与日志。</p>

        <div class="mig-group">
          <div class="mig-group-title">旧服务器账号</div>
          <label class="field">
            <span>账号或邮箱</span>
            <input type="text" id="fOfficialUser" placeholder="旧服务器登录用户名" autocomplete="off" />
          </label>
          <label class="field">
            <span>密码</span>
            <input type="password" id="fOfficialPass" placeholder="旧服务器登录密码" autocomplete="off" />
          </label>
        </div>

        <div class="mig-group" id="pane-register">
          <div class="mig-group-title">社区版新账号</div>
          <label class="field">
            <span>用户名</span>
            <input type="text" id="fUsername" placeholder="3-20 位小写字母/数字/下划线" autocomplete="off" />
          </label>
          <label class="field">
            <span>邮箱</span>
            <input type="email" id="fEmail" placeholder="常用邮箱" autocomplete="off" />
          </label>
          <label class="field">
            <span>密码</span>
            <input type="password" id="fPassword" placeholder="至少 8 位" autocomplete="new-password" />
          </label>
        </div>

        <div class="mig-group" id="pane-existing" hidden>
          <div class="mig-group-title">已有社区账号</div>
          <label class="field">
            <span>用户名或邮箱</span>
            <input type="text" id="fIdentifier" placeholder="已注册的社区账号" autocomplete="off" />
          </label>
          <label class="field">
            <span>社区密码</span>
            <input type="password" id="fExistingPass" placeholder="社区账号密码" autocomplete="current-password" />
          </label>
        </div>

        <div class="field captcha-field" id="captchaField">
          <span>人机验证</span>
          <div id="captchaContainer"></div>
        </div>

        <button type="button" class="btn-primary" id="btnStart">开始迁移</button>
        <p class="auth-error" id="formError"></p>
      </div>

      <div id="progress" hidden>
        <div id="migBanner"></div>
        <div class="mig-head"><span id="migStage">准备中</span><span id="migPercent">0%</span></div>
        <div class="mig-bar"><div class="mig-bar-fill" id="migBarFill"></div></div>
        <div class="mig-stats" id="migStats"></div>
        <ol class="mig-events" id="migEvents"></ol>
        <p class="mig-hint" id="migHint">每 10 秒自动刷新一次，可以离开本页，回来时用同一个链接继续看</p>
        <p class="mig-summary" id="migSummary"></p>
      </div>

      <p class="auth-footer"><a href="/">返回首页</a> · <a href="/app/">前往登录</a></p>
    </div>
  </div>

  <script>window.__MIG_CFG = __MIG_CFG_JSON__;</script>
  <script>
    (function () {
      'use strict';
      var CFG = window.__MIG_CFG || { captcha: 'off', powDifficulty: 18, sourceHost: '' };
      var mode = 'register';
      var jobId = '';
      var pollTimer = null;
      var busy = false;
      var powSolution = null;

      var setup = document.getElementById('setup');
      var progress = document.getElementById('progress');
      var errEl = document.getElementById('formError');
      var btn = document.getElementById('btnStart');

      /*__POW_JS__*/

      var tabs = document.querySelectorAll('.mig-tab');
      for (var i = 0; i < tabs.length; i++) {
        tabs[i].addEventListener('click', function () {
          for (var j = 0; j < tabs.length; j++) { tabs[j].classList.remove('active'); }
          this.classList.add('active');
          mode = this.getAttribute('data-mode');
          document.getElementById('pane-register').hidden = mode !== 'register';
          document.getElementById('pane-existing').hidden = mode !== 'existing';
        });
      }

      function fail(text) { errEl.textContent = text || ''; }

      function initCaptcha() {
        var field = document.getElementById('captchaField');
        if (CFG.captcha === 'off' || CFG.captcha === '') {
          if (field) { field.style.display = 'none'; }
          return;
        }
        if (CFG.captcha === 'pow') {
          var box = document.getElementById('captchaContainer');
          var b = document.createElement('button');
          b.type = 'button';
          b.className = 'btn-ghost';
          b.id = 'powBtn';
          b.textContent = '点击完成人机验证';
          b.addEventListener('click', function () {
            b.disabled = true;
            b.textContent = '验证中…';
            requestPow().then(function () {
              b.textContent = '已验证';
            }).catch(function () {
              powSolution = null;
              b.disabled = false;
              b.textContent = '验证失败，点击重试';
            });
          });
          box.appendChild(b);
          return;
        }
        if (field) { field.style.display = 'none'; }
      }

      function apiRequest(path, options) {
        return fetch('/v1' + path, options).then(function (res) {
          return res.json().catch(function () { return {}; }).then(function (data) {
            return { code: res.status, data: data };
          });
        });
      }

      function requestPow() {
        return apiRequest('/auth/captcha/challenge').then(function (res) {
          if (res.code !== 200 || !res.data || !res.data.challenge) { throw new Error('challenge failed'); }
          var data = res.data;
          return solvePow(data.challenge, data.difficulty || CFG.powDifficulty || 18).then(function (nonce) {
            powSolution = { token: data.token, nonce: nonce };
            return powSolution;
          });
        });
      }

      function captchaPayload() {
        if (CFG.captcha === 'pow') {
          if (!powSolution) { throw new Error('请先完成人机验证'); }
          return { pow_token: powSolution.token, pow_nonce: powSolution.nonce };
        }
        return {};
      }

      function start() {
        if (busy) { return; }
        fail('');
        var payload = { mode: mode, official_username: document.getElementById('fOfficialUser').value.trim(), official_password: document.getElementById('fOfficialPass').value };
        if (mode === 'register') {
          payload.username = document.getElementById('fUsername').value.trim();
          payload.email = document.getElementById('fEmail').value.trim();
          payload.password = document.getElementById('fPassword').value;
        } else {
          payload.identifier = document.getElementById('fIdentifier').value.trim();
          payload.password = document.getElementById('fExistingPass').value;
        }
        if (!payload.official_username || !payload.official_password) { fail('请填写旧服务器账号与密码'); return; }

        var extra;
        try { extra = captchaPayload(); } catch (e) { fail(e.message); return; }
        for (var k in extra) { payload[k] = extra[k]; }

        busy = true;
        btn.disabled = true;
        btn.textContent = '正在登录旧服务器…';
        fetch('/migrate/start', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        }).then(function (res) {
          return res.json().catch(function () { return {}; }).then(function (data) {
            if (!res.ok || !data.job) {
              throw new Error(data.message || '发起迁移失败');
            }
            return data;
          });
        }).then(function (data) {
          jobId = data.job;
          window.__MIG_STATE = data.account || '';
          setup.hidden = true;
          progress.hidden = false;
          history.replaceState(null, '', '/migrate?job=' + encodeURIComponent(jobId));
          poll();
          if (pollTimer) { clearInterval(pollTimer); }
          pollTimer = setInterval(poll, 10000);
        }).catch(function (e) {
          fail(e.message);
          btn.disabled = false;
          btn.textContent = '开始迁移';
        }).then(function () { busy = false; });
      }

      function statLine(stats) {
        var items = [];
        function add(label, key, suffix) {
          var v = stats[key];
          if (v === undefined || v === null || v === 0) { return; }
          items.push('<span>' + label + ' <b>' + v + '</b>' + (suffix || '') + '</span>');
        }
        add('好友', 'friends', ' 位');
        add('私聊消息', 'direct_messages', ' 条');
        add('群聊', 'groups', ' 个');
        add('群消息', 'group_messages', ' 条');
        add('朋友圈', 'moments', ' 条');
        add('朋友圈评论', 'moment_comments', ' 条');
        add('占位账号', 'shadow_users', ' 个');
        add('图片', 'media_files', ' 张');
        add('图片失败', 'media_failed', ' 张');
        add('消息内媒体未下载', 'media_skipped', ' 条');
        return items.join('');
      }

      function render(job) {
        document.getElementById('migStage').textContent = job.stage || '处理中';
        document.getElementById('migPercent').textContent = (job.percent || 0) + '%';
        document.getElementById('migBarFill').style.width = Math.min(100, job.percent || 0) + '%';
        document.getElementById('migStats').innerHTML = statLine(job.stats || {});

        var list = document.getElementById('migEvents');
        var html = '';
        var events = job.events || [];
        for (var i = 0; i < events.length; i++) {
          var ev = events[i];
          html += '<li class="' + (ev.level || 'info') + '"><time>' + (ev.time || '') + '</time><span class="stage">' + (ev.stage || '') + '</span><span class="msg">' + escapeHtml(ev.message || '') + '</span></li>';
        }
        list.innerHTML = html;
        if (events.length) { list.scrollTop = list.scrollHeight; }

        var banner = document.getElementById('migBanner');
        var hint = document.getElementById('migHint');
        if (job.status === 'done') {
          banner.className = 'mig-done';
          banner.textContent = '迁移已完成。';
          hint.textContent = '已停止刷新';
          if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        } else if (job.status === 'failed') {
          banner.className = 'mig-failed';
          banner.textContent = '迁移中断：' + (job.error || '未知错误');
          hint.textContent = '可以修正后重新发起迁移，已导入的数据会保留';
          if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        } else {
          banner.className = '';
          banner.textContent = '';
        }
      }

      function escapeHtml(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
      }

      function poll() {
        if (!jobId) { return; }
        fetch('/migrate/status?job=' + encodeURIComponent(jobId), { headers: { 'Accept': 'application/json' } })
          .then(function (res) { return res.json(); })
          .then(function (data) {
            if (data && data.job) { render(data.job); }
          }).catch(function () {});
      }

      btn.addEventListener('click', start);
      initCaptcha();

      var params = new URLSearchParams(location.search);
      var existing = params.get('job');
      if (existing) {
        jobId = existing;
        setup.hidden = true;
        progress.hidden = false;
        poll();
        pollTimer = setInterval(poll, 10000);
      }
    })();
  </script>
</body>
</html>
`

func renderMigratePage(cfg config.Config) string {
	payload := map[string]interface{}{
		"captcha":       cfg.CaptchaMode,
		"powDifficulty": cfg.PowDifficulty,
		"sourceHost":    cfg.MigrateSourceHost,
		"allowClaim":    cfg.MigrateAllowClaim,
		"includeGroups": cfg.MigrateIncludeGroups,
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		raw = []byte(`{"captcha":"off","powDifficulty":18}`)
	}
	out := strings.ReplaceAll(appMigrateHTML, "__MIG_CFG_JSON__", string(raw))
	return injectPowJS(out)
}
