import {escapeHTML as esc, safeURL, label, keyOf, timeText, dateText, mine} from './model.js';
export const $ = id => document.getElementById(id);
const paths = {
  chat:'M4 4h16v12H9l-5 4V4z M8 8h8M8 12h5', users:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.8 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  grid:'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z', music:'M9 18V5l12-2v13 M9 18a3 3 0 1 1-3-3c1.7 0 3 1.3 3 3 M21 16a3 3 0 1 1-3-3c1.7 0 3 1.3 3 3',
  plus:'M12 5v14M5 12h14',search:'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  'user-plus':'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M20 7v6M17 10h6',
  refresh:'M20 7v5h-5M4 17v-5h5 M5 7a8 8 0 0 1 13-2l2 3M4 16l2 3a8 8 0 0 0 13-2',
  back:'M19 12H5M12 5l-7 7 7 7', info:'M12 11v6M12 7h.01 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  image:'M3 3h18v18H3zM3 17l6-6 4 4 3-3 5 5M16 7h.01', smile:'M8 14s1 3 4 3 4-3 4-3M8 8h.01M16 8h.01 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  send:'M22 2L9 15M22 2l-7 20-6-7-7-6 20-7z'
};
export function initIcons(root = document) {
  root.querySelectorAll('[data-icon]').forEach(el => { el.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[el.dataset.icon] || paths.chat}"/></svg>`; });
}
export function avatarHTML(item) {
  const url = safeURL(item.avatar_url);
  return url ? `<img src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : esc(Array.from(label(item)).slice(0,1).join(''));
}
export function imagesFallback(root) {
  root.querySelectorAll('.avatar img,.self-avatar img').forEach(img => {
    img.onerror = () => { img.replaceWith(document.createTextNode('聊')); };
  });
}
let toastTimer;
export function toast(text) {
  $('toast').textContent = text; $('toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4500);
}
export function connection(status) {
  const labels = {connecting:'正在建立实时连接',online:'实时连接正常',reconnecting:'实时重连中 · 定时同步',offline:'实时不可用 · 定时同步'};
  $('connection-label').textContent = navigator.onLine ? labels[status] || status : '网络离线 · 等待恢复';
  $('connection-dot').className = 'status-dot ' + (status === 'online' ? 'online' : 'offline');
}
export function renderList(state) {
  const query = $('conversation-search').value.trim().toLowerCase();
  const items = state.chats.filter(c => (state.tab === 'all' || state.tab === c.type)
    && (state.filter !== 'unread' || c.unread > 0)
    && [c.name,c.id,c.ncuid].join(' ').toLowerCase().includes(query)).sort((a,b) => b.time-a.time);
  $('unread-total').textContent = state.chats.reduce((n,c) => n+c.unread, 0);
  const html = items.length ? items.map(c => `<button class="conversation-item ${keyOf(c) === keyOf(state.current) ? 'active' : ''}" data-chat="${esc(keyOf(c))}" aria-current="${keyOf(c) === keyOf(state.current) ? 'true' : 'false'}"><span class="avatar ${c.type}">${avatarHTML(c)}</span><span class="conversation-info"><span class="conversation-top"><strong>${esc(c.name)}</strong><time>${timeText(c.time)}</time></span><span class="conversation-bottom"><p>${esc(c.preview || (c.type === 'group' ? `${c.member_count || 0} 位成员 · 群聊` : '点开，聊两句'))}</p>${c.unread ? `<span class="badge">${c.unread > 99 ? '99+' : c.unread}</span>` : ''}</span></span></button>`).join('')
    : `<div class="empty-list">${query ? '没有找到匹配的会话' : state.filter === 'unread' ? '未读都看完啦，享受这一刻。' : '这里还很安静。<br>点右上角 ＋ 添加好友或加入群聊。'}</div>`;
  const list = $('conversation-list');
  if (list._html !== html) {
    const template = document.createElement('template'); template.innerHTML = html;
    const old = new Map([...list.children].map(el => [el.dataset.chat,el]));
    const nodes = [...template.content.children].map(el => {
      const previous = old.get(el.dataset.chat);
      if (previous && previous._html === el.outerHTML) return previous;
      el._html = el.outerHTML; return el;
    });
    list.replaceChildren(...nodes); list._html = html;
    imagesFallback(list);
  }
}
export function renderHeader(chat) {
  $('welcome').hidden = true; $('chat-panel').hidden = false; $('workspace').classList.add('chat-open');
  $('chat-avatar').innerHTML = avatarHTML(chat); $('chat-avatar').className = 'avatar ' + chat.type;
  $('chat-name').textContent = chat.name;
  $('chat-subtitle').textContent = chat.type === 'group' ? `${chat.member_count || 0} 位成员 · ${chat.global_mute ? '全员禁言中' : '群聊'}` : (chat.is_online ? '当前在线' : '私聊 · 消息将在对方上线后送达');
  imagesFallback($('chat-avatar'));
}
function contentHTML(m) {
  if (m.burn_after_seconds > 0) return '<div class="message-text muted">阅后即焚消息，请在 Android 客户端查看。</div>';
  if (m.msg_type === 'recall') return '<div class="message-text muted">消息已撤回</div>';
  const url = safeURL(m.media_url);
  if (['image','voice','video'].includes(m.msg_type) && !url) return '<div class="message-text muted">媒体地址不可用，请在客户端查看。</div>';
  if (m.msg_type === 'image' && url) return `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer" class="image-open"><img class="message-media" src="${esc(safeURL(m.thumb_url) || url)}" alt="图片消息" loading="lazy" referrerpolicy="no-referrer"></a>`;
  if (m.msg_type === 'voice' && url) return `<audio controls preload="none" src="${esc(url)}"></audio>`;
  if (m.msg_type === 'video' && url) return `<video class="message-media" controls preload="none" playsinline src="${esc(url)}"></video>`;
  if (['resource','file'].includes(m.msg_type)) return `<div class="message-text">${esc(m.body || '文件消息')}</div>${url ? `<a class="media-link" href="${esc(url)}" target="_blank" rel="noopener noreferrer">查看附件 ↗</a>` : '<div class="muted">请在客户端查看此资源</div>'}`;
  return `<div class="message-text">${esc(m.body || `[${m.msg_type || '未知'}消息]`)}</div>`;
}
function messageHTML(m, state) {
  const own = mine(m, state.user);
  const status = m.localState === 'failed' ? `<button data-retry="${esc(m.id)}">发送未确认 · 手动重试</button>`
    : m.localState === 'sending' ? '发送中…' : own ? (m.read_at ? '<span class="read">已读 ✓✓</span>' : '已发送 ✓') : '';
  return `<div class="message-row ${own ? 'outgoing' : 'incoming'}" data-id="${esc(m.id)}"><div class="message-stack">${!own && state.current.type === 'group' ? `<div class="message-sender">${esc(m.from_name || m.from_ncuid || m.from_uid)}</div>` : ''}<div class="bubble">${contentHTML(m)}<div class="message-meta"><time>${timeText(m.created_at)}</time>${status}</div></div></div></div>`;
}
export function renderMessages(state, mode = 'preserve') {
  const scroller = $('message-scroll');
  const bottom = scroller.scrollHeight-scroller.scrollTop-scroller.clientHeight < 90;
  const height = scroller.scrollHeight, top = scroller.scrollTop;
  const container = $('messages');
  const existing = new Map([...container.querySelectorAll('.message-row')].map(el => [el.dataset.id,el]));
  const fragment = document.createDocumentFragment();
  let date = '';
  for (const m of state.messages) {
    const day = dateText(m.created_at);
    if (day !== date) { const divider = document.createElement('div'); divider.className = 'message-day'; divider.textContent = day; fragment.append(divider); date = day; }
    const html = messageHTML(m,state);
    let node = existing.get(String(m.id));
    if (!node || node._html !== html) {
      const template = document.createElement('template'); template.innerHTML = html;
      node = template.content.firstElementChild; node._html = html;
    }
    fragment.append(node);
  }
  container.replaceChildren(fragment);
  if (!state.messages.length) container.innerHTML = '<div class="empty-messages">还没有消息。用一句「你好」开始吧。</div>';
  container.querySelectorAll('.message-media').forEach(media => {
    if (media.tagName === 'IMG') media.onerror = () => { media.alt = '图片暂时不可用，点击尝试打开原图'; };
    media.onload = () => { if (mode === 'bottom' || bottom) scroller.scrollTop = scroller.scrollHeight; };
  });
  if (mode === 'older') scroller.scrollTop = top + scroller.scrollHeight-height;
  else if (mode === 'bottom' || bottom) { scroller.scrollTop = scroller.scrollHeight; $('jump-latest').hidden = true; }
  else { scroller.scrollTop = top; $('jump-latest').hidden = false; }
  $('load-older').hidden = !state.hasMore;
}
let modalFocus;
export function openModal(title, html) {
  modalFocus = document.activeElement; $('modal-title').textContent = title; $('modal-body').innerHTML = html;
  if (!$('modal').open) $('modal').showModal();
  imagesFallback($('modal-body'));
}
export function closeModal() { $('modal').close(); modalFocus?.focus(); }
export function initUI() {
  initIcons();
  let theme;
  try { theme = localStorage.getItem('oldchat_web_theme'); } catch {}
  document.documentElement.dataset.theme = theme || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  document.querySelectorAll('.theme-toggle').forEach(button => button.onclick = () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('oldchat_web_theme',next); } catch {}
  });
  $('modal-close').onclick = closeModal;
  $('modal').addEventListener('click', e => { if (e.target === $('modal')) { const r = $('modal').getBoundingClientRect(); if (e.clientX<r.left || e.clientX>r.right || e.clientY<r.top || e.clientY>r.bottom) closeModal(); } });
  $('reveal-password').onclick = () => {
    const reveal = $('password').type === 'password'; $('password').type = reveal ? 'text' : 'password';
    $('reveal-password').textContent = reveal ? '隐藏' : '显示'; $('reveal-password').setAttribute('aria-pressed',String(reveal));
  };
}
