export const label = u => u?.remark_name || u?.display_name || u?.name || u?.username || u?.ncuid || u?.uid || '旧聊用户';
export const keyOf = c => c ? `${c.type}:${c.id}` : '';
export const escapeHTML = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function safeURL(value, origin = globalThis.location?.origin || 'https://localhost') {
  if (!value || typeof value !== 'string') return '';
  try {
    const url = new URL(value, origin);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : '';
  } catch { return ''; }
}
export function mine(message, user) {
  if (!user) return false;
  const stable = message.from_ncuid || message.sender_ncuid;
  if (stable && user.ncuid) return stable === user.ncuid;
  return !!message.from_uid && message.from_uid === user.uid;
}
export function mergeMessages(old, incoming) {
  const map = new Map();
  for (const message of [...old, ...incoming]) {
    if (!message?.id) continue;
    const previous = map.get(message.id);
    if (previous?.msg_type === 'recall' && message.msg_type !== 'recall') continue;
    map.set(message.id, { ...previous, ...message });
  }
  return [...map.values()].sort((a, b) => Number(a.created_at || 0) - Number(b.created_at || 0)
    || Number(a.sort_seq || 0) - Number(b.sort_seq || 0) || String(a.id).localeCompare(String(b.id)));
}
export function preview(message) {
  if (!message) return '还没有消息，打个招呼吧';
  if (message.burn_after_seconds > 0) return '[阅后即焚消息]';
  const types = {image:'[图片]',voice:'[语音]',video:'[视频]',file:'[文件]',resource:'[资源]',recall:'[消息已撤回]'};
  return types[message.msg_type] || String(message.body || '').slice(0, 120);
}
export function conversation(item, type) {
  const id = type === 'group' ? item.group_id || item.id : item.uid || item.ncuid;
  return id ? { ...item, id, type, name: label(item), unread: 0, preview: '', time: 0 } : null;
}
export function messagePath(chat, cursor) {
  const params = new URLSearchParams({limit:'50'});
  if (chat.type === 'group') params.set('group_id', chat.id);
  else params.set(chat.ncuid ? 'with_ncuid' : 'with_uid', chat.ncuid || chat.id);
  if (cursor?.id) { params.set('before_id', cursor.id); params.set('before_created_at', cursor.created_at); }
  return `/${chat.type === 'group' ? 'groups' : 'direct'}/messages/v2?${params}`;
}
export function sendPayload(chat, fields) {
  return { ...fields, ...(chat.type === 'group' ? {group_id:chat.id}
    : chat.ncuid ? {to_ncuid:chat.ncuid} : {to_uid:chat.id}) };
}
export function timeText(seconds) {
  if (!seconds) return '';
  const date = new Date(Number(seconds) * 1000);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', {hour:'2-digit',minute:'2-digit'});
}
export function dateText(seconds) {
  const date = new Date(Number(seconds) * 1000);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString('zh-CN', {month:'long',day:'numeric'});
}
