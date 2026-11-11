package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"

	"metrochat/internal/config"
)

// appForgotPasswordHTML 官网找回密码页（/forgot-password）。
// 人机验证方式随 CAPTCHA_MODE 变化，未开启邮箱验证时不会渲染本页。
const appForgotPasswordHTML = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>旧聊 · 找回密码</title>
  <link rel="stylesheet" href="/app-assets/app.css" />
  <!--__CAPTCHA_SCRIPT__-->
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

  <script>window.__REG_CFG = __REG_CFG_JSON__;</script>
  <script>
    (function () {
      'use strict';
      var API = '/v1';
      var form = document.getElementById('resetForm');
      var errEl = document.getElementById('resetError');
      var btnSendCode = document.getElementById('btnSendCode');
      var btnReset = document.getElementById('btnReset');
      var sendCodeTimer = null;
      var CFG = window.__REG_CFG || { captcha: 'off', emailVerify: true, powDifficulty: 18 };
      var powSolution = null;
      var humanVerified = false;   // 本次会话已通过人机验证（发码成功即视为已验证）
      var geetestObj = null;
      window.__geetest = null;

      /*__POW_JS__*/

      function setPowHint(text, disabled) {
        var btn = document.getElementById('powBtn');
        if (btn) { btn.textContent = text; btn.disabled = !!disabled; }
      }

      function initPow() {
        var box = document.getElementById('captchaContainer');
        if (!box) { return; }
        box.innerHTML = '<button type="button" class="btn-ghost" id="powBtn" style="width:100%;height:44px;">点击完成人机验证</button>';
        document.getElementById('powBtn').addEventListener('click', function () {
          if (powSolution) { return; }
          ensurePow().then(function () { showOk('人机验证通过'); }).catch(function () { showErr('人机验证失败，请重试'); });
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

      function ensurePow() {
        if (CFG.captcha !== 'pow') { return Promise.resolve(); }
        if (powSolution) { return Promise.resolve(); }
        setPowHint('验证中…', true);
        return requestPow().then(function () {
          setPowHint('已验证', true);
        }).catch(function () {
          powSolution = null;
          setPowHint('验证失败，点击重试', false);
          throw new Error('pow failed');
        });
      }

      function captchaPayload() {
        if (CFG.captcha === 'pow' && powSolution) {
          return { pow_token: powSolution.token, pow_nonce: powSolution.nonce };
        }
        var v = window.__geetest;
        if (CFG.captcha === 'geetest' && v) {
          return {
            geetest_lot_number: v.lot_number,
            geetest_captcha_output: v.captcha_output,
            geetest_pass_token: v.pass_token,
            geetest_gen_time: v.gen_time
          };
        }
        return {};
      }

      function merge(target, extra) {
        for (var k in extra) { if (Object.prototype.hasOwnProperty.call(extra, k)) { target[k] = extra[k]; } }
        return target;
      }

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
            captchaId: CFG.geetestId,
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
        powSolution = null;
        if (CFG.captcha === 'pow') { setPowHint('点击完成人机验证', false); }
        window.__geetest = null;
        try { if (geetestObj) geetestObj.refresh(); } catch (e) {}
        try { if (geetestObj) geetestObj.showCaptcha(); } catch (e) {}
      }

      function initCaptcha() {
        if (CFG.captcha === 'pow') { initPow(); return; }
        if (CFG.captcha === 'geetest') { initGeetest(); }
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

      // 发送邮箱验证码（带人机验证参数；username 非空时服务端按 reset 用途发码）
      btnSendCode.addEventListener('click', function () {
        if (sendCodeTimer) return;
        var username = form.elements.username.value.trim();
        var email = form.elements.email.value.trim();
        if (!username) { showErr('请先填写用户名'); return; }
        if (!email) { showErr('请先填写邮箱'); return; }

        btnSendCode.disabled = true;
        ensurePow().then(function () {
          return apiRequest('/auth/email/send', {
            method: 'POST',
            body: merge({ email: email, username: username }, captchaPayload())
          });
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
        }).catch(function () {
          showErr('人机验证失败，请重试');
          btnSendCode.disabled = false;
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
        if (CFG.captcha !== 'off' && !humanVerified) {
          var ready = CFG.captcha === 'pow' ? !!powSolution : !!window.__geetest;
          if (!ready) { showErr('请先完成人机验证并获取验证码'); return; }
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

      initCaptcha();
    })();
  </script>
</body>
</html>
`

// appForgotPasswordDisabledHTML 在未开启邮箱验证时展示，
// 因为重置密码必须依赖邮箱验证码，没有这条链路就无法安全地确认身份。
const appForgotPasswordDisabledHTML = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>旧聊 · 找回密码</title>
  <link rel="stylesheet" href="/app-assets/app.css" />
</head>
<body>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <h1>旧聊</h1>
        <p class="auth-subtitle">OldChat · 找回密码</p>
      </div>
      <p class="auth-error" style="color:var(--text);text-align:left;line-height:1.6;">
        本站未开启邮箱验证，无法自助重置密码。请联系服务器管理员处理。
      </p>
      <p class="auth-footer"><a href="/app/">返回登录</a> · <a href="/register">去注册</a></p>
    </div>
  </div>
</body>
</html>
`

// handleForgotPasswordPage 返回官网找回密码页（/forgot-password）。
// 未开启邮箱验证时直接展示提示页，因为重置密码必须依赖验证码确认身份。
func (a *API) handleForgotPasswordPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if !a.cfg.EmailVerify {
		_, _ = io.WriteString(w, appForgotPasswordDisabledHTML)
		return
	}
	_, _ = io.WriteString(w, renderForgotPasswordPage(a.cfg))
}

// renderForgotPasswordPage 注入前端配置并填入共用的人机验证脚本。
func renderForgotPasswordPage(cfg config.Config) string {
	payload := map[string]interface{}{
		"captcha":       cfg.CaptchaMode,
		"emailVerify":   cfg.EmailVerify,
		"powDifficulty": cfg.PowDifficulty,
	}
	if cfg.CaptchaMode == config.CaptchaModeGeetest {
		payload["geetestId"] = cfg.GeetestID
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		raw = []byte(`{"captcha":"off","emailVerify":true,"powDifficulty":18}`)
	}
	captchaScript := ""
	if cfg.CaptchaMode == config.CaptchaModeGeetest {
		captchaScript = `<script src="https://static.geetest.com/v4/gt4.js"></script>`
	}
	out := strings.ReplaceAll(appForgotPasswordHTML, "__REG_CFG_JSON__", string(raw))
	out = injectPowJS(out)
	return strings.ReplaceAll(out, "<!--__CAPTCHA_SCRIPT__-->", captchaScript)
}
