package httpapi

import (
	"io"
	"net/http"
)

// appForgotPasswordHTML 官网找回密码页（/forgot-password，GeeTest 4.0 人机验证）。
// 内嵌于二进制，不依赖 webapp 静态目录。
const appForgotPasswordHTML = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>旧聊 · 找回密码</title>
  <link rel="stylesheet" href="/app-assets/app.css" />
  <!-- GeeTest 4.0 人机验证 -->
  <script src="https://static.geetest.com/v4/gt4.js"></script>
</head>
<body>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <h1>旧聊</h1>
        <p class="auth-subtitle">OldChat · 找回密码</p>
      </div>

      <form id="resetForm" autocomplete="off">
        <label class="field">
          <span>用户名</span>
          <input type="text" name="username" placeholder="账号用户名" required />
        </label>
        <label class="field">
          <span>邮箱</span>
          <input type="email" name="email" placeholder="注册时绑定的邮箱" required />
        </label>

        <!-- GeeTest 4.0：验证通过后 getValidate() 写入 window.__geetest -->
        <div class="field turnstile-field">
          <span>人机验证</span>
          <div id="captchaContainer"></div>
        </div>

        <div class="field-row">
          <label class="field email-code-field">
            <span>邮箱验证码</span>
            <input type="text" name="email_code" placeholder="输入邮件中的 6 位码" required maxlength="6" />
          </label>
          <button type="button" class="btn-ghost" id="btnSendCode">发送验证码</button>
        </div>
        <label class="field">
          <span>新密码</span>
          <input type="password" name="new_password" placeholder="至少 8 位" required minlength="8" />
        </label>
        <button type="submit" class="btn-primary" id="btnReset">重置密码</button>
      </form>
      <p class="auth-error" id="resetError"></p>
      <p class="auth-footer"><a href="/app/">返回登录</a> · <a href="/register">去注册</a></p>
    </div>
  </div>

  <script>
    (function () {
      'use strict';
      var API = '/v1';
      var form = document.getElementById('resetForm');
      var errEl = document.getElementById('resetError');
      var btnSendCode = document.getElementById('btnSendCode');
      var btnReset = document.getElementById('btnReset');
      var sendCodeTimer = null;
      var humanVerified = false;   // 本次会话已通过人机验证（发码成功即视为已验证）
      window.__geetest = null;
      var geetestObj = null;
      var GEETEST_CAPTCHA_ID = '769d069177e132e46eeba07a6210cf3a';

      function showErr(msg) {
        if (errEl) { errEl.style.color = 'var(--error)'; errEl.textContent = msg; }
      }
      function showOk(msg) {
        if (errEl) { errEl.style.color = 'var(--ok)'; errEl.textContent = msg; }
      }

      // GeeTest 4.0 初始化
      function initGeetest() {
        if (typeof window.initGeetest4 !== 'function') {
          showErr('人机验证组件加载失败，请刷新重试');
          return;
        }
        try {
          window.initGeetest4({
            captchaId: GEETEST_CAPTCHA_ID,
            product: 'popup',
            riskType: 'ai',
            nativeButton: { width: '100%', height: '44px' }
          }, function (captchaObj) {
            geetestObj = captchaObj;
            captchaObj.appendTo('#captchaContainer');
            captchaObj.onReady(function () {
              if (errEl) errEl.textContent = '';
            });
            captchaObj.onSuccess(function () {
              var v = captchaObj.getValidate();
              if (v && v.lot_number && v.captcha_output && v.pass_token) {
                window.__geetest = v;
                if (errEl) errEl.textContent = '';
              } else {
                window.__geetest = null;
              }
            });
            captchaObj.onError(function () {
              window.__geetest = null;
              showErr('人机验证出错，请点击按钮重试');
            });
            captchaObj.onClose(function () {
              window.__geetest = null;
            });
          });
        } catch (e) {
          showErr('人机验证初始化失败，请刷新重试');
        }
      }

      function resetCaptcha() {
        window.__geetest = null;
        try { if (geetestObj) geetestObj.refresh(); } catch (e) {}
        try { if (geetestObj) geetestObj.showCaptcha(); } catch (e) {}
      }

      function apiRequest(path, opts) {
        opts = opts || {};
        return new Promise(function (resolve) {
          var xhr = new XMLHttpRequest();
          xhr.open(opts.method || 'GET', API + path, true);
          xhr.setRequestHeader('Content-Type', 'application/json');
          xhr.onload = function () {
            var data = {};
            try { data = JSON.parse(xhr.responseText); } catch (e) {}
            resolve({ code: xhr.status, data: data });
          };
          xhr.onerror = function () {
            resolve({ code: 0, data: {} });
          };
          xhr.send(JSON.stringify(opts.body || {}));
        });
      }

      // 发送邮箱验证码（带 geetest 验证参数；username 非空时服务端按 reset 用途发码）
      btnSendCode.addEventListener('click', function () {
        if (sendCodeTimer) return;
        var username = form.elements.username.value.trim();
        var email = form.elements.email.value.trim();
        var v = window.__geetest;
        if (!username) { showErr('请先填写用户名'); return; }
        if (!email) { showErr('请先填写邮箱'); return; }
        if (!v) { showErr('请先完成人机验证'); return; }

        btnSendCode.disabled = true;
        apiRequest('/auth/email/send', {
          method: 'POST',
          body: {
            email: email,
            username: username,
            geetest_lot_number: v.lot_number,
            geetest_captcha_output: v.captcha_output,
            geetest_pass_token: v.pass_token,
            geetest_gen_time: v.gen_time
          }
        }).then(function (res) {
          resetCaptcha();
          if (res.code === 200) {
            humanVerified = true;
            showOk('验证码已发送，请查收邮箱');
            startCountdown();
          } else {
            var msg = (res.data && (res.data.error || res.data.message)) || ('发送失败 (' + res.code + ')');
            showErr(msg);
            btnSendCode.disabled = false;
          }
        });
      });

      function startCountdown() {
        var left = 120;
        sendCodeTimer = setInterval(function () {
          left--;
          btnSendCode.textContent = left + 's 后重发';
          if (left <= 0) {
            clearInterval(sendCodeTimer);
            sendCodeTimer = null;
            btnSendCode.textContent = '发送验证码';
            btnSendCode.disabled = false;
          }
        }, 1000);
      }

      // 重置密码
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        var username = form.elements.username.value.trim();
        var email = form.elements.email.value.trim();
        var emailCode = form.elements.email_code.value.trim();
        var newPassword = form.elements.new_password.value;
        if (!username || !email || !emailCode || !newPassword) { showErr('请填写完整信息'); return; }
        if (newPassword.length < 8) { showErr('新密码至少 8 位'); return; }
        // 验证码只能通过人机验证后获取，发码成功即视为已通过人机验证。
        if (!humanVerified) {
          var v = window.__geetest;
          if (!v) { showErr('请先完成人机验证并获取验证码'); return; }
        }

        btnReset.disabled = true;
        apiRequest('/auth/password/reset', {
          method: 'POST',
          body: {
            username: username,
            email: email,
            email_code: emailCode,
            new_password: newPassword
          }
        }).then(function (res) {
          if (res.code === 200) {
            showOk('密码已重置，正在跳转登录…');
            setTimeout(function () { window.location.href = '/app/'; }, 1500);
          } else {
            var msg = (res.data && (res.data.error || res.data.message)) || ('重置失败 (' + res.code + ')');
            showErr(msg);
            btnReset.disabled = false;
          }
        });
      });

      initGeetest();
    })();
  </script>
</body>
</html>
`

// handleForgotPasswordPage 返回官网找回密码页（/forgot-password）。
func (a *API) handleForgotPasswordPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, appForgotPasswordHTML)
}
