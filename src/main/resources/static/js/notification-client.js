(function (host, factory) {
  const client = factory(host);
  if (typeof module === 'object' && module.exports) module.exports = client;
  host.NotificationClient = client;
}(typeof globalThis === 'undefined' ? window : globalThis, host => {
  let token = null;
  let expiresAt = 0;
  let tokenPromise = null;
  let socket = null;
  let retryTimer = null;
  let root = null;
  let generation = 0;

  const unavailable = () => ({ kind: 'unavailable' });
  const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const isString = value => typeof value === 'string';
  const isDateString = value => isString(value) && Number.isFinite(Date.parse(value));

  function clearToken() {
    token = null;
    expiresAt = 0;
    if (retryTimer !== null) host.clearTimeout(retryTimer);
    retryTimer = null;
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
        if (retryTimer !== null) host.clearTimeout(retryTimer);
        retryTimer = host.setTimeout(clearToken, expiresAt - Date.now() - 15_000);
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
    '/api/v1/notifications/read-all', { method: 'POST' }, value => parseCount(value, 'changedCount'));

  function stop() {
    generation += 1;
    root = null;
    clearToken();
    tokenPromise = null;
    if (socket) socket.disconnect();
    socket = null;
  }

  function start(element) {
    stop();
    root = element;
    if (typeof host.io === 'function') {
      getToken().then(value => {
        if (root !== element) return;
        const config = configuration();
        socket = host.io(config.realtimeUrl, {
          auth: { token: value },
          transports: ['websocket', 'polling'],
          reconnection: false,
        });
      }).catch(() => {});
    }
    return client;
  }

  const client = { start, stop, list, unreadCount, read, readAll };
  if (host.document) {
    const element = host.document.getElementById('notification-root');
    if (element) start(element);
  }
  return client;
}));
