import {request} from './api.js';
import {keyOf, messagePath, mergeMessages, sendPayload, preview} from './model.js';
import {$, renderHeader, renderMessages, toast, openModal, closeModal} from './ui.js';
export function createChat(state, changed) {
  let generation = 0, controller, loading = false, sending = false;
  const drafts = new Map();
  let attachment = null, attachmentURL = '';
  function clearAttachment() {
    if (attachmentURL) URL.revokeObjectURL(attachmentURL);
    attachmentURL = ''; attachment = null; $('file-input').value = ''; $('attachment-preview').hidden = true;
  }
  function reset() {
    generation++; controller?.abort(); loading = false; sending = false; drafts.clear();
    clearAttachment(); state.current = null; state.messages = []; $('message-input').value = '';
    $('messages').replaceChildren(); $('chat-name').textContent = ''; $('chat-avatar').replaceChildren();
    $('message-error').hidden = true; $('send-status').textContent = ''; $('send-button').disabled = false;
    $('workspace').classList.remove('chat-open'); $('welcome').hidden = false; $('chat-panel').hidden = true;
  }
  function error(text) {
    $('message-error').replaceChildren(document.createTextNode(text));
    const retry = document.createElement('button'); retry.textContent = '重新加载'; retry.onclick = () => load(false);
    $('message-error').append(retry); $('message-error').hidden = false;
  }
  async function open(chat) {
    if (!chat) return;
    if (keyOf(state.current) === keyOf(chat)) { $('workspace').classList.add('chat-open'); await load(); return; }
    if (state.current) drafts.set(keyOf(state.current),$('message-input').value);
    generation++; controller?.abort(); loading = false; clearAttachment();
    state.current = chat; state.messages = []; state.hasMore = false;
    $('message-input').value = drafts.get(keyOf(chat)) || '';
    $('message-input').style.height = 'auto'; $('message-error').hidden = true;
    renderHeader(chat); renderMessages(state,'bottom'); changed(); await load(false,true);
  }
  async function markRead(chat) {
    if (!chat || document.hidden || keyOf(state.current) !== keyOf(chat)) return;
    const scroll = $('message-scroll');
    if (scroll.scrollHeight-scroll.scrollTop-scroll.clientHeight >= 90) return;
    if (matchMedia('(max-width:760px)').matches && !$('workspace').classList.contains('chat-open')) return;
    try {
      const body = chat.type === 'group' ? {group_id:chat.id} : chat.ncuid ? {with_ncuid:chat.ncuid} : {with_uid:chat.id};
      await request(chat.type === 'group' ? '/groups/read' : '/direct/read', {method:'POST',body});
      if (keyOf(state.current) === keyOf(chat)) { chat.unread = 0; changed(); }
    } catch {}
  }
  async function load(older = false, first = false) {
    if (!state.current || loading) return;
    loading = true;
    const version = generation, chat = state.current;
    controller = new AbortController();
    $('load-older').disabled = true;
    const cursor = older ? state.messages.find(m => !m.localState) : null;
    try {
      const data = await request(messagePath(chat,cursor), {signal:controller.signal});
      if (version !== generation) return;
      const rows = Array.isArray(data.messages) ? data.messages : [];
      const before = state.messages.length;
      const next = mergeMessages(state.messages,rows);
      // Bound one conversation's DOM; reopening resets the history window.
      if (next.length > 600 && older) {
        state.hasMore = false; $('load-older').hidden = true;
        toast('当前已加载 600 条消息，请用客户端查看更久的历史'); return;
      }
      if (older || first) state.hasMore = data.has_more === true && rows.length > 0 && (!older || next.length > before);
      if (JSON.stringify(next) !== JSON.stringify(state.messages) || first) {
        state.messages = next; renderMessages(state,older ? 'older' : first ? 'bottom' : 'preserve');
        const latest = rows.reduce((last,m) => !last || m.created_at > last.created_at ? m : last,null);
        if (latest && latest.created_at >= chat.time) { chat.preview = preview(latest); chat.time = latest.created_at; changed(); }
      }
      $('load-older').hidden = !state.hasMore;
      $('message-error').hidden = true;
      if (!older) await markRead(chat);
    } catch (err) { if (version === generation) error(err.message); }
    finally { if (version === generation) { loading = false; $('load-older').disabled = false; } }
  }
  async function transmit(chat, message, file) {
    const version = generation, epoch = state.epoch;
    sending = true; $('send-button').disabled = true; $('send-status').textContent = file ? '正在上传图片…' : '发送中…';
    try {
      let fields = {body:message.body,msg_type:message.msg_type,media_url:message.media_url};
      if (file) {
        const form = new FormData(); form.append('file',file);
        const media = await request('/media', {method:'POST',body:form,timeout:90000});
        if (state.epoch !== epoch) return;
        if (!media.url) throw new Error('上传未返回有效图片地址');
        fields = {...fields,media_url:media.url,thumb_url:media.thumb_url,original_url:media.original_url};
      }
      if (state.epoch !== epoch) return;
      const result = await request(chat.type === 'group' ? '/groups/message/send' : '/direct/send', {method:'POST',body:sendPayload(chat,fields)});
      if (state.epoch !== epoch) return;
      const confirmed = result.message || result;
      if (!confirmed.id) throw new Error('服务器未确认发送结果，请检查消息记录');
      chat.preview = preview(confirmed); chat.time = confirmed.created_at;
      if (version === generation) {
        state.messages = mergeMessages(state.messages.filter(m => m.id !== message.id),[confirmed]);
        renderMessages(state,'preserve');
      }
      changed();
    } catch (err) {
      if (state.epoch !== epoch) return;
      if (version === generation) {
        message.localState = 'failed'; message.retryFile = file; state.messages = mergeMessages(state.messages,[message]); renderMessages(state);
      } else {
        drafts.set(keyOf(chat), message.body || '');
        toast(`${chat.name}：发送未确认，请回到会话检查，文字已恢复到草稿`);
      }
      toast(err.message);
    } finally {
      if (state.epoch === epoch) { sending = false; $('send-button').disabled = false; $('send-status').textContent = ''; }
    }
  }
  async function send() {
    if (!state.current || sending) return;
    const text = $('message-input').value.trim(), file = attachment;
    if (!text && !file) return;
    if (!navigator.onLine) { toast('网络离线，草稿已保留，请联网后发送'); return; }
    const chat = state.current;
    const message = {id:'local-'+crypto.randomUUID(),from_uid:state.user.uid,from_ncuid:state.user.ncuid,
      body:text,msg_type:file ? 'image' : 'text',created_at:Math.floor(Date.now()/1000),localState:'sending'};
    state.messages = mergeMessages(state.messages,[message]);
    $('message-input').value = ''; $('message-input').style.height = 'auto'; drafts.delete(keyOf(chat)); clearAttachment();
    renderMessages(state,'bottom'); await transmit(chat,message,file);
  }
  $('compose-form').onsubmit = e => { e.preventDefault(); send(); };
  $('message-input').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && e.keyCode !== 229) { e.preventDefault(); send(); }
  });
  $('message-input').addEventListener('input', () => {
    const input = $('message-input'); input.style.height = 'auto'; input.style.height = Math.min(input.scrollHeight,140)+'px';
  });
  $('load-older').onclick = () => load(true);
  $('back-button').onclick = () => { $('workspace').classList.remove('chat-open'); $('conversation-search').focus(); };
  $('jump-latest').onclick = () => { $('message-scroll').scrollTop = $('message-scroll').scrollHeight; $('jump-latest').hidden = true; markRead(state.current); };
  $('message-scroll').onscroll = () => {
    const el = $('message-scroll'); if (el.scrollHeight-el.scrollTop-el.clientHeight < 90) $('jump-latest').hidden = true;
  };
  $('attach-button').onclick = () => $('file-input').click();
  $('file-input').onchange = () => {
    const file = $('file-input').files[0]; if (!file) return;
    if (!['image/jpeg','image/png','image/gif','image/webp'].includes(file.type) || file.size > 10*1024*1024) {
      toast('请选择不超过 10MB 的 JPG、PNG、GIF 或 WebP 图片'); $('file-input').value = ''; return;
    }
    clearAttachment(); attachment = file; attachmentURL = URL.createObjectURL(file);
    const image = document.createElement('img'); image.src = attachmentURL; image.alt = '待发送图片';
    const name = document.createElement('span'); name.textContent = file.name;
    const remove = document.createElement('button'); remove.textContent = '×'; remove.setAttribute('aria-label','移除附件'); remove.onclick = clearAttachment;
    $('attachment-preview').replaceChildren(image,name,remove); $('attachment-preview').hidden = false;
  };
  $('emoji-button').onclick = () => {
    openModal('一点小表情', '<div class="emoji-grid">'+['😊','👋','❤️','👍','🎉','🌿','😂','🥹','✨','☕','🐱','晚安 🌙'].map(e => `<button type="button">${e}</button>`).join('')+'</div>');
    $('modal-body').querySelectorAll('button').forEach(button => button.onclick = () => {
      const input = $('message-input'); input.setRangeText(button.textContent,input.selectionStart,input.selectionEnd,'end'); closeModal(); input.focus();
    });
  };
  $('messages').onclick = e => {
    const id = e.target.closest('[data-retry]')?.dataset.retry;
    if (!id || sending) return;
    const message = state.messages.find(m => m.id === id); if (!message) return;
    openModal('确认重新发送', '<p class="muted">网络超时不一定代表发送失败。请先检查历史记录，重新发送可能产生重复消息。</p><button class="primary-button" id="confirm-retry">确认重新发送</button>');
    $('confirm-retry').onclick = () => { closeModal(); message.localState = 'sending'; renderMessages(state); transmit(state.current,message,message.retryFile); };
  };
  return {open,load,reset,markRead};
}
