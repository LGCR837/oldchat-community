import {request} from './api.js';
import {escapeHTML as esc, label} from './model.js';
import {$, openModal, closeModal, toast, avatarHTML} from './ui.js';
export function initContacts(state, reload, signOut) {
  const add = () => {
    openModal('重新连接', `<p class="muted">找到熟悉的人，也认识新的朋友。</p><form id="add-form"><label for="add-kind">我想…</label><select id="add-kind"><option value="friend">通过 UID 添加好友</option><option value="ncuid">通过 NCUID 添加好友</option><option value="join">加入群聊</option><option value="create">创建群聊</option></select><label for="add-value" id="add-label">对方 UID</label><input id="add-value" required maxlength="80" placeholder="输入对方的 UID"><p class="form-error" id="add-error" role="alert"></p><button class="primary-button" id="add-submit">发送好友申请 ↗</button></form>`);
    $('add-kind').onchange = () => {
      const create = $('add-kind').value === 'create', join = $('add-kind').value === 'join';
      $('add-label').textContent = create ? '群聊名称' : join ? '群号' : $('add-kind').value === 'ncuid' ? '对方 NCUID' : '对方 UID';
      $('add-value').placeholder = create ? '给新群起个名字' : join ? '输入群号' : '输入对应账号标识';
      $('add-submit').textContent = create ? '创建群聊 ↗' : join ? '申请加入 ↗' : '发送好友申请 ↗';
    };
    $('add-form').onsubmit = async e => {
      e.preventDefault(); const epoch = state.epoch, value = $('add-value').value.trim(), kind = $('add-kind').value;
      if (!value) return; $('add-submit').disabled = true;
      try {
        const path = kind === 'create' ? '/groups/create' : kind === 'join' ? '/groups/join' : '/friends/request';
        const body = kind === 'create' ? {name:value} : kind === 'join' ? {group_id:value} : kind === 'ncuid' ? {to_ncuid:value} : {to_uid:value};
        await request(path,{method:'POST',body});
        if (state.epoch !== epoch) return;
        closeModal(); toast(kind === 'create' ? '群聊已创建' : '申请已发送，请等待对方确认'); await reload();
      } catch (err) { if ($('add-error')) $('add-error').textContent = err.message; }
      finally { if ($('add-submit')) $('add-submit').disabled = false; }
    };
  };
  $('new-chat').onclick = add; $('welcome-add').onclick = add;
  $('profile-button').onclick = () => {
    const user = state.user;
    openModal('我的账号', `<div class="profile-hero"><div class="avatar">${avatarHTML(user)}</div><div><strong>${esc(label(user))}</strong><p class="muted">${esc(user.signature || '今天，也想好好说话。')}</p></div></div><div class="detail-line"><span>UID</span><strong>${esc(user.uid)}</strong></div><div class="detail-line"><span>NCUID</span><strong>${esc(user.ncuid || '—')}</strong></div><p class="muted">本页草稿仅保留在当前会话内，退出登录后清除。资料修改及账号安全设置请使用客户端。</p><a class="outline-button" href="/download/oldchat.apk">下载 Android 客户端 ↗</a><button id="logout-button" class="danger-button">退出登录</button>`);
    $('logout-button').onclick = () => { closeModal(); signOut(); };
  };
  $('chat-details').onclick = () => {
    const c = state.current; if (!c) return;
    openModal(c.type === 'group' ? '群聊资料' : '好友资料', `<div class="profile-hero"><div class="avatar ${c.type}">${avatarHTML(c)}</div><strong>${esc(c.name)}</strong></div><div class="detail-line"><span>${c.type === 'group' ? '群号' : 'UID'}</span><strong>${esc(c.id)}</strong></div>${c.ncuid ? `<div class="detail-line"><span>NCUID</span><strong>${esc(c.ncuid)}</strong></div>` : ''}${c.type === 'group' ? `<div class="detail-line"><span>成员</span><strong>${c.member_count || 0} 人</strong></div><p class="muted">群公告</p><p>${esc(c.announcement || '暂时没有公告。')}</p>` : ''}<p class="muted">更多管理与安全操作请前往 Android 客户端。</p>`);
  };
  $('requests-button').onclick = async () => {
    const epoch = state.epoch;
    openModal('好友申请','<p class="muted">正在加载…</p>');
    try {
      const result = await request('/friends/requests');
      if (state.epoch !== epoch || !$('modal').open) return;
      const rows = (result.requests || []).filter(r => r.status === 0);
      $('modal-body').innerHTML = rows.length ? rows.map(r => `<section class="request-card"><p><strong>${esc(r.from_display_name || r.from_username || r.from_uid)}</strong></p><p class="muted">${esc(r.from_ncuid || r.from_uid)}</p><button data-request="${esc(r.id)}" data-accept="true">接受</button><button data-request="${esc(r.id)}" data-accept="false">忽略</button></section>`).join('') : '<p class="muted">目前没有待处理的好友申请。</p>';
      $('modal-body').querySelectorAll('[data-request]').forEach(button => button.onclick = async () => {
        const section = button.closest('section'); section.querySelectorAll('button').forEach(b => {b.disabled = true;});
        try {
          await request('/friends/respond',{method:'POST',body:{request_id:button.dataset.request,accept:button.dataset.accept === 'true'}});
          if (state.epoch !== epoch) return;
          section.remove(); toast('申请已处理'); await reload();
          if (!$('modal-body').querySelector('.request-card')) $('modal-body').textContent = '目前没有待处理的好友申请。';
        } catch (err) { toast(err.message); section.querySelectorAll('button').forEach(b => {b.disabled = false;}); }
      });
    } catch (err) { if ($('modal').open) $('modal-body').textContent = err.message; }
  };
}
