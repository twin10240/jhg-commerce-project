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
  let outsideClickListener = null;
  let logoutListener = null;
  let visibilityListener = null;
  let socketListeners = null;
  let renderedListeners = [];
  let recentLoaded = false;
  let recent = [];
  let unread = 0;
  let socketIds = new Set();
  let readIds = new Set();
  let authoritativeReadAt = new Map();
  let inbox = null;

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
  const getNotification = id => request(
    `/api/v1/notifications/${encodeURIComponent(id)}`,
    {},
    value => {
      const item = parseNotification(value);
      if (item.id !== id) throw new Error('Mismatched notification response');
      return item;
    },
  );
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
        if (current.pendingReads !== 0) return;
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

  const isUnread = item => item.readAt === null && !readIds.has(item.id);

  function compareNotifications(left, right) {
    const createdAt = Date.parse(right.createdAt) - Date.parse(left.createdAt);
    if (createdAt !== 0) return createdAt;
    return left.id < right.id ? 1 : left.id > right.id ? -1 : 0;
  }

  function orderedNotifications(items) {
    const unique = new Map();
    for (const item of items) {
      if (!unique.has(item.id)) unique.set(item.id, item);
    }
    return [...unique.values()].sort(compareNotifications);
  }

  function mergeNotifications(existing, observed) {
    const preserveReadState = item => {
      if (item.readAt !== null) {
        authoritativeReadAt.set(item.id, item.readAt);
        return item;
      }
      const readAt = authoritativeReadAt.get(item.id);
      return readAt ? { ...item, readAt } : item;
    };
    const merged = new Map(existing.map(item => [item.id, preserveReadState(item)]));
    for (const item of observed) {
      merged.set(item.id, preserveReadState(item));
    }
    return orderedNotifications([...merged.values()]);
  }

  function renderRecent(current) {
    if (!isActive(current) || !itemsRoot || !host.document) return;
    clearRenderedListeners();
    const elements = recent.map(item => {
      const link = host.document.createElement('a');
      const title = host.document.createElement('strong');
      const body = host.document.createElement('span');
      const time = host.document.createElement('time');
      link.className = `notification-item${isUnread(item) ? ' unread' : ''}`;
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
        if (!isActive(current) || item.readAt !== null || readIds.has(item.id) ||
            (inbox && inbox.readAllPromise !== null)) return;
        readIds.add(item.id);
        current.notificationEpoch += 1;
        current.pendingReads += 1;
        link.classList.toggle('unread', false);
        showUnread(unread - 1);
        read(item.id).then(() => {
          if (!isActive(current)) return;
          current.notificationEpoch += 1;
          current.pendingReads -= 1;
          if (current.pendingReads === 0) syncUnread(current);
        });
      };
      link.addEventListener('click', listener);
      renderedListeners.push([link, listener]);
      return link;
    });
    itemsRoot.replaceChildren(...elements);
    if (emptyState) emptyState.hidden = elements.length !== 0;
  }

  function clearInboxRenderedListeners(state = inbox) {
    if (!state) return;
    for (const [element, listener] of state.renderedListeners) {
      element.removeEventListener('click', listener);
    }
    state.renderedListeners = [];
  }

  function showInboxError(state, message, retry) {
    state.errorMessage.textContent = message;
    state.error.hidden = false;
    state.retryAction = retry;
  }

  function clearInboxError(state) {
    state.error.hidden = true;
    state.retryAction = null;
  }

  function showInboxStatus(state, message, retry) {
    state.statusMessage.textContent = message;
    state.status.hidden = false;
    state.statusRetry.hidden = !retry;
    state.statusRetryAction = retry;
  }

  function clearInboxStatus(state) {
    state.status.hidden = true;
    state.statusRetryAction = null;
  }

  function renderInbox(current) {
    const state = inbox;
    if (!state || !isActive(current) || !host.document) return;
    clearInboxRenderedListeners(state);
    const visible = state.filter.checked
      ? state.rows.filter(isUnread)
      : state.rows;
    const elements = visible.map(item => {
      const unreadItem = isUnread(item);
      const row = host.document.createElement('article');
      const indicator = host.document.createElement('span');
      const content = host.document.createElement('div');
      const link = host.document.createElement('a');
      const body = host.document.createElement('p');
      const time = host.document.createElement('time');
      row.className = `notification-inbox-row${unreadItem ? ' unread' : ''}`;
      row.setAttribute('role', 'listitem');
      row.setAttribute('data-notification-id', item.id);
      indicator.className = 'notification-inbox-indicator';
      indicator.setAttribute('data-notification-unread-indicator', '');
      indicator.setAttribute('aria-label', '읽지 않음');
      indicator.hidden = !unreadItem;
      content.className = 'notification-inbox-content';
      link.className = 'notification-inbox-link';
      link.setAttribute('data-notification-link', '');
      link.setAttribute('href', item.linkUrl);
      link.textContent = item.title;
      body.className = 'notification-inbox-body';
      body.setAttribute('data-notification-body', '');
      body.textContent = item.body;
      time.className = 'notification-inbox-time';
      time.dateTime = item.createdAt;
      time.textContent = relativeTime(item.createdAt);
      content.append(link, body, time);
      row.append(indicator, content);
      if (unreadItem) {
        const action = host.document.createElement('button');
        const failed = state.readFailures.has(item.id);
        const pending = state.readInFlight.has(item.id);
        action.className = 'app-btn secondary notification-inbox-read';
        action.setAttribute('type', 'button');
        action.setAttribute('data-notification-mark-read', '');
        action.textContent = failed ? '다시 시도' : pending ? '처리 중' : '읽음으로 표시';
        action.disabled = pending || state.readAllPromise !== null || state.refreshPromise !== null;
        const listener = () => markInboxRead(current, item.id);
        action.addEventListener('click', listener);
        state.renderedListeners.push([action, listener]);
        row.append(action);
        if (failed) {
          const status = host.document.createElement('p');
          status.className = 'notification-inbox-row-status';
          status.setAttribute('data-notification-row-status', '');
          status.setAttribute('role', 'status');
          status.textContent = '읽음 처리하지 못했습니다.';
          row.append(status);
        }
      }
      return row;
    });
    state.items.replaceChildren(...elements);
    state.empty.textContent = state.filter.checked
      ? '읽지 않은 알림이 없습니다.'
      : '알림이 없습니다.';
    const busy = state.loading || state.refreshPromise !== null;
    state.empty.hidden = !state.loaded || busy || elements.length !== 0;
    state.loadingMessage.hidden = state.loaded || !busy;
    state.loadMore.hidden = !state.loaded || state.cursor === null;
    state.loadMore.disabled = busy || state.readAllPromise !== null;
    state.loadMore.textContent = busy && state.loaded ? '불러오는 중' : '더 보기';
    state.loadMore.setAttribute('aria-busy', String(busy && state.loaded));
    state.list.setAttribute('aria-busy', String(busy));
    state.readAll.disabled = state.readAllPromise !== null || state.readInFlight.size !== 0 ||
      state.loading || state.refreshPromise !== null || current.pendingReads !== 0;
  }

  function mergeInboxItems(state, items) {
    state.rows = mergeNotifications(state.rows, items);
  }

  function replaceInboxPage(state, page) {
    for (const item of page.items) readIds.delete(item.id);
    state.rows = mergeNotifications([], page.items);
    state.cursor = page.nextCursor;
    state.loaded = true;
    state.readFailures = new Set();
    clearInboxError(state);
  }

  async function loadInbox(current) {
    const state = inbox;
    if (!state || !isActive(current) || state.loading || state.cursor === null ||
        state.refreshPromise !== null || state.readAllPromise !== null) return;
    const cursor = state.loaded ? state.cursor : undefined;
    const pageEpoch = state.pageEpoch;
    state.loading = true;
    clearInboxError(state);
    renderInbox(current);
    const result = await list({ limit: 20, ...(cursor === undefined ? {} : { cursor }) });
    if (!isActive(current) || inbox !== state || pageEpoch !== state.pageEpoch) return;
    state.loading = false;
    if (result.kind === 'unavailable') {
      showInboxError(state, '알림을 불러오지 못했습니다.', () => loadInbox(current));
      renderInbox(current);
      return;
    }
    mergeInboxItems(state, result.items);
    state.cursor = result.nextCursor;
    state.loaded = true;
    clearInboxError(state);
    renderInbox(current);
  }

  function refreshInbox(current) {
    const state = inbox;
    if (!state || !isActive(current)) return Promise.resolve();
    if (state.readAllPromise !== null) {
      state.refreshPending = true;
      return Promise.resolve();
    }
    if (state.refreshPromise) return state.refreshPromise;
    state.refreshPending = false;
    const pageEpoch = ++state.pageEpoch;
    state.loading = false;
    let pending;
    pending = (async () => {
      while (isActive(current) && inbox === state && pageEpoch === state.pageEpoch) {
        const eventEpoch = state.eventEpoch;
        const result = await list({ limit: 20 });
        if (!isActive(current) || inbox !== state || pageEpoch !== state.pageEpoch) return;
        if (result.kind === 'unavailable') {
          showInboxError(state, '알림을 불러오지 못했습니다.', () => refreshInbox(current));
          return;
        }
        if (eventEpoch !== state.eventEpoch) continue;
        replaceInboxPage(state, result);
        return;
      }
    })().finally(() => {
      if (state.refreshPromise !== pending) return;
      state.refreshPromise = null;
      renderInbox(current);
      if (state.refreshPending) refreshInbox(current);
    });
    state.refreshPromise = pending;
    renderInbox(current);
    return pending;
  }

  async function markInboxRead(current, id) {
    const state = inbox;
    const item = state?.rows.find(row => row.id === id);
    if (!state || !item || !isActive(current) || !isUnread(item) ||
        state.readInFlight.has(id) || state.readAllPromise !== null ||
        state.refreshPromise !== null) return;
    state.readInFlight.add(id);
    state.readFailures.delete(id);
    current.notificationEpoch += 1;
    current.pendingReads += 1;
    renderInbox(current);
    const result = await read(id);
    if (!isActive(current) || inbox !== state) return;
    current.notificationEpoch += 1;
    current.pendingReads -= 1;
    state.readInFlight.delete(id);
    if (result.kind === 'unavailable') {
      state.readFailures.add(id);
    } else {
      readIds.add(id);
      state.rows = state.rows.map(row => row.id === id
        ? { ...row, readAt: new Date().toISOString() }
        : row);
      showUnread(unread - 1);
      renderRecent(current);
    }
    renderInbox(current);
    if (current.pendingReads === 0) syncUnread(current);
  }

  function replaceRecentPage(page) {
    for (const item of page.items) readIds.delete(item.id);
    recent = mergeNotifications([], page.items).slice(0, 5);
    recentLoaded = true;
    if (emptyState) emptyState.textContent = '새 알림이 없습니다.';
  }

  async function synchronizeReadAllState(current, state, pageEpoch) {
    while (isActive(current) && inbox === state && pageEpoch === state.pageEpoch) {
      const eventEpoch = state.eventEpoch;
      const [count, inboxPage, recentPage] = await Promise.all([
        unreadCount(),
        list({ limit: 20 }),
        list({ limit: 5 }),
      ]);
      if (!isActive(current) || inbox !== state || pageEpoch !== state.pageEpoch) return null;
      if (count.kind === 'unavailable' || inboxPage.kind === 'unavailable' ||
          recentPage.kind === 'unavailable') return false;
      if (eventEpoch !== state.eventEpoch) continue;
      replaceInboxPage(state, inboxPage);
      replaceRecentPage(recentPage);
      showUnread(count.count);
      clearInboxStatus(state);
      renderRecent(current);
      renderInbox(current);
      return true;
    }
    return null;
  }

  function retryReadAllSynchronization(current) {
    const state = inbox;
    if (!state || !isActive(current) || state.readAllPromise !== null ||
        state.readInFlight.size !== 0 || state.loading || state.refreshPromise !== null) return;
    const pageEpoch = ++state.pageEpoch;
    current.authorityEpoch += 1;
    current.notificationEpoch += 1;
    current.recentEpoch += 1;
    current.pendingReads += 1;
    let pending;
    pending = (async () => {
      const synchronized = await synchronizeReadAllState(current, state, pageEpoch);
      if (!isActive(current) || inbox !== state || synchronized === null) return;
      current.notificationEpoch += 1;
      current.authorityEpoch += 1;
      current.pendingReads -= 1;
      state.readAllPromise = null;
      state.refreshPending = false;
      if (!synchronized) {
        showInboxStatus(
          state,
          '알림 상태를 동기화하지 못했습니다.',
          () => retryReadAllSynchronization(current),
        );
      }
      renderInbox(current);
    })();
    state.readAllPromise = pending;
    clearInboxStatus(state);
    renderInbox(current);
  }

  function markInboxAllRead(current) {
    const state = inbox;
    if (!state || !isActive(current) || state.readAllPromise || state.readInFlight.size !== 0 ||
        state.loading || state.refreshPromise !== null || current.pendingReads !== 0) return;
    const pageEpoch = ++state.pageEpoch;
    state.loading = false;
    current.notificationEpoch += 1;
    current.recentEpoch += 1;
    current.authorityEpoch += 1;
    current.pendingReads += 1;
    let pending;
    pending = (async () => {
      const result = await readAll();
      if (!isActive(current) || inbox !== state) return;
      if (result.kind === 'unavailable') {
        current.notificationEpoch += 1;
        current.authorityEpoch += 1;
        current.pendingReads -= 1;
        state.readAllPromise = null;
        showInboxStatus(
          state,
          '모두 읽음 처리하지 못했습니다.',
          () => markInboxAllRead(current),
        );
        renderInbox(current);
        if (current.pendingReads === 0) syncUnread(current);
        if (state.refreshPending) refreshInbox(current);
        return;
      }
      const synchronized = await synchronizeReadAllState(current, state, pageEpoch);
      if (!isActive(current) || inbox !== state || synchronized === null) return;
      current.notificationEpoch += 1;
      current.authorityEpoch += 1;
      current.pendingReads -= 1;
      state.readAllPromise = null;
      state.refreshPending = false;
      if (!synchronized) {
        showInboxStatus(
          state,
          '알림 상태를 동기화하지 못했습니다.',
          () => retryReadAllSynchronization(current),
        );
      }
      renderInbox(current);
    })();
    state.readAllPromise = pending;
    clearInboxStatus(state);
    renderInbox(current);
  }

  function setupInbox(current) {
    if (!host.document || typeof host.document.querySelector !== 'function') return false;
    const element = host.document.querySelector('[data-notification-inbox]');
    if (!element) return false;
    const state = {
      element,
      filter: element.querySelector('[data-notification-unread-filter]'),
      readAll: element.querySelector('[data-notification-read-all]'),
      list: element.querySelector('[data-notification-inbox-list]'),
      loadingMessage: element.querySelector('[data-notification-inbox-loading]'),
      empty: element.querySelector('[data-notification-inbox-empty]'),
      items: element.querySelector('[data-notification-inbox-items]'),
      error: element.querySelector('[data-notification-inbox-error]'),
      errorMessage: element.querySelector('[data-notification-inbox-error-message]'),
      retry: element.querySelector('[data-notification-inbox-retry]'),
      status: element.querySelector('[data-notification-inbox-status]'),
      statusMessage: element.querySelector('[data-notification-inbox-status-message]'),
      statusRetry: element.querySelector('[data-notification-inbox-status-retry]'),
      loadMore: element.querySelector('[data-notification-load-more]'),
      rows: [],
      cursor: undefined,
      loaded: false,
      loading: false,
      retryAction: null,
      statusRetryAction: null,
      readInFlight: new Set(),
      readFailures: new Set(),
      readAllPromise: null,
      pageEpoch: 0,
      eventEpoch: 0,
      refreshPromise: null,
      refreshPending: false,
      renderedListeners: [],
      listeners: [],
    };
    if ([state.filter, state.readAll, state.list, state.loadingMessage, state.empty,
      state.items, state.error, state.errorMessage, state.retry, state.status,
      state.statusMessage, state.statusRetry, state.loadMore].some(value => !value)) return false;
    inbox = state;
    const on = (elementToBind, type, listener) => {
      elementToBind.addEventListener(type, listener);
      state.listeners.push([elementToBind, type, listener]);
    };
    on(state.filter, 'change', () => renderInbox(current));
    on(state.readAll, 'click', () => markInboxAllRead(current));
    on(state.retry, 'click', () => state.retryAction?.());
    on(state.statusRetry, 'click', () => state.statusRetryAction?.());
    on(state.loadMore, 'click', () => loadInbox(current));
    loadInbox(current);
    return true;
  }

  function stopInbox() {
    if (!inbox) return;
    clearInboxRenderedListeners(inbox);
    for (const [element, type, listener] of inbox.listeners) {
      element.removeEventListener(type, listener);
    }
    inbox = null;
  }

  function syncRecent(current) {
    if (!isActive(current)) return Promise.resolve();
    if (inbox?.readAllPromise) return Promise.resolve();
    if (current.recentSync) return current.recentSync;
    let pending;
    pending = (async () => {
      while (isActive(current)) {
        const epoch = current.recentEpoch;
        const result = await list({ limit: 5 });
        if (!isActive(current)) return;
        if (inbox?.readAllPromise) return;
        if (result.kind === 'unavailable') {
          if (emptyState) {
            emptyState.textContent = '알림을 불러오지 못했습니다.';
            emptyState.hidden = recent.length !== 0;
          }
          return;
        }
        if (epoch !== current.recentEpoch) continue;
        replaceRecentPage(result);
        renderRecent(current);
        return;
      }
    })().finally(() => {
      if (current.recentSync === pending) current.recentSync = null;
    });
    current.recentSync = pending;
    return pending;
  }

  async function acceptNotification(current, value) {
    if (!isActive(current)) return;
    let payload;
    try {
      payload = parseNotification(value);
    } catch {
      return;
    }
    if (socketIds.has(payload.id)) return;
    socketIds.add(payload.id);
    let item;
    while (isActive(current)) {
      if (inbox?.readAllPromise) await inbox.readAllPromise;
      if (!isActive(current)) return;
      const authorityEpoch = current.authorityEpoch;
      const result = await getNotification(payload.id);
      if (!isActive(current)) return;
      if (authorityEpoch !== current.authorityEpoch || inbox?.readAllPromise) continue;
      if (result.kind === 'unavailable') {
        socketIds.delete(payload.id);
        current.notificationEpoch += 1;
        current.recentEpoch += 1;
        if (inbox) inbox.eventEpoch += 1;
        syncUnread(current);
        if (recentLoaded) syncRecent(current);
        if (inbox) refreshInbox(current);
        return;
      }
      item = result;
      break;
    }
    if (!item) return;
    const existing = recent.find(row => row.id === item.id) ||
      inbox?.rows.find(row => row.id === item.id);
    const wasUnread = existing ? isUnread(existing) : false;
    current.notificationEpoch += 1;
    current.recentEpoch += 1;
    recent = mergeNotifications(recent, [item]).slice(0, 5);
    if (inbox) {
      inbox.eventEpoch += 1;
      mergeInboxItems(inbox, [item]);
      renderInbox(current);
    }
    const accepted = recent.find(row => row.id === item.id) ||
      inbox?.rows.find(row => row.id === item.id);
    if (!wasUnread && accepted && isUnread(accepted)) showUnread(unread + 1);
    renderRecent(current);
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
          currentSession.notificationEpoch += 1;
          currentSession.recentEpoch += 1;
          retryIndex = 0;
          retryPending = false;
          if (retryTimer !== null) host.clearTimeout(retryTimer);
          retryTimer = null;
          syncUnread(currentSession);
          if (currentSession.recentSync || recentLoaded) syncRecent(currentSession);
          refreshInbox(currentSession);
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
    outsideClickListener = event => {
      if (panel.hidden || element.contains(event.target)) return;
      panel.hidden = true;
      trigger.setAttribute('aria-expanded', 'false');
    };
    host.document?.addEventListener('click', outsideClickListener);
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
    if (host.document && outsideClickListener) {
      host.document.removeEventListener('click', outsideClickListener);
    }
    if (logoutForm && logoutListener) logoutForm.removeEventListener('submit', logoutListener);
    if (host.document && visibilityListener) {
      host.document.removeEventListener('visibilitychange', visibilityListener);
    }
    clearRenderedListeners();
    stopInbox();
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
    outsideClickListener = null;
    logoutListener = null;
    visibilityListener = null;
    retryIndex = 0;
    retryPending = false;
    recentLoaded = false;
    recent = [];
    unread = 0;
    socketIds = new Set();
    readIds = new Set();
    authoritativeReadAt = new Map();
  }

  function start(element) {
    stop();
    root = element;
    const current = {
      generation,
      element,
      notificationEpoch: 0,
      recentEpoch: 0,
      pendingReads: 0,
      authorityEpoch: 0,
      countSync: null,
      recentSync: null,
    };
    session = current;
    const hasUi = setupUi(element, current);
    setupInbox(current);
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

  const client = { start, stop, list, unreadCount, getNotification, read, readAll };
  if (host.document) {
    const element = host.document.getElementById('notification-root');
    if (element) start(element);
  }
  return client;
}));
