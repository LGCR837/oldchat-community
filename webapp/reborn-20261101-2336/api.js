const KEY = 'oldchat_web_auth';
let auth = null;
let revision = 0;
let refreshing = null;
export function loadAuth() {
  revision++;
  try { auth = JSON.parse(localStorage.getItem(KEY)); } catch { auth = null; }
  if (!auth?.access_token) auth = null;
  return auth;
}
export const getAuth = () => auth;
export function saveAuth(value) {
  auth = value;
  revision++;
  try { if (value) localStorage.setItem(KEY, JSON.stringify(value)); else localStorage.removeItem(KEY); } catch {}
  // Do not duplicate bearer tokens into a JavaScript-readable cookie.
  document.cookie = 'oldchat_auth=; Max-Age=0; path=/; SameSite=Lax';
}
export class APIError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}
async function perform(path, options, token) {
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal?.addEventListener('abort', abort, {once:true});
  if (options.signal?.aborted) controller.abort();
  const timer = setTimeout(abort, options.timeout || 25000);
  const multipart = options.body instanceof FormData;
  try {
    const res = await fetch('/v1' + path, {
      method: options.method || 'GET', signal: controller.signal, credentials:'same-origin', cache:'no-store',
      headers: { ...(token ? {Authorization:`Bearer ${token}`} : {}),
        ...(!multipart && options.body ? {'Content-Type':'application/json'} : {}) },
      body: options.body ? (multipart ? options.body : JSON.stringify(options.body)) : undefined
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new APIError(data.message || data.error || `请求失败 (${res.status})`, res.status);
    return data;
  } catch (error) {
    if (error.name === 'AbortError') throw new APIError('请求已取消或超时，请检查网络后重试', 0);
    if (error instanceof APIError) throw error;
    throw new APIError('网络暂时不可用，请稍后重试', 0);
  } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
}
async function refresh() {
  const start = revision;
  const token = auth?.refresh_token;
  if (!token) throw new APIError('登录已过期，请重新登录', 401);
  const data = await perform('/auth/refresh', {method:'POST',body:{refresh_token:token}}, null);
  if (start !== revision) throw new APIError('登录状态已改变', 401);
  if (!data.access_token) throw new APIError('登录已过期，请重新登录', 401);
  saveAuth({...auth, ...data});
}
export async function request(path, options = {}) {
  const start = revision;
  const token = options.public ? null : auth?.access_token;
  const identity = auth?.user?.id || auth?.user?.ncuid || auth?.user?.uid;
  try { return await perform(path, options, token); }
  catch (error) {
    if (error.status !== 401 || options.public) throw error;
    if (start !== revision) {
      const currentIdentity = auth?.user?.id || auth?.user?.ncuid || auth?.user?.uid;
      if (identity && identity === currentIdentity && auth?.access_token !== token) {
        return perform(path, options, auth.access_token);
      }
      throw error;
    }
    try {
      if (!refreshing) refreshing = refresh().finally(() => { refreshing = null; });
      await refreshing;
    } catch (refreshError) {
      if (refreshError.status === 401 && start === revision) {
        saveAuth(null); window.dispatchEvent(new Event('oldchat:expired'));
      }
      throw refreshError;
    }
    const retryToken = auth?.access_token;
    try { return await perform(path, options, retryToken); }
    catch (retryError) {
      if (retryError.status === 401 && retryToken === auth?.access_token) {
        saveAuth(null); window.dispatchEvent(new Event('oldchat:expired'));
      }
      throw retryError;
    }
  }
}
export async function login(identifier, password) {
  let device = '';
  try { device = localStorage.getItem('oldchat_web_device_id') || ''; } catch {}
  if (!device) {
    device = 'web-' + crypto.randomUUID();
    try { localStorage.setItem('oldchat_web_device_id', device); } catch {}
  }
  const data = await request('/auth/login', {public:true,method:'POST',body:{identifier,password,
    device_id:device,device_name:'OldChat Web',platform:'web',app_version:'2.0.0'}});
  if (!data.access_token) throw new APIError('服务器未返回有效登录信息', 0);
  saveAuth(data);
  return data;
}
export async function logout() {
  const snapshot = auth;
  saveAuth(null);
  if (snapshot?.refresh_token) {
    await perform('/auth/logout', {method:'POST',body:{refresh_token:snapshot.refresh_token}}, snapshot.access_token).catch(() => {});
  }
}
