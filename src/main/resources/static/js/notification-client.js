(function (host, factory) {
  const client = factory(host);
  if (typeof module === 'object' && module.exports) module.exports = client;
  host.NotificationClient = client;
}(typeof globalThis === 'undefined' ? window : globalThis, host => {
  let token = null;
  let expiresAt = 0;
  let tokenPromise = null;
  let socket = null;
  let tokenTimer = null;
  let retryTimer = null;
  let root = null;
  let generation = 0;
  let session = null;
  let retryIndex = 0;
  let retryPending = false;
  let trigger = null;
  let panel = null;
  let badge = null;
  let itemsRoot = null;
  let emptyState = null;
  let logoutForm = null;
  let triggerListener = null;
  let logoutListener = null;
  let visibilityListener = null;
  let socketListeners = null;
  let renderedListeners = [];
  let recentLoaded = false;
  let recent = [];
  let unread = 0;
  let eventIds = new Set();
  let readIds = new Set();

  const retryDelays = [1_000, 2_000, 5_000, 10_000, 30_000];

  const unavailable = () => ({ kind: 'unavailable' });
  const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const isString = value => typeof value === 'string';
  const isDateString = value => isString(value) && Number.isFinite(Date.parse(value));

  function clearToken() {
    token = null;
    expiresAt = 0;
    if (tokenTimer !== null) host.clearTimeout(tokenTimer);
    tokenTimer = null;
  }

  function configuration() {
    const data = root && root.dataset;
    if (!data || !data.realtimeUrl || !data.csrfHeader || !data.csrfToken) {
      throw new Error('Missing realtime client configuration');
    }
    return {
      realtimeUrl: data.realtimeUrl.replace(/\/+$/, ''),
      csrfHeader: data.csrfHeader,
      csrfToken: data.csrfToken,
    };
  }

  function getToken() {
    if (token && Date.now() < expiresAt - 15_000) return Promise.resolve(token);
    if (tokenPromise) return tokenPromise;

    const currentGeneration = generation;
    let pending;
    pending = (async () => {
      try {
        const config = configuration();
        const response = await host.fetch('/api/realtime/token', {
          method: 'POST',
          headers: { [config.csrfHeader]: config.csrfToken },
        });
        if (!response.ok) throw new Error('Token request failed');
        const body = await response.json();
        const expiration = isObject(body) ? Date.parse(body.expiresAt) : NaN;
        if (!isObject(body) || !isString(body.token) || !body.token ||
            !Number.isFinite(expiration) || expiration <= Date.now() + 15_000) {
          throw new Error('Malformed token response');
        }
        if (generation !== currentGeneration) throw new Error('Client stopped');
        token = body.token;
        expiresAt = expiration;
        if (tokenTimer !== null) host.clearTimeout(tokenTimer);
        tokenTimer = host.setTimeout(clearToken, expiresAt - Date.now() - 15_000);
        return token;
      } finally {
        if (tokenPromise === pending) tokenPromise = null;
      }
    })();
    tokenPromise = pending;
    return pending;
  }

  function safeLink(linkUrl) {
    const encodedUnsafe = /%(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)/i;
    return isString(linkUrl) && /^\/(?!\/)[^\u0000-\u001f\u007f\\]*$/.test(linkUrl) &&
        !encodedUnsafe.test(linkUrl)
      ? linkUrl
      : '/notifications';
  }

  function parseNotification(value) {
    if (!isObject(value) || !isString(value.id) || !isString(value.type) ||
        !isString(value.title) || !isString(value.body) || !isString(value.linkUrl) ||
        !isDateString(value.createdAt) ||
        !(value.readAt === null || isDateString(value.readAt))) {
      throw new Error('Malformed notification response');
    }
    return {
      id: value.id,
      type: value.type,
      title: value.title,
      body: value.body,
      linkUrl: safeLink(value.linkUrl),
      createdAt: value.createdAt,
      readAt: value.readAt,
    };
  }

  const parsePage = value => {
    if (!isObject(value) || !Array.isArray(value.items) ||
        !(value.nextCursor === null || isString(value.nextCursor))) {
      throw new Error('Malformed notification page');
    }
    return { items: value.items.map(parseNotification), nextCursor: value.nextCursor };
  };

  const parseCount = (value, field) => {
    if (!isObject(value) || !Number.isSafeInteger(value[field]) || value[field] < 0) {
      throw new Error('Malformed count response');
    }
    return { [field]: value[field] };
  };

  async function request(path, options, parse, expectedStatus = 200, canRetry = true) {
    try {
      const config = configuration();
      const bearer = await getToken();
      const response = await host.fetch(`${config.realtimeUrl}${path}`, {
        ...options,
        headers: { ...(options.headers || {}), Authorization: `Bearer ${bearer}` },
      });
      if (response.status === 401) {
        if (token === bearer) clearToken();
        if (canRetry) return request(path, options, parse, expectedStatus, false);
        throw new Error('Authorization failed');
      }
      if (!response.ok || response.status !== expectedStatus) {
        throw new Error('Realtime request failed');
      }
      return expectedStatus === 204 ? {} : parse(await response.json());
    } catch {
      return unavailable();
    }
  }

  function list(options = {}) {
    const query = new URLSearchParams();
    if (options.cursor !== undefined) query.set('cursor', options.cursor);
    if (options.limit !== undefined) query.set('limit', String(options.limit));
    const suffix = query.size ? `?${query}` : '';
    return request(`/api/v1/notifications${suffix}`, {}, parsePage);
  }

  const unreadCount = () => request(
    '/api/v1/notifications/unread-count', {}, value => parseCount(value, 'count'));
  const read = id => request(
    `/api/v1/notifications/${encodeURIComponent(id)}/read`, { method: 'PATCH' }, () => ({}), 204);
  const readAll = () => request(
    '/api/v1/notifications/read-all', { method: 'POST' },
    value => parseCount(value, 'changedCount'), 201);

  function showUnread(value) {
    unread = Math.max(0, value);
    if (!badge) return;
    badge.textContent = unread > 99 ? '99+' : String(unread);
    badge.hidden = unread === 0;
    badge.setAttribute('aria-label', `읽지 않은 알림 ${unread}개`);
  }

  const isActive = current => session === current && root === current.element &&
    generation === current.generation;

  function syncUnread(current) {
    if (!isActive(current)) return Promise.resolve();
    if (current.countSync) return current.countSync;
    let pending;
    pending = (async () => {
      while (isActive(current)) {
        const epoch = current.notificationEpoch;
        const result = await unreadCount();
        if (!isActive(current) || result.kind === 'unavailable') return;
        if (epoch !== current.notificationEpoch) continue;
        showUnread(result.count);
        return;
      }
    })().finally(() => {
      if (current.countSync === pending) current.countSync = null;
    });
    current.countSync = pending;
    return pending;
  }

  function relativeTime(createdAt) {
    const seconds = Math.max(0, Math.floor((Date.now() - Date.parse(createdAt)) / 1_000));
    if (seconds < 60) return '방금 전';
    if (seconds < 3_600) return `${Math.floor(seconds / 60)}분 전`;
    if (seconds < 86_400) return `${Math.floor(seconds / 3_600)}시간 전`;
    if (seconds < 604_800) return `${Math.floor(seconds / 86_400)}일 전`;
    return new Date(createdAt).toLocaleDateString('ko-KR');
  }

  function clearRenderedListeners() {
    for (const [element, listener] of renderedListeners) {
      element.removeEventListener('click', listener);
    }
    renderedListeners = [];
  }

  function renderRecent() {
    if (!itemsRoot || !host.document) return;
    clearRenderedListeners();
    const elements = recent.map(item => {
      const link = host.document.createElement('a');
      const title = host.document.createElement('strong');
      const body = host.document.createElement('span');
      const time = host.document.createElement('time');
      link.className = `notification-item${item.readAt === null && !readIds.has(item.id) ? ' unread' : ''}`;
      link.setAttribute('href', item.linkUrl);
      link.setAttribute('role', 'listitem');
      link.setAttribute('data-notification-id', item.id);
      title.className = 'notification-item-title';
      title.textContent = item.title;
      body.className = 'notification-item-body';
      body.textContent = item.body;
      time.className = 'notification-item-time';
      time.dateTime = item.createdAt;
      time.textContent = relativeTime(item.createdAt);
      link.append(title, body, time);
      const listener = () => {
        if (item.readAt !== null || readIds.has(item.id)) return;
        readIds.add(item.id);
        link.classList.toggle('unread', false);
        showUnread(unread - 1);
        read(item.id);
      };
      link.addEventListener('click', listener);
      renderedListeners.push([link, listener]);
      return link;
    });
    itemsRoot.replaceChildren(...elements);
    if (emptyState) emptyState.hidden = elements.length !== 0;
  }

  function syncRecent(current) {
    if (!isActive(current)) return Promise.resolve();
    if (current.recentSync) return current.recentSync;
    let pending;
    pending = (async () => {
      while (isActive(current)) {
        const epoch = current.recentEpoch;
        const result = await list({ limit: 5 });
        if (!isActive(current)) return;
        if (result.kind === 'unavailable') {
          if (emptyState) {
            emptyState.textContent = '알림을 불러오지 못했습니다.';
            emptyState.hidden = recent.length !== 0;
          }
          return;
        }
        if (epoch !== current.recentEpoch) continue;
        const pageIds = new Set();
        recent = result.items.filter(item => {
          if (pageIds.has(item.id)) return false;
          pageIds.add(item.id);
          return true;
        }).slice(0, 5);
        recentLoaded = true;
        if (emptyState) emptyState.textContent = '새 알림이 없습니다.';
        renderRecent();
        return;
      }
    })().finally(() => {
      if (current.recentSync === pending) current.recentSync = null;
    });
    current.recentSync = pending;
    return pending;
  }

  function acceptNotification(current, value) {
    if (!isActive(current)) return;
    let item;
    try {
      item = parseNotification(value);
    } catch {
      return;
    }
    if (eventIds.has(item.id)) return;
    eventIds.add(item.id);
    current.notificationEpoch += 1;
    current.recentEpoch += 1;
    recent = [item, ...recent.filter(existing => existing.id !== item.id)].slice(0, 5);
    if (item.readAt === null) showUnread(unread + 1);
    renderRecent();
    syncUnread(current);
  }

  function detachSocket(disconnect) {
    const current = socket;
    if (!current) return;
    if (socketListeners && typeof current.off === 'function') {
      for (const [event, listener] of Object.entries(socketListeners)) current.off(event, listener);
    }
    socket = null;
    socketListeners = null;
    if (disconnect && typeof current.disconnect === 'function') current.disconnect();
  }

  function scheduleReconnect(current) {
    if (!isActive(current) || typeof host.io !== 'function' || retryTimer !== null) return;
    if (host.document && host.document.hidden) {
      retryPending = true;
      return;
    }
    retryPending = false;
    const delay = retryDelays[Math.min(retryIndex, retryDelays.length - 1)];
    retryIndex = Math.min(retryIndex + 1, retryDelays.length - 1);
    retryTimer = host.setTimeout(() => {
      retryTimer = null;
      if (host.document && host.document.hidden) retryPending = true;
      else connectSocket(current);
    }, delay);
  }

  async function connectSocket(currentSession) {
    if (!isActive(currentSession) || typeof host.io !== 'function') return;
    try {
      const bearer = await getToken();
      if (!isActive(currentSession)) return;
      const config = configuration();
      const currentSocket = host.io(config.realtimeUrl, {
        auth: { token: bearer },
        transports: ['websocket', 'polling'],
        reconnection: false,
      });
      socket = currentSocket;
      if (typeof currentSocket.on !== 'function') return;
      const failed = () => {
        if (!isActive(currentSession) || socket !== currentSocket) return;
        detachSocket(true);
        clearToken();
        scheduleReconnect(currentSession);
      };
      socketListeners = {
        connect: () => {
          if (!isActive(currentSession)) return;
          retryIndex = 0;
          retryPending = false;
          if (retryTimer !== null) host.clearTimeout(retryTimer);
          retryTimer = null;
          syncUnread(currentSession);
          if (recentLoaded) syncRecent(currentSession);
        },
        connect_error: failed,
        disconnect: failed,
        'notification:new': value => acceptNotification(currentSession, value),
      };
      for (const [event, listener] of Object.entries(socketListeners)) currentSocket.on(event, listener);
    } catch {
      if (isActive(currentSession)) scheduleReconnect(currentSession);
    }
  }

  function setupUi(element, current) {
    if (typeof element.querySelector !== 'function') return false;
    trigger = element.querySelector('[data-notification-trigger]');
    panel = element.querySelector('[data-notification-panel]');
    badge = element.querySelector('[data-notification-badge]');
    itemsRoot = element.querySelector('[data-notification-items]');
    emptyState = element.querySelector('[data-notification-empty]');
    if (!trigger || !panel || !badge || !itemsRoot) return false;
    triggerListener = () => {
      const open = panel.hidden;
      panel.hidden = !open;
      trigger.setAttribute('aria-expanded', String(open));
      if (open && !recentLoaded) syncRecent(current);
    };
    trigger.addEventListener('click', triggerListener);
    logoutForm = element.closest('.site-nav')?.querySelector('[data-logout-form]') || null;
    if (logoutForm) {
      logoutListener = () => stop();
      logoutForm.addEventListener('submit', logoutListener);
    }
    return true;
  }

  function stop() {
    generation += 1;
    session = null;
    if (trigger && triggerListener) trigger.removeEventListener('click', triggerListener);
    if (logoutForm && logoutListener) logoutForm.removeEventListener('submit', logoutListener);
    if (host.document && visibilityListener) {
      host.document.removeEventListener('visibilitychange', visibilityListener);
    }
    clearRenderedListeners();
    detachSocket(true);
    if (retryTimer !== null) host.clearTimeout(retryTimer);
    retryTimer = null;
    clearToken();
    tokenPromise = null;
    root = null;
    trigger = null;
    panel = null;
    badge = null;
    itemsRoot = null;
    emptyState = null;
    logoutForm = null;
    triggerListener = null;
    logoutListener = null;
    visibilityListener = null;
    retryIndex = 0;
    retryPending = false;
    recentLoaded = false;
    recent = [];
    unread = 0;
    eventIds = new Set();
    readIds = new Set();
  }

  function start(element) {
    stop();
    root = element;
    const current = {
      generation,
      element,
      notificationEpoch: 0,
      recentEpoch: 0,
      countSync: null,
      recentSync: null,
    };
    session = current;
    const hasUi = setupUi(element, current);
    if (host.document) {
      visibilityListener = () => {
        if (!host.document.hidden && retryPending) scheduleReconnect(current);
      };
      host.document.addEventListener('visibilitychange', visibilityListener);
    }
    if (hasUi) syncUnread(current);
    connectSocket(current);
    return client;
  }

  const client = { start, stop, list, unreadCount, read, readAll };
  if (host.document) {
    const element = host.document.getElementById('notification-root');
    if (element) start(element);
  }
  return client;
}));
