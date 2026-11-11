/**
 * OldChat Admin 2.0 - Core Engine (工具、API客户端、主题与弹窗)
 */
(function (global) {
  'use strict';

  var state = {
    currentTab: 'dashboard',
    theme: localStorage.getItem('admin_theme') || 'dark',
    stats: {},
    cloudConfig: {},
    isShowingLogin: false
  };

  function $(sel, parent) { return (parent || document).querySelector(sel); }
  function $$(sel, parent) { return Array.prototype.slice.call((parent || document).querySelectorAll(sel)); }

  function escapeHtml(str) {
    if (!str && str !== 0) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function formatDate(raw) {
    if (!raw) return '—';
    try {
      var d = new Date(raw);
      if (isNaN(d.getTime())) return String(raw);
      var Y = d.getFullYear();
      var M = ('0' + (d.getMonth() + 1)).slice(-2);
      var D = ('0' + d.getDate()).slice(-2);
      var h = ('0' + d.getHours()).slice(-2);
      var m = ('0' + d.getMinutes()).slice(-2);
      return Y + '-' + M + '-' + D + ' ' + h + ':' + m;
    } catch (e) {
      return String(raw);
    }
  }

  function renderAllIcons() {
    $$('[data-icon]').forEach(function (el) {
      var name = el.getAttribute('data-icon');
      var sz = parseInt(el.getAttribute('data-size') || '18', 10);
      if (global.AdminIcons && global.AdminIcons.get) {
        el.innerHTML = global.AdminIcons.get(name, '', sz);
      }
    });
  }

  function toast(msg, type) {
    type = type || 'info';
    var container = $('#toast-container');
    if (!container) return;
    var el = document.createElement('div');
    el.className = 'toast';
    var iconName = type === 'success' ? 'check' : (type === 'error' ? 'alert' : 'info');
    var iconHtml = (global.AdminIcons && global.AdminIcons.get) ? global.AdminIcons.get(iconName, '', 20) : '';
    el.innerHTML = iconHtml + '<span>' + escapeHtml(msg) + '</span>';
    container.appendChild(el);
    setTimeout(function () {
      el.style.opacity = '0';
      el.style.transform = 'translateY(-10px)';
      el.style.transition = 'all 0.25s ease';
      setTimeout(function () { el.remove(); }, 250);
    }, 3000);
  }

  function api(url, opts) {
    opts = opts || {};
    opts.headers = opts.headers || {};
    opts.credentials = 'include';
    if (opts.body && typeof opts.body === 'object' && !(opts.body instanceof FormData)) {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(opts.body);
    }
    return fetch(url, opts).then(function (res) {
      if (res.status === 401) {
        promptLoginModal();
        throw new Error('UNAUTHORIZED');
      }
      return res.json().then(function (data) {
        if (!res.ok || (data && data.code && data.code !== 200 && data.code !== 0)) {
          throw new Error((data && (data.error || data.message)) || ('HTTP ' + res.status));
        }
        return data.data !== undefined ? data.data : data;
      });
    });
  }

  function promptLoginModal() {
    if (state.isShowingLogin) return;
    state.isShowingLogin = true;
    var content = '<p style="margin-bottom:12px;">当前管理会话已失效或未登录，请输入管理密钥重新授权：</p>' +
      '<div><input type="password" id="inlineSecretInput" class="input" placeholder="输入管理密钥" autofocus></div>';
    var footer = '<button class="btn btn-primary" id="btnInlineLogin">立即验证登录</button>';
    showModal('管理身份验证', content, footer);

    var btn = $('#btnInlineLogin');
    var input = $('#inlineSecretInput');
    var doLogin = function () {
      var secret = input ? input.value.trim() : '';
      if (!secret) { toast('请输入管理密钥', 'error'); return; }
      api('/v1/admin/login', { method: 'POST', body: { secret: secret } }).then(function () {
        state.isShowingLogin = false;
        hideModal();
        toast('登录验证成功！', 'success');
        if (global.AdminApp && global.AdminApp.switchTab) {
          global.AdminApp.switchTab(state.currentTab || 'dashboard');
        }
      }).catch(function (e) {
        toast('密钥错误: ' + e.message, 'error');
      });
    };
    if (btn) btn.onclick = doLogin;
    if (input) input.onkeydown = function (e) { if (e.keyCode === 13) doLogin(); };
  }

  function showModal(title, bodyHtml, footerHtml) {
    var m = $('#globalModal');
    var c = $('#globalModalContent');
    if (!m || !c) return;
    c.innerHTML = '<div class="modal-header"><h3 class="modal-title">' + escapeHtml(title) + '</h3><button class="btn btn-sm" id="btnModalClose">✕</button></div>' +
      '<div class="modal-body">' + bodyHtml + '</div>' +
      (footerHtml ? '<div class="modal-footer">' + footerHtml + '</div>' : '');
    m.classList.add('active');
    var closeBtn = $('#btnModalClose');
    if (closeBtn) closeBtn.onclick = hideModal;
  }

  function hideModal() {
    var m = $('#globalModal');
    if (m) m.classList.remove('active');
    state.isShowingLogin = false;
  }

  function applyTheme(theme) {
    state.theme = theme;
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('admin_theme', theme);
    var iconEl = $('#themeIcon');
    if (iconEl && global.AdminIcons) {
      iconEl.innerHTML = global.AdminIcons.get(theme === 'dark' ? 'sun' : 'moon', '', 18);
    }
  }

  global.AdminCore = {
    state: state,
    $: $,
    $$: $$,
    escapeHtml: escapeHtml,
    formatDate: formatDate,
    renderAllIcons: renderAllIcons,
    toast: toast,
    api: api,
    showModal: showModal,
    hideModal: hideModal,
    closeModal: hideModal,
    applyTheme: applyTheme
  };
})(window);
