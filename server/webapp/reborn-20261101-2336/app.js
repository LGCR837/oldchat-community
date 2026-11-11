import {loadAuth,getAuth,saveAuth,request,login,logout} from './api.js';
import {conversation,keyOf,label,preview} from './model.js';
import {$,initUI,renderList,toast,connection,avatarHTML,imagesFallback,closeModal} from './ui.js';
import {createChat} from './chat.js';
import {initContacts} from './contacts.js';
import {connectRealtime} from './realtime.js';
const state = {user:null,chats:[],current:null,messages:[],hasMore:false,tab:'all',filter:'all',epoch:0};
let realtime, poll, eventTimer, syncing = false, listing = false;
const chat = createChat(state,() => renderList(state));
initUI();
function showLogin(message = '') {
  state.epoch++; realtime?.close(); realtime = null; clearTimeout(poll); clearTimeout(eventTimer);
  syncing = false; listing = false; eventTimer = null; chat.reset(); state.user = null; state.chats = [];
  $('conversation-list').replaceChildren(); $('conversation-list')._html = null;
  $('toast').hidden = true; $('workspace').hidden = true; $('auth-screen').hidden = false;
  if ($('modal').open) closeModal();
  $('modal-body').replaceChildren(); $('profile-button').textContent = '我';
  $('conversation-search').value = ''; $('login-error').textContent = message;
}
async function signOut() {
  const pending = logout(); showLogin(); await pending;
}
async function reload() {
  if (listing || !state.user) return;
  listing = true; const epoch = state.epoch;
  $('refresh-list').disabled = true;
  try {
    const [friends,groups] = await Promise.all([request('/friends'),request('/groups/list')]);
    if (epoch !== state.epoch) return;
    const old = new Map(state.chats.map(c => [keyOf(c),c]));
    state.chats = [...(friends.friends || []).map(f => conversation(f,'direct')),...(groups.groups || []).map(g => conversation(g,'group'))]
      .filter(Boolean).map(c => ({...c,preview:old.get(keyOf(c))?.preview || '',time:old.get(keyOf(c))?.time || 0,unread:old.get(keyOf(c))?.unread || 0}));
    if (state.current) {
      const updated = state.chats.find(c => keyOf(c) === keyOf(state.current));
      if (updated) state.current = updated;
    }
    $('list-error').hidden = true; renderList(state);
  } catch (err) {
    if (epoch !== state.epoch) return;
    $('list-error').textContent = err.message+' · 点击刷新重试'; $('list-error').hidden = false;
  } finally { if (epoch === state.epoch) { listing = false; $('refresh-list').disabled = false; } }
}
async function unreadPage(type,epoch) {
  const messages = [], seen = new Set();
  // Bounded bootstrap: a large backlog must not freeze the browser or loop forever.
  for (let offset = 0; offset < 2000; offset += 200) {
    const result = await request(`/${type === 'group' ? 'groups' : 'direct'}/unread`,{method:'POST',body:{limit:200,offset}});
    if (epoch !== state.epoch) return [];
    const rows = result.messages || [];
    let fresh = 0;
    for (const m of rows) if (m?.id && !seen.has(m.id)) { seen.add(m.id); messages.push(m); fresh++; }
    if (rows.length < 200 || !fresh || result.has_more === false) break;
  }
  return messages;
}
async function sync() {
  if (syncing || !state.user || document.hidden || !navigator.onLine) return;
  syncing = true; const epoch = state.epoch;
  try {
    const [direct,groups] = await Promise.all([unreadPage('direct',epoch),unreadPage('group',epoch)]);
    if (epoch !== state.epoch) return;
    state.chats.forEach(c => {c.unread = 0;});
    for (const [type,rows] of [['direct',direct],['group',groups]]) {
      for (const m of rows) {
        const id = type === 'group' ? m.group_id : m.peer_uid || m.from_uid;
        let c = state.chats.find(c => c.type === type && (c.id === id || (type === 'direct' && c.ncuid && c.ncuid === m.peer_ncuid)));
        if (!c && id) {
          c = conversation(type === 'group' ? {group_id:id,name:'群聊 '+id} : {uid:id,ncuid:m.peer_ncuid,display_name:m.from_name || id},type);
          state.chats.push(c);
        }
        if (!c) continue;
        c.unread++;
        if (m.created_at >= c.time) { c.preview = preview(m); c.time = m.created_at; }
      }
    }
    $('list-error').hidden = true; renderList(state); await chat.load();
  } catch (err) {
    if (epoch === state.epoch) { $('list-error').textContent = '同步暂未完成：'+err.message; $('list-error').hidden = false; }
  } finally { if (epoch === state.epoch) syncing = false; }
}
function schedulePoll() {
  clearTimeout(poll);
  poll = setTimeout(async () => { await sync(); if (state.user) schedulePoll(); },15000);
}
function eventReceived(event) {
  if (['ping','pong','presence','typing'].includes(event.type)) return;
  if (!eventTimer) eventTimer = setTimeout(() => { eventTimer = null; sync(); },250);
}
async function boot() {
  const epoch = ++state.epoch;
  $('login-submit').disabled = true;
  try {
    const result = await request('/me');
    if (epoch !== state.epoch) return;
    state.user = result.user || result;
    if (!state.user.uid) throw new Error('账号信息不完整，请重新登录');
    $('auth-screen').hidden = true; $('workspace').hidden = false; $('password').value = '';
    $('profile-button').innerHTML = avatarHTML(state.user); $('profile-button').title = label(state.user);
    imagesFallback($('profile-button')); renderList(state);
    await reload(); if (epoch !== state.epoch) return;
    realtime = connectRealtime({getToken:() => getAuth()?.access_token,onEvent:eventReceived,onStatus:status => {
      connection(status); if (status === 'online') sync();
    }});
    await sync(); if (epoch === state.epoch) schedulePoll();
  } catch (err) { if (epoch === state.epoch) showLogin(err.message); }
  finally { $('login-submit').disabled = false; }
}
$('login-form').onsubmit = async e => {
  e.preventDefault(); if ($('login-submit').disabled) return;
  $('login-submit').disabled = true; $('login-error').textContent = '';
  try { await login($('identifier').value.trim(),$('password').value); await boot(); }
  catch (err) { $('login-error').textContent = err.message; }
  finally { $('login-submit').disabled = false; }
};
$('conversation-list').onclick = e => {
  const key = e.target.closest('[data-chat]')?.dataset.chat;
  if (key) chat.open(state.chats.find(c => keyOf(c) === key));
};
$('conversation-search').oninput = () => renderList(state);
$('refresh-list').onclick = async () => { await reload(); await sync(); };
document.querySelectorAll('[data-tab]').forEach(button => button.onclick = () => {
  state.tab = button.dataset.tab;
  document.querySelectorAll('[data-tab]').forEach(b => { b.classList.toggle('active',b === button); b.setAttribute('aria-pressed',String(b === button)); });
  $('list-title').replaceChildren(document.createTextNode({all:'消息',direct:'好友',group:'群聊'}[state.tab]));
  renderList(state);
});
document.querySelectorAll('[data-filter]').forEach(button => button.onclick = () => {
  state.filter = button.dataset.filter;
  document.querySelectorAll('[data-filter]').forEach(b => {b.classList.toggle('active',b === button);b.setAttribute('aria-pressed',String(b === button));});
  renderList(state);
});
initContacts(state,reload,signOut);
window.addEventListener('oldchat:expired',() => showLogin('登录已过期，请重新登录'));
window.addEventListener('offline',() => connection('offline'));
window.addEventListener('online',() => sync());
document.addEventListener('visibilitychange',() => { if (!document.hidden) sync(); });
window.addEventListener('storage',e => {
  if (e.key !== 'oldchat_web_auth') return;
  const previous = getAuth()?.user?.id;
  const next = loadAuth();
  if (!next) showLogin('你已在其他窗口退出登录');
  else if (previous && next.user?.id !== previous) { showLogin(); boot(); }
});
window.addEventListener('pagehide',() => { realtime?.close(); clearTimeout(poll); });
window.addEventListener('pageshow',e => { if (e.persisted && getAuth()) { showLogin(); boot(); } });
if (loadAuth()) boot();
