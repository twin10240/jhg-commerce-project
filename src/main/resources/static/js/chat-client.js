(() => {
  const root = document.querySelector('[data-chat-root]');
  if (!root) return;

  const role = root.dataset.chatRole;
  const memberId = root.dataset.memberId;
  const csrf = { [root.dataset.csrfHeader]: root.dataset.csrfToken };
  const panel = root.querySelector('[data-chat-conversation-id]');
  const messages = root.querySelector('[data-chat-messages]');
  const form = root.querySelector('[data-chat-message-form]');
  const body = root.querySelector('[data-chat-body]');
  const send = root.querySelector('[data-chat-send]');
  const status = root.querySelector('[data-chat-status]');
  const loadMore = root.querySelector('[data-chat-load-more]');
  const statusToggle = root.querySelector('[data-chat-status-toggle]');
  const list = root.querySelector('[data-chat-conversations]');
  let conversation = null;
  let oldest = null;
  let requestedConversationId = root.dataset.chatRequestedConversationId || null;
  let socket = null;
  let tokenRefreshTimer = null;
  let reconnectTimer = null;
  let connecting = false;
  const seen = new Set();

  const api = async (path, options = {}) => {
    const response = await fetch(`/api/chat/conversations${path}`, { ...options, headers: { ...csrf, 'Content-Type': 'application/json', ...(options.headers || {}) } });
    if (!response.ok) throw new Error(`채팅 요청에 실패했습니다. (${response.status})`);
    return response.status === 204 ? null : response.json();
  };
  const cursor = message => btoa(unescape(encodeURIComponent(JSON.stringify({ id: message.id, createdAt: message.createdAt })))).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  const uuid = () => crypto.randomUUID();
  const setStatus = text => { status.textContent = text; };

  function append(message, before = false) {
    if (seen.has(message.id) || message.conversationId !== conversation?.id) return;
    seen.add(message.id);
    const item = document.createElement('li');
    item.className = `chat-message ${message.senderRole === role ? 'mine' : ''}`;
    item.dataset.chatMessageId = message.id;
    item.textContent = message.body;
    const time = document.createElement('time');
    time.dateTime = message.createdAt;
    time.textContent = new Date(message.createdAt).toLocaleString('ko-KR');
    item.append(time);
    messages[before ? 'prepend' : 'append'](item);
  }
  async function markRead() { if (conversation) await api(`/${conversation.id}/read`, { method: 'POST', body: '{}' }); }
  async function load(cursorValue) {
    if (!conversation) return;
    const page = await api(`/${conversation.id}/messages?limit=50${cursorValue ? `&cursor=${encodeURIComponent(cursorValue)}` : ''}`);
    if (cursorValue) page.forEach(message => append(message, true));
    else page.slice().reverse().forEach(message => append(message));
    oldest = page.at(-1) || oldest;
    loadMore.hidden = page.length < 50;
    await markRead();
  }
  function select(next) {
    conversation = next;
    panel.dataset.chatConversationId = next.id;
    messages.replaceChildren(); seen.clear(); oldest = null;
    setStatus(`주문 #${next.orderId} 상담 (${next.status === 'OPEN' ? '진행 중' : '종료'})`);
    form.hidden = next.status !== 'OPEN';
    if (statusToggle) { statusToggle.hidden = false; statusToggle.textContent = next.status === 'OPEN' ? '상담 종료' : '상담 재개'; }
    load().catch(error => setStatus(error.message));
  }
  function renderList(items) {
    if (!list) return;
    list.replaceChildren();
    items.forEach(item => {
      const button = document.createElement('button'); button.type = 'button'; button.textContent = `주문 #${item.orderId} · ${item.status}`;
      button.setAttribute('aria-current', String(item.id === conversation?.id)); button.onclick = () => select(item);
      const row = document.createElement('li'); row.append(button); list.append(row);
    });
    const requested = requestedConversationId && items.find(item => item.id === requestedConversationId);
    if (!conversation && (requested || items[0])) select(requested || items[0]);
    requestedConversationId = null;
  }
  async function reloadConversations() { renderList(await api('')); }

  form.addEventListener('submit', async event => {
    event.preventDefault();
    const text = body.value.trim();
    if (!text || !conversation) return;
    send.disabled = true;
    try {
      append(await api(`/${conversation.id}/messages`, { method: 'POST', body: JSON.stringify({ body: text, clientMessageId: uuid() }) }));
      body.value = '';
      await markRead();
    } catch (error) { setStatus(error.message); } finally { send.disabled = false; }
  });
  loadMore.addEventListener('click', () => oldest && load(cursor(oldest)).catch(error => setStatus(error.message)));
  statusToggle?.addEventListener('click', async () => {
    try { select(await api(`/${conversation.id}`, { method: 'PATCH', body: JSON.stringify({ status: conversation.status === 'OPEN' ? 'CLOSED' : 'OPEN' }) })); await reloadConversations(); }
    catch (error) { setStatus(error.message); }
  });

  function stopSocket() {
    if (tokenRefreshTimer) clearTimeout(tokenRefreshTimer);
    tokenRefreshTimer = null;
    if (socket) { socket.removeAllListeners(); socket.disconnect(); }
    socket = null;
  }
  function scheduleReconnect() {
    if (reconnectTimer) return;
    reconnectTimer = setTimeout(() => { reconnectTimer = null; connect().catch(() => scheduleReconnect()); }, 1_000);
  }
  async function connect() {
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (!window.io || connecting) return;
    connecting = true;
    stopSocket();
    try {
      const token = await fetch('/api/realtime/token', { method: 'POST', headers: csrf }).then(response => response.ok ? response.json() : Promise.reject(new Error('실시간 인증 토큰을 갱신하지 못했습니다.')));
      const realtimeUrl = document.getElementById('notification-root')?.dataset.realtimeUrl;
      socket = window.io(realtimeUrl, { auth: { token: token.token }, autoConnect: false, reconnection: false });
      socket.on('chat:message:new', message => { append(message); if (message.conversationId === conversation?.id) markRead().catch(() => {}); });
      socket.on('chat:conversation:updated', update => { if (update.id === conversation?.id) { conversation = { ...conversation, ...update }; select(conversation); } if (role === 'ADMIN') reloadConversations().catch(() => {}); });
      socket.on('chat:read', update => {
        if (update.conversationId === conversation?.id && update.readerMemberId && String(update.readerMemberId) !== memberId) {
          setStatus(`상대방이 ${new Date(update.readAt).toLocaleString('ko-KR')}에 읽었습니다.`);
        }
      });
      socket.on('connect', () => conversation && load().catch(() => {}));
      socket.on('disconnect', scheduleReconnect);
      socket.on('connect_error', scheduleReconnect);
      const refreshAt = Math.max(1_000, new Date(token.expiresAt).getTime() - Date.now() - 15_000);
      tokenRefreshTimer = setTimeout(() => connect().catch(() => scheduleReconnect()), refreshAt);
      socket.connect();
    } catch (error) {
      scheduleReconnect();
      throw error;
    } finally { connecting = false; }
  }
  (async () => {
    try {
      if (role === 'USER' && requestedConversationId) {
        const conversations = await api('');
        const requested = conversations.find(item => item.id === requestedConversationId);
        if (!requested) throw new Error('상담을 찾을 수 없습니다.');
        select(requested);
        requestedConversationId = null;
      } else if (role === 'USER') select(await api('', { method: 'POST', body: JSON.stringify({ orderId: Number(root.dataset.orderId) }) }));
      else await reloadConversations();
      await connect();
    } catch (error) { setStatus(error.message || '채팅을 불러오지 못했습니다.'); }
  })();
})();
