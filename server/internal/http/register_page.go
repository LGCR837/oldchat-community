package httpapi

import (
	"encoding/json"
	"strings"

	"metrochat/internal/config"
)

// appRegisterHTML 官网注册页（/register）。
// 人机验证与邮箱验证码都是可配置的：渲染时用 renderRegisterPage 注入前端配置，
// 未开启的字段由脚本自动隐藏，不加载任何第三方脚本。
const appRegisterHTML = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>旧聊 · 注册</title>
  <link rel="stylesheet" href="/app-assets/app.css" />
  <!--__CAPTCHA_SCRIPT__-->
</head>
<body>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <h1>旧聊</h1>
        <p class="auth-subtitle">OldChat · 注册</p>
      </div>

      <form id="registerForm" autocomplete="off">
        <label class="field">
          <span>邮箱</span>
          <input type="email" name="email" placeholder="常用邮箱" required />
        </label>
        <label class="field">
          <span>用户名</span>
          <input type="text" name="username" placeholder="设置用户名（小写字母/数字）" required />
        </label>
        <label class="field">
          <span>密码</span>
          <input type="password" name="password" placeholder="至少 8 位" required minlength="8" />
        </label>

        <!-- 人机验证：按 CAPTCHA_MODE 由脚本渲染（自建 PoW / 极验） -->
        <div class="field captcha-field">
          <span>人机验证</span>
          <div id="captchaContainer"></div>
        </div>

        <div class="field-row email-code-row">
          <label class="field email-code-field">
            <span>邮箱验证码</span>
            <input type="text" name="email_code" placeholder="输入邮件中的 6 位码" required maxlength="6" />
          </label>
          <button type="button" class="btn-ghost" id="btnSendCode">发送验证码</button>
        </div>

        <div class="field-terms" style="margin:12px 0 16px;font-size:13px;color:var(--text-muted);display:flex;align-items:flex-start;gap:8px;line-height:1.45;text-align:left;">
          <input type="checkbox" name="agree_terms" id="agreeTerms" required style="margin-top:3px;width:16px;height:16px;accent-color:var(--primary);cursor:pointer;" />
          <label for="agreeTerms" style="cursor:pointer;color:var(--text);">我已认真阅读并完全同意 <a href="javascript:void(0)" id="openTermsModal" style="color:var(--accent);text-decoration:underline;">《用户服务协议与免责声明》</a></label>
        </div>

        <button type="submit" class="btn-primary" id="btnRegister">注 册</button>
      </form>
      <p class="auth-error" id="registerError"></p>
      <p class="auth-footer"><a href="/app/">返回登录</a> · <a href="/forgot-password">忘记密码？</a> · <a href="/migrate">从旧服务器迁移</a></p>
    </div>
  </div>

  <!-- 邮箱已注册弹窗 -->
  <div class="modal-mask" id="regModal" style="display:none">
    <div class="modal-card">
      <p class="modal-title">该邮箱已注册</p>
      <p class="modal-text">该邮箱已存在 Oldchat 账号，请选择操作：</p>
      <div class="modal-actions">
        <a class="btn-primary" href="/app/">去登录</a>
        <a class="btn-ghost" href="/forgot-password">找回密码</a>
      </div>
    </div>
  </div>

  <!-- 用户协议与免责声明弹窗 -->
  <div class="modal-mask" id="termsModal" style="display:none;z-index:999;">
    <div class="modal-card" style="max-width:620px;max-height:82vh;display:flex;flex-direction:column;text-align:left;padding:24px;">
      <p class="modal-title" style="font-size:16px;font-weight:700;margin-bottom:12px;border-bottom:1px solid var(--border);padding-bottom:10px;">《OldChat（旧聊）用户服务协议与免责声明》</p>
      <div style="flex:1;overflow-y:auto;font-size:13px;line-height:1.65;color:var(--text);padding-right:8px;margin-bottom:16px;">
        <p style="color:#f85149;font-weight:700;margin-bottom:10px;">【特别重要提示】OldChat目前为个人开发者维护的免费、非营利性公益项目，服务器主要位于香港等境外地区。注册/使用即视为已充分阅读并完全接受本协议全部条款（版本：2026年8月16日）。</p>
        <p><b>一、免费公益与技术服务性质</b>：OldChat为免费非营利项目，不向用户收取使用费，不提供付费交易。主要提供即时通信中立技术功能，用户对其发送、上传的全部内容承担独立全部法律责任，运营者不作内容背书。</p>
        <p><b>二、服务地区与跨地域访问</b>：主要面向香港及中国大陆以外地区提供服务，不以中国大陆作为主动推广或定向经营市场。用户应自行确认所在地区使用本服务的合法性。</p>
        <p><b>三、禁止违法违规行为</b>：严禁从事诈骗、洗钱、赌博、传销、毒品、枪爆等违法犯罪；严禁淫秽色情与危害未成年人内容；严禁侵犯他人人身权、隐私与个人资料；严禁盗版与知识产权侵权；严禁网络攻击与自动化系统滥用。</p>
        <p><b>四、内容与账号处置</b>：运营者有权依据违规情况、投诉或安全需要采取删除、屏蔽、限制功能、暂停或终止账号等措施。</p>
        <p><b>五、可用性与数据自行备份</b>：服务按“现状”提供，不保证永不中断。<b>用户应自行备份重要聊天记录与文件</b>。因不可抗力或第三方原因导致的异常，运营者在法定最大范围内免责。</p>
        <p><b>六、责任限制与追偿</b>：在法律允许最大范围内排除非直接损失；若因用户违法侵权导致运营者遭受损失，运营者有权依法追偿合理损失与维权费用。</p>
        <p><b>七、隐私保护与执法协助</b>：遵守《隐私政策》合理处理数据；收到合法执法请求将在法律要求范围内提供必要协助。</p>
        <p><b>八、法律适用与管辖</b>：本协议原则上适用香港特别行政区法律，争议由香港特别行政区有管辖权的法院处理。</p>
        <p style="margin-top:10px;"><a href="/terms" target="_blank" style="color:var(--accent);text-decoration:underline;">在新页面查看完整18条法律协议 &rarr;</a></p>
      </div>
      <div class="modal-actions" style="margin-top:0;justify-content:flex-end;gap:12px;">
        <button type="button" class="btn-ghost" id="btnCloseTerms">关闭</button>
        <button type="button" class="btn-primary" id="btnAcceptTerms">我已阅读并同意</button>
      </div>
    </div>
  </div>

  <script>window.__REG_CFG = __REG_CFG_JSON__;</script>
  <script>
    (function () {
      'use strict';
      var API = '/v1';
      var form = document.getElementById('registerForm');
      var errEl = document.getElementById('registerError');
      var btnSendCode = document.getElementById('btnSendCode');
      var btnRegister = document.getElementById('btnRegister');
      var termsModal = document.getElementById('termsModal');
      var openTermsModal = document.getElementById('openTermsModal');
      var btnCloseTerms = document.getElementById('btnCloseTerms');
      var btnAcceptTerms = document.getElementById('btnAcceptTerms');
      var agreeTermsCheckbox = document.getElementById('agreeTerms');
      var sendCodeTimer = null;
      var CFG = window.__REG_CFG || { captcha: 'off', emailVerify: false, powDifficulty: 18 };
      var powSolution = null;      // { token, nonce }，本站自建人机验证的解
      var humanVerified = false;   // 本次会话已通过人机验证（发码成功即视为已验证）
      var geetestObj = null;
      window.__geetest = null;

      // 弹窗事件
      if (openTermsModal && termsModal) {
        openTermsModal.addEventListener('click', function (e) {
          e.preventDefault();
          termsModal.style.display = 'flex';
        });
      }
      if (btnCloseTerms && termsModal) {
        btnCloseTerms.addEventListener('click', function () {
          termsModal.style.display = 'none';
        });
      }
      if (btnAcceptTerms && termsModal && agreeTermsCheckbox) {
        btnAcceptTerms.addEventListener('click', function () {
          agreeTermsCheckbox.checked = true;
          termsModal.style.display = 'none';
          if (errEl && errEl.textContent.indexOf('用户服务协议') !== -1) {
            errEl.textContent = '';
          }
        });
      }

      // ---- 人机验证：自建 PoW / 极验，由 renderRegisterPage 注入的配置决定 ----
      /*__POW_JS__*/

      function setPowHint(text, disabled) {
        var btn = document.getElementById('powBtn');
        if (btn) { btn.textContent = text; btn.disabled = !!disabled; }
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

      // ensurePow 保证本次提交前有可用的人机验证解；已解过则直接复用。
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

      function initPow() {
        var box = document.getElementById('captchaContainer');
        if (!box) { return; }
        box.innerHTML = '<button type="button" class="btn-ghost" id="powBtn" style="width:100%;height:44px;">点击完成人机验证</button>';
        document.getElementById('powBtn').addEventListener('click', function () {
          if (powSolution) { return; }
          ensurePow().then(function () { showOk('人机验证通过'); }).catch(function () { showErr('人机验证失败，请重试'); });
        });
      }

      // GeeTest 4.0（仅在 CAPTCHA_MODE=geetest 时启用）
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
              showErr('人机验证失败，请重试');
            });
            captchaObj.onClose(function () {
              window.__geetest = null;
            });
          });
        } catch (e) {
          showErr('人机验证初始化失败，请刷新重试');
        }
      }

      function initCaptcha() {
        if (CFG.captcha === 'pow') { initPow(); return; }
        if (CFG.captcha === 'geetest') { initGeetest(); }
      }

      // 本站未开启的字段直接隐藏，避免用户看到无法使用的输入框。
      function applyConfig() {
        if (!CFG.emailVerify) {
          var codeRow = form.querySelector('.email-code-row');
          if (codeRow) { codeRow.style.display = 'none'; }
        }
        if (CFG.captcha === 'off') {
          var captchaField = form.querySelector('.captcha-field');
          if (captchaField) { captchaField.style.display = 'none'; }
        }
      }

      function apiRequest(path, options) {
        options = options || {};
        var headers = { 'Content-Type': 'application/json' };
        var body = options.body ? JSON.stringify(options.body) : null;
        return fetch(API + path, {
          method: options.method || 'GET',
          headers: headers,
          body: body
        }).then(function (resp) {
          return resp.json().catch(function () { return {}; }).then(function (data) {
            return { code: resp.status, data: data };
          });
        });
      }

      function showErr(msg) {
        if (errEl) { errEl.style.color = 'var(--error)'; errEl.textContent = msg || ''; }
      }
      function showOk(msg) {
        if (errEl) { errEl.style.color = 'var(--ok)'; errEl.textContent = msg || ''; }
      }

      // 提交后重置验证，重试时重新验证
      function resetCaptcha() {
        powSolution = null;
        if (CFG.captcha === 'pow') { setPowHint('点击完成人机验证', false); }
        window.__geetest = null;
        try { if (geetestObj) geetestObj.refresh(); } catch (e) {}
      }

      // 组装人机验证参数
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

      if (btnSendCode) {
        btnSendCode.addEventListener('click', function () {
          if (sendCodeTimer) { return; }
          var email = form.elements.email.value.trim();
          if (!email) { showErr('请先填写邮箱'); return; }

          btnSendCode.disabled = true;
          ensurePow().then(function () {
            return apiRequest('/auth/email/send', {
              method: 'POST',
              body: merge({ email: email }, captchaPayload())
            });
          }).then(function (res) {
            resetCaptcha();
            if (res.code === 200) {
              humanVerified = true;
              showOk('验证码已发送，请查收邮箱');
              startCountdown();
            } else if (res.data && res.data.error === 'email_registered') {
              showRegisteredModal(email);
              btnSendCode.disabled = false;
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
      }

      // 邮箱已注册：弹出登录/找回密码入口
      function showRegisteredModal(email) {
        var modal = document.getElementById('regModal');
        if (!modal) { return; }
        modal.style.display = 'flex';
      }

      function startCountdown() {
        var left = 120;
        sendCodeTimer = setInterval(function () {
          left--;
          if (btnSendCode) { btnSendCode.textContent = left + 's 后重发'; }
          if (left <= 0) {
            clearInterval(sendCodeTimer);
            sendCodeTimer = null;
            if (btnSendCode) { btnSendCode.textContent = '发送验证码'; btnSendCode.disabled = false; }
          }
        }, 1000);
      }

      // 注册
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        var email = form.elements.email.value.trim();
        var username = form.elements.username.value.trim();
        var password = form.elements.password.value;
        var emailCode = CFG.emailVerify ? form.elements.email_code.value.trim() : '';
        var agreeTerms = form.elements.agree_terms ? form.elements.agree_terms.checked : false;

        if (!email || !username || !password) { showErr('请填写完整信息'); return; }
        if (CFG.emailVerify && !emailCode) { showErr('请填写邮箱验证码'); return; }
        if (!agreeTerms) { showErr('请先阅读并勾选同意《用户服务协议与免责声明》'); return; }

        btnRegister.disabled = true;
        var body = {
          email: email, username: username, password: password,
          agree_terms: true, platform: 'web', app_version: '1.0.0'
        };
        if (emailCode) { body.email_code = emailCode; }

        // 开启邮箱验证时，验证码本身即人机凭证；否则注册请求自己过人机验证。
        var gate = CFG.emailVerify ? Promise.resolve() : ensurePow();
        gate.then(function () {
          return apiRequest('/auth/web/register', {
            method: 'POST',
            body: merge(body, CFG.emailVerify ? {} : captchaPayload())
          });
        }).then(function (res) {
          resetCaptcha();
          if (res.code >= 200 && res.code < 300) {
            showOk('注册成功，正在跳转登录…');
            setTimeout(function () { window.location.href = '/app/'; }, 1200);
          } else {
            var msg = (res.data && (res.data.error || res.data.message)) || ('注册失败 (' + res.code + ')');
            showErr(msg);
            btnRegister.disabled = false;
          }
        }).catch(function () {
          showErr('人机验证失败，请重试');
          btnRegister.disabled = false;
        });
      });

      applyConfig();
      initCaptcha();
    })();
  </script>
</body>
</html>
`

// renderRegisterPage 按运行配置渲染注册页。
// 人机验证方式与是否开启邮箱验证都随 settings.json / 环境变量变化，
// 页面据此隐藏未启用的字段，并在需要时才加载极验脚本。
func renderRegisterPage(cfg config.Config) string {
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
		raw = []byte(`{"captcha":"off","emailVerify":false,"powDifficulty":18}`)
	}
	captchaScript := ""
	if cfg.CaptchaMode == config.CaptchaModeGeetest {
		captchaScript = `<script src="https://static.geetest.com/v4/gt4.js"></script>`
	}
	out := strings.ReplaceAll(appRegisterHTML, "__REG_CFG_JSON__", string(raw))
	out = injectPowJS(out)
	return strings.ReplaceAll(out, "<!--__CAPTCHA_SCRIPT__-->", captchaScript)
}
