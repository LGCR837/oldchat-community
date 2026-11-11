// ECDH/AES-CBC/HMAC protocol is implemented by the existing audited ws-crypto module.
export function connectRealtime({getToken,onEvent,onStatus}) {
  let stopped = false, generation = 0, socket, timer, timeout, delay = 1500;
  function retry() {
    if (stopped || timer) return;
    onStatus(navigator.onLine ? 'reconnecting' : 'offline');
    timer = setTimeout(() => { timer = null; connect(); }, delay + Math.random()*400);
    delay = Math.min(delay*2,30000);
  }
  async function connect() {
    if (stopped) return;
    const version = ++generation, token = getToken();
    if (!token) return;
    if (!navigator.onLine || !globalThis.crypto?.subtle || !globalThis.OldChatCrypto) { retry(); return; }
    onStatus('connecting');
    try {
      const session = await Promise.race([
        OldChatCrypto.handshake('/v1',token),
        new Promise((_,reject) => { timeout = setTimeout(() => reject(new Error('Handshake timeout')),15000); })
      ]);
      clearTimeout(timeout);
      if (stopped || version !== generation || token !== getToken()) { if (!stopped) retry(); return; }
      const ws = new WebSocket(location.origin.replace(/^http/,'ws')+'/v1/ws?token='+encodeURIComponent(token)+'&sid='+encodeURIComponent(session.sessionId));
      socket = ws;
      timeout = setTimeout(() => ws.close(),15000);
      let sequence = Promise.resolve();
      ws.onopen = () => { clearTimeout(timeout); if (version !== generation || stopped) return ws.close(); delay = 1500; onStatus('online'); };
      ws.onmessage = event => {
        sequence = sequence.then(async () => {
          if (stopped || version !== generation) return;
          const plain = await OldChatCrypto.decrypt(session,event.data);
          if (!stopped && version === generation) onEvent(JSON.parse(plain));
        }).catch(() => { ws.close(); });
      };
      ws.onerror = () => ws.close();
      ws.onclose = () => { clearTimeout(timeout); if (!stopped && version === generation) retry(); };
    } catch { clearTimeout(timeout); if (!stopped && version === generation) retry(); }
  }
  function online() {
    if (stopped || socket?.readyState === WebSocket.OPEN) return;
    clearTimeout(timer); timer = null; generation++; socket?.close(); connect();
  }
  window.addEventListener('online',online);
  connect();
  return {close() { stopped = true; generation++; clearTimeout(timer); clearTimeout(timeout); socket?.close(); window.removeEventListener('online',online); }};
}
