import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';

import NotificationClient from '../../main/resources/static/js/notification-client.js';

const original = {
  document: globalThis.document,
  fetch: globalThis.fetch,
  io: globalThis.io,
  setTimeout: globalThis.setTimeout,
  clearTimeout: globalThis.clearTimeout,
  localStorage: globalThis.localStorage,
  sessionStorage: globalThis.sessionStorage,
};

class FakeClassList {
  constructor(element) {
    this.element = element;
  }

  values() {
    return new Set(this.element.className.split(/\s+/).filter(Boolean));
  }

  contains(name) {
    return this.values().has(name);
  }

  toggle(name, force) {
    const values = this.values();
    const add = force === undefined ? !values.has(name) : force;
    if (add) values.add(name);
    else values.delete(name);
    this.element.className = [...values].join(' ');
    return add;
  }
}

const dataKey = name => name.replace(/^data-/, '').replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());

class FakeElement {
  constructor(tagName = 'div') {
    this.tagName = tagName.toUpperCase();
    this.dataset = {};
    this.attributes = new Map();
    this.children = [];
    this.parentElement = null;
    this.hidden = false;
    this.className = '';
    this.classList = new FakeClassList(this);
    this.listeners = new Map();
    this.textContent = '';
  }

  set innerHTML(_value) {
    throw new Error('notification content must not use innerHTML');
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
    if (name.startsWith('data-')) this.dataset[dataKey(name)] = String(value);
    if (name === 'class') this.className = String(value);
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  append(...children) {
    for (const child of children) {
      child.parentElement = this;
      this.children.push(child);
    }
  }

  replaceChildren(...children) {
    this.children = [];
    this.append(...children);
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(listener);
  }

  removeEventListener(type, listener) {
    this.listeners.get(type)?.delete(listener);
  }

  dispatch(type) {
    const event = { type, currentTarget: this, target: this };
    for (const listener of [...(this.listeners.get(type) || [])]) listener(event);
  }

  matches(selector) {
    if (selector.startsWith('.')) return this.classList.contains(selector.slice(1));
    const data = selector.match(/^\[data-([a-z-]+)(?:="([^"]*)")?\]$/);
    if (data) {
      const value = this.dataset[dataKey(`data-${data[1]}`)];
      return value !== undefined && (data[2] === undefined || value === data[2]);
    }
    return false;
  }

  querySelector(selector) {
    for (const child of this.children) {
      if (child.matches(selector)) return child;
      const nested = child.querySelector(selector);
      if (nested) return nested;
    }
    return null;
  }

  closest(selector) {
    for (let current = this; current; current = current.parentElement) {
      if (current.matches(selector)) return current;
    }
    return null;
  }
}

class FakeDocument {
  constructor() {
    this.hidden = false;
    this.listeners = new Map();
    this.children = [];
  }

  createElement(tagName) {
    return new FakeElement(tagName);
  }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(listener);
  }

  removeEventListener(type, listener) {
    this.listeners.get(type)?.delete(listener);
  }

  dispatch(type) {
    for (const listener of [...(this.listeners.get(type) || [])]) listener({ type });
  }

  append(...children) {
    this.children.push(...children);
  }

  querySelector(selector) {
    for (const child of this.children) {
      if (child.matches(selector)) return child;
      const nested = child.querySelector(selector);
      if (nested) return nested;
    }
    return null;
  }
}

class FakeSocket {
  constructor() {
    this.listeners = new Map();
    this.disconnected = 0;
  }

  on(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(listener);
    return this;
  }

  off(type, listener) {
    this.listeners.get(type)?.delete(listener);
    return this;
  }

  serverEmit(type, value) {
    for (const listener of [...(this.listeners.get(type) || [])]) listener(value);
  }

  disconnect() {
    this.disconnected += 1;
  }
}

const uiRoot = () => {
  const document = new FakeDocument();
  const nav = new FakeElement('nav');
  nav.className = 'site-nav';
  const element = new FakeElement();
  Object.assign(element.dataset, root().dataset);
  const trigger = new FakeElement('button');
  trigger.setAttribute('data-notification-trigger', '');
  trigger.setAttribute('aria-expanded', 'false');
  const badge = new FakeElement('span');
  badge.setAttribute('data-notification-badge', '');
  badge.hidden = true;
  badge.textContent = '0';
  const panel = new FakeElement('section');
  panel.setAttribute('data-notification-panel', '');
  panel.hidden = true;
  const items = new FakeElement();
  items.setAttribute('data-notification-items', '');
  const empty = new FakeElement('p');
  empty.setAttribute('data-notification-empty', '');
  empty.hidden = true;
  empty.textContent = '새 알림이 없습니다.';
  panel.append(items, empty);
  element.append(trigger, badge, panel);
  const logout = new FakeElement('form');
  logout.setAttribute('data-logout-form', '');
  nav.append(element, logout);
  globalThis.document = document;
  return { document, element, trigger, badge, panel, items, empty, logout };
};

const inboxRoot = () => {
  const fixture = uiRoot();
  const inbox = new FakeElement('main');
  inbox.setAttribute('data-notification-inbox', '');
  const filter = new FakeElement('input');
  filter.setAttribute('data-notification-unread-filter', '');
  filter.checked = false;
  const readAll = new FakeElement('button');
  readAll.setAttribute('data-notification-read-all', '');
  const list = new FakeElement('section');
  list.setAttribute('data-notification-inbox-list', '');
  list.setAttribute('aria-busy', 'true');
  const loading = new FakeElement('p');
  loading.setAttribute('data-notification-inbox-loading', '');
  const empty = new FakeElement('p');
  empty.setAttribute('data-notification-inbox-empty', '');
  empty.hidden = true;
  const items = new FakeElement('div');
  items.setAttribute('data-notification-inbox-items', '');
  const error = new FakeElement('div');
  error.setAttribute('data-notification-inbox-error', '');
  error.hidden = true;
  const errorMessage = new FakeElement('p');
  errorMessage.setAttribute('data-notification-inbox-error-message', '');
  const retry = new FakeElement('button');
  retry.setAttribute('data-notification-inbox-retry', '');
  error.append(errorMessage, retry);
  const status = new FakeElement('div');
  status.setAttribute('data-notification-inbox-status', '');
  status.hidden = true;
  const statusMessage = new FakeElement('p');
  statusMessage.setAttribute('data-notification-inbox-status-message', '');
  const statusRetry = new FakeElement('button');
  statusRetry.setAttribute('data-notification-inbox-status-retry', '');
  status.append(statusMessage, statusRetry);
  const loadMore = new FakeElement('button');
  loadMore.setAttribute('data-notification-load-more', '');
  loadMore.textContent = '더 보기';
  loadMore.hidden = true;
  list.append(loading, empty, items, error, status, loadMore);
  inbox.append(filter, readAll, list);
  fixture.document.append(inbox);
  return {
    ...fixture,
    inbox,
    filter,
    readAll,
    list,
    loading,
    inboxEmpty: empty,
    inboxItems: items,
    error,
    errorMessage,
    retry,
    status,
    statusMessage,
    statusRetry,
    loadMore,
  };
};

const inboxRow = (fixture, id) => fixture.inboxItems.children.find(
  row => row.dataset.notificationId === id,
);

const readButton = row => row.querySelector('[data-notification-mark-read]');

const settle = async () => {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
};

const root = () => ({
  dataset: {
    realtimeUrl: 'https://realtime.example.test',
    csrfHeader: 'X-CSRF-TOKEN',
    csrfToken: 'csrf-value',
  },
});

const response = (status, body) => ({
  status,
  ok: status >= 200 && status < 300,
  json: async () => body,
});

const token = value => ({
  token: value,
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
});

const notification = (overrides = {}) => ({
  id: 'd01bf93d-2884-4094-88bc-28a8a88d2ac4',
  type: 'DELIVERY_COMPLETED',
  title: 'Delivery completed',
  body: 'Order #12 was delivered.',
  linkUrl: '/orders/12',
  createdAt: '2026-08-30T06:30:01.000Z',
  readAt: null,
  ...overrides,
});

afterEach(() => {
  NotificationClient.stop();
  for (const [name, value] of Object.entries(original)) {
    if (value === undefined) delete globalThis[name];
    else globalThis[name] = value;
  }
});

test('concurrent API calls share one CSRF-protected token request', async () => {
  let releaseToken;
  let tokenRequests = 0;
  const calls = [];
  globalThis.fetch = (url, options = {}) => {
    calls.push({ url, options });
    if (url === '/api/realtime/token') {
      tokenRequests += 1;
      return new Promise(resolve => {
        releaseToken = () => resolve(response(200, token('shared-token')));
      });
    }
    if (url.endsWith('/unread-count')) return Promise.resolve(response(200, { count: 2 }));
    return Promise.resolve(response(200, { items: [], nextCursor: null }));
  };

  NotificationClient.start(root());
  const list = NotificationClient.list({ limit: 5 });
  const count = NotificationClient.unreadCount();

  assert.equal(tokenRequests, 1);
  assert.deepEqual(calls[0], {
    url: '/api/realtime/token',
    options: {
      method: 'POST',
      headers: { 'X-CSRF-TOKEN': 'csrf-value' },
    },
  });
  releaseToken();
  assert.deepEqual(await list, { items: [], nextCursor: null });
  assert.deepEqual(await count, { count: 2 });
  assert.equal(tokenRequests, 1);
  assert.ok(calls.slice(1).every(call => call.options.credentials !== 'include'));
});

test('401 clears the token and retries once with a newly issued token', async () => {
  let tokenRequests = 0;
  const authorizations = [];
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') {
      tokenRequests += 1;
      return response(200, token(`token-${tokenRequests}`));
    }
    authorizations.push(options.headers.Authorization);
    return authorizations.length === 1
      ? response(401)
      : response(200, { count: 3 });
  };

  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.unreadCount(), { count: 3 });
  assert.equal(tokenRequests, 2);
  assert.deepEqual(authorizations, ['Bearer token-1', 'Bearer token-2']);
});

test('a repeated authorization failure returns unavailable without another retry', async () => {
  let tokenRequests = 0;
  let apiRequests = 0;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') {
      tokenRequests += 1;
      return response(200, token(`token-${tokenRequests}`));
    }
    apiRequests += 1;
    return response(401);
  };

  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.list(), { kind: 'unavailable' });
  assert.equal(tokenRequests, 2);
  assert.equal(apiRequests, 2);
});

test('tokens stay out of storage, DOM bootstrap data, and the public API', async () => {
  const storage = new Proxy({}, {
    get() {
      throw new Error('browser storage must not be used');
    },
  });
  globalThis.localStorage = storage;
  globalThis.sessionStorage = storage;
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('memory-only-token'))
    : response(200, { count: 0 });
  const element = root();

  NotificationClient.start(element);

  assert.deepEqual(await NotificationClient.unreadCount(), { count: 0 });
  assert.deepEqual(Object.keys(element.dataset).sort(), [
    'csrfHeader',
    'csrfToken',
    'realtimeUrl',
  ]);
  assert.equal(NotificationClient.token, undefined);
  assert.ok(!JSON.stringify(NotificationClient).includes('memory-only-token'));
});

test('stop clears the refresh timer and disconnects the socket', async () => {
  const timers = [];
  const cleared = [];
  let disconnected = 0;
  globalThis.setTimeout = (callback, delay) => {
    const handle = { callback, delay };
    timers.push(handle);
    return handle;
  };
  globalThis.clearTimeout = handle => cleared.push(handle);
  globalThis.fetch = async () => response(200, token('socket-token'));
  globalThis.io = () => ({
    disconnect() {
      disconnected += 1;
    },
  });

  NotificationClient.start(root());
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(timers.length, 1);

  NotificationClient.stop();

  assert.equal(disconnected, 1);
  assert.deepEqual(cleared, timers);
});

test('list validates required fields and normalizes unsafe links', async () => {
  const items = [
    notification(),
    notification({ id: 'absolute', linkUrl: 'https://evil.example/path' }),
    notification({ id: 'protocol-relative', linkUrl: '//evil.example/path' }),
    notification({ id: 'not-relative', linkUrl: 'orders/12' }),
    notification({ id: 'backslash', linkUrl: '/\\evil.example/path' }),
    notification({ id: 'control-character', linkUrl: '/\n/evil.example/path' }),
    notification({ id: 'encoded-slash', linkUrl: '/safe%2f%2fevil.example/path' }),
    notification({ id: 'encoded-backslash', linkUrl: '/%5C%5cevil.example/path' }),
    notification({ id: 'encoded-null', linkUrl: '/safe%00/path' }),
    notification({ id: 'encoded-control', linkUrl: '/safe%1F/path' }),
    notification({ id: 'encoded-delete', linkUrl: '/safe%7f/path' }),
    notification({ id: 'normal-encoding', linkUrl: '/orders/12?label=%ED%95%9C%EA%B8%80' }),
  ];
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('list-token'))
    : response(200, { items, nextCursor: 'next-page' });

  NotificationClient.start(root());

  const result = await NotificationClient.list();
  assert.deepEqual(result.items.map(item => item.linkUrl), [
    '/orders/12',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/notifications',
    '/orders/12?label=%ED%95%9C%EA%B8%80',
  ]);
  assert.equal(result.nextCursor, 'next-page');
  assert.ok(result.items.every(item => Object.getPrototypeOf(item) === Object.prototype));
});

test('malformed REST responses return unavailable', async () => {
  let apiResponse = { items: [notification({ title: undefined })], nextCursor: null };
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('validation-token'))
    : response('changedCount' in apiResponse ? 201 : 200, apiResponse);
  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.list(), { kind: 'unavailable' });
  apiResponse = { count: -1 };
  assert.deepEqual(await NotificationClient.unreadCount(), { kind: 'unavailable' });
  apiResponse = { changedCount: '1' };
  assert.deepEqual(await NotificationClient.readAll(), { kind: 'unavailable' });
});

test('read methods use the authenticated Node REST endpoints', async () => {
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('read-token'));
    calls.push({ url, options });
    return url.endsWith('/read-all')
      ? response(201, { changedCount: 4 })
      : response(204);
  };
  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.read('notification-id'), {});
  assert.deepEqual(await NotificationClient.readAll(), { changedCount: 4 });
  assert.deepEqual(calls.map(call => [call.url, call.options.method]), [
    ['https://realtime.example.test/api/v1/notifications/notification-id/read', 'PATCH'],
    ['https://realtime.example.test/api/v1/notifications/read-all', 'POST'],
  ]);
});

test('JSON endpoints reject a 204 response with no required body', async () => {
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('status-token'))
    : response(204);
  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.list(), { kind: 'unavailable' });
  assert.deepEqual(await NotificationClient.unreadCount(), { kind: 'unavailable' });
  assert.deepEqual(await NotificationClient.readAll(), { kind: 'unavailable' });
});

test('read accepts only the Node API 204 response contract', async () => {
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('read-status-token'))
    : response(200, {});
  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.read('notification-id'), { kind: 'unavailable' });
});

test('read-all rejects 200 instead of the Node API 201 response contract', async () => {
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('read-all-status-token'))
    : response(200, { changedCount: 1 });
  NotificationClient.start(root());

  assert.deepEqual(await NotificationClient.readAll(), { kind: 'unavailable' });
});

test('start fetches unread count and shows a stable capped badge', async () => {
  const fixture = uiRoot();
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token('badge-token'))
    : response(200, { count: 104 });

  NotificationClient.start(fixture.element);
  await settle();

  assert.equal(fixture.badge.textContent, '99+');
  assert.equal(fixture.badge.hidden, false);
});

test('the recent panel fetches five items only on first open and renders text safely', async () => {
  const fixture = uiRoot();
  const dangerous = notification({
    title: '<img src=x onerror=alert(1)>',
    body: '<script>steal()</script>',
    linkUrl: 'https://evil.example/path',
  });
  let listRequests = 0;
  let readRequests = 0;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('panel-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: readRequests === 0 ? 1 : 0 });
    if (url.includes('/api/v1/notifications?')) {
      listRequests += 1;
      assert.match(url, /[?&]limit=5(?:&|$)/);
      return response(200, { items: [dangerous], nextCursor: null });
    }
    if (options.method === 'PATCH') {
      readRequests += 1;
      return response(204);
    }
    throw new Error(`unexpected request: ${url}`);
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  fixture.trigger.dispatch('click');
  fixture.trigger.dispatch('click');
  await settle();

  assert.equal(listRequests, 1);
  assert.equal(fixture.trigger.getAttribute('aria-expanded'), 'true');
  assert.equal(fixture.items.children.length, 1);
  const item = fixture.items.children[0];
  assert.equal(item.getAttribute('href'), '/notifications');
  assert.equal(item.children[0].textContent, dangerous.title);
  assert.equal(item.children[1].textContent, dangerous.body);
  assert.ok(item.children[2].textContent.length > 0);

  item.dispatch('click');
  item.dispatch('click');
  await settle();
  assert.equal(fixture.badge.hidden, true);
  assert.equal(readRequests, 1);
});

test('notification:new prepends once per ID and increments the unread badge once', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  let ioCall;
  let countRequests = 0;
  globalThis.io = (url, options) => {
    ioCall = { url, options };
    return socket;
  };
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('socket-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      return response(200, { count: countRequests === 1 ? 2 : 3 });
    }
    return response(200, { items: [], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  const incoming = notification({ title: 'New delivery' });
  socket.serverEmit('notification:new', incoming);
  socket.serverEmit('notification:new', incoming);
  await settle();

  assert.deepEqual(ioCall, {
    url: 'https://realtime.example.test',
    options: {
      auth: { token: 'socket-token' },
      transports: ['websocket', 'polling'],
      reconnection: false,
    },
  });
  assert.equal(fixture.items.children.length, 1);
  assert.equal(fixture.items.children[0].children[0].textContent, 'New delivery');
  assert.equal(fixture.badge.textContent, '3');
  assert.equal(countRequests, 2);
});

test('socket retries back off, pause while hidden, reset on connect, and stop on logout', async () => {
  const fixture = uiRoot();
  const timers = [];
  const sockets = [];
  globalThis.setTimeout = (callback, delay) => {
    const handle = { callback, delay, cleared: false };
    timers.push(handle);
    return handle;
  };
  globalThis.clearTimeout = handle => {
    handle.cleared = true;
  };
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token(`retry-token-${sockets.length + 1}`))
    : response(200, { count: 0 });
  globalThis.io = () => {
    const socket = new FakeSocket();
    sockets.push(socket);
    return socket;
  };

  NotificationClient.start(fixture.element);
  await settle();
  sockets[0].serverEmit('connect_error', new Error('expired'));
  const retry1 = timers.find(timer => timer.delay === 1_000 && !timer.cleared);
  assert.ok(retry1);
  retry1.callback();
  await settle();

  fixture.document.hidden = true;
  sockets[1].serverEmit('connect_error', new Error('offline'));
  assert.equal(timers.filter(timer => timer.delay === 2_000 && !timer.cleared).length, 0);
  fixture.document.hidden = false;
  fixture.document.dispatch('visibilitychange');
  const retry2 = timers.find(timer => timer.delay === 2_000 && !timer.cleared);
  assert.ok(retry2);
  retry2.callback();
  await settle();

  sockets[2].serverEmit('connect');
  sockets[2].serverEmit('disconnect', 'io server disconnect');
  assert.ok(timers.some(timer => timer.delay === 1_000 && !timer.cleared));

  fixture.logout.dispatch('submit');
  assert.equal(sockets[2].disconnected, 1);
  assert.equal(fixture.document.listeners.get('visibilitychange')?.size ?? 0, 0);
});

test('consecutive socket failures use the complete capped retry schedule', async () => {
  const fixture = uiRoot();
  const timers = [];
  const sockets = [];
  globalThis.setTimeout = (callback, delay) => {
    const handle = { callback, delay, cleared: false };
    timers.push(handle);
    return handle;
  };
  globalThis.clearTimeout = handle => {
    handle.cleared = true;
  };
  globalThis.fetch = async url => url === '/api/realtime/token'
    ? response(200, token(`backoff-token-${sockets.length + 1}`))
    : response(200, { count: 0 });
  globalThis.io = () => {
    const socket = new FakeSocket();
    sockets.push(socket);
    return socket;
  };

  NotificationClient.start(fixture.element);
  await settle();
  for (const delay of [1_000, 2_000, 5_000, 10_000, 30_000, 30_000]) {
    sockets.at(-1).serverEmit('connect_error', new Error('offline'));
    const retry = timers.find(timer => timer.delay === delay && !timer.cleared);
    assert.ok(retry, `missing ${delay}ms retry`);
    retry.cleared = true;
    retry.callback();
    await settle();
  }

  assert.deepEqual(
    timers.filter(timer => timer.delay <= 30_000).map(timer => timer.delay),
    [1_000, 2_000, 5_000, 10_000, 30_000, 30_000],
  );
  NotificationClient.stop();
  assert.equal(fixture.document.listeners.get('visibilitychange')?.size ?? 0, 0);
  assert.equal(sockets.at(-1).disconnected, 1);
});

test('stop prevents an in-flight recent request from restoring DOM listeners', async () => {
  const fixture = uiRoot();
  let releaseList;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('stop-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 0 });
    return new Promise(resolve => {
      releaseList = () => resolve(response(200, { items: [notification()], nextCursor: null }));
    });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  NotificationClient.stop();
  releaseList();
  await settle();

  assert.equal(fixture.items.children.length, 0);
  assert.equal(fixture.trigger.listeners.get('click')?.size ?? 0, 0);
  assert.equal(fixture.logout.listeners.get('submit')?.size ?? 0, 0);
  assert.equal(fixture.document.listeners.get('visibilitychange')?.size ?? 0, 0);
});

test('connect resyncs authoritative unread count and an already loaded recent panel', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  const oldItem = notification({ id: 'old', title: 'Old' });
  const offlineItem = notification({ id: 'offline', title: 'Offline' });
  let countRequests = 0;
  let listRequests = 0;
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('resync-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      return response(200, { count: countRequests === 1 ? 1 : 2 });
    }
    listRequests += 1;
    return response(200, {
      items: listRequests === 1 ? [oldItem] : [offlineItem, oldItem],
      nextCursor: null,
    });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  socket.serverEmit('connect');
  await settle();

  assert.equal(countRequests, 2);
  assert.equal(listRequests, 2);
  assert.equal(fixture.badge.textContent, '2');
  assert.deepEqual(
    fixture.items.children.map(item => item.children[0].textContent),
    ['Offline', 'Old'],
  );
});

test('an event racing a count response that already includes it is not double counted', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  let releaseFirstCount;
  let countRequests = 0;
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('count-race-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      if (countRequests === 1) {
        return new Promise(resolve => {
          releaseFirstCount = () => resolve(response(200, { count: 3 }));
        });
      }
      return response(200, { count: 3 });
    }
    return response(200, { items: [], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  socket.serverEmit('notification:new', notification({ id: 'racing-event' }));
  assert.equal(fixture.badge.textContent, '1');
  releaseFirstCount();
  await settle();

  assert.equal(countRequests, 2);
  assert.equal(fixture.badge.textContent, '3');
});

test('a failed first recent request retries on reopen and restores the empty message', async () => {
  const fixture = uiRoot();
  let listRequests = 0;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('recent-retry-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 0 });
    listRequests += 1;
    return listRequests === 1
      ? response(503)
      : response(200, { items: [], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  assert.equal(fixture.empty.textContent, '알림을 불러오지 못했습니다.');
  fixture.trigger.dispatch('click');
  fixture.trigger.dispatch('click');
  await settle();

  assert.equal(listRequests, 2);
  assert.equal(fixture.empty.textContent, '새 알림이 없습니다.');
  assert.equal(fixture.empty.hidden, false);
});

test('restarting the same element ignores stale count and recent responses', async () => {
  const fixture = uiRoot();
  const staleItem = notification({ id: 'stale', title: 'Stale' });
  const currentItem = notification({ id: 'current', title: 'Current' });
  let countRequests = 0;
  let listRequests = 0;
  let releaseStaleCount;
  let releaseStaleList;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token(`restart-token-${countRequests + 1}`));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      if (countRequests === 1) {
        return new Promise(resolve => {
          releaseStaleCount = () => resolve(response(200, { count: 99 }));
        });
      }
      return response(200, { count: 7 });
    }
    listRequests += 1;
    if (listRequests === 1) {
      return new Promise(resolve => {
        releaseStaleList = () => resolve(response(200, { items: [staleItem], nextCursor: null }));
      });
    }
    return response(200, { items: [currentItem], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  fixture.panel.hidden = true;
  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  releaseStaleCount();
  releaseStaleList();
  await settle();

  assert.equal(fixture.badge.textContent, '7');
  assert.deepEqual(
    fixture.items.children.map(item => item.children[0].textContent),
    ['Current'],
  );
});

test('recent resync re-reads when a socket event races its response', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  const oldItem = notification({ id: 'race-old', title: 'Old' });
  const incoming = notification({ id: 'race-new', title: 'New' });
  let listRequests = 0;
  let releaseRacingList;
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('recent-race-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 2 });
    listRequests += 1;
    if (listRequests === 2) {
      return new Promise(resolve => {
        releaseRacingList = () => resolve(response(200, { items: [incoming, oldItem], nextCursor: null }));
      });
    }
    return response(200, { items: listRequests === 1 ? [oldItem] : [incoming, oldItem], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  socket.serverEmit('connect');
  await settle();
  assert.equal(typeof releaseRacingList, 'function');
  socket.serverEmit('notification:new', incoming);
  releaseRacingList();
  await settle();

  assert.equal(listRequests, 3);
  assert.deepEqual(
    fixture.items.children.map(item => item.children[0].textContent),
    ['New', 'Old'],
  );
});

test('a socket event after the initial count still reconciles with the authoritative count', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  let countRequests = 0;
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('event-sync-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      return response(200, { count: countRequests === 1 ? 2 : 3 });
    }
    return response(200, { items: [], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  socket.serverEmit('notification:new', notification({ id: 'event-after-count' }));
  await settle();

  assert.equal(countRequests, 2);
  assert.equal(fixture.badge.textContent, '3');
});

test('a loaded panel keeps resyncing after one reconnect list failure', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  const oldItem = notification({ id: 'loaded-old', title: 'Old' });
  const offlineItem = notification({ id: 'loaded-offline', title: 'Offline' });
  let listRequests = 0;
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('loaded-retry-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 2 });
    listRequests += 1;
    if (listRequests === 2) return response(503);
    return response(200, {
      items: listRequests === 1 ? [oldItem] : [offlineItem, oldItem],
      nextCursor: null,
    });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  socket.serverEmit('connect');
  await settle();
  socket.serverEmit('connect');
  await settle();

  assert.equal(listRequests, 3);
  assert.deepEqual(
    fixture.items.children.map(item => item.children[0].textContent),
    ['Offline', 'Old'],
  );
});

test('connect invalidates count and recent snapshots started before the socket joined', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  const oldItem = notification({ id: 'pre-connect-old', title: 'Old' });
  const missedItem = notification({ id: 'pre-connect-missed', title: 'Missed' });
  let countRequests = 0;
  let listRequests = 0;
  let releaseFirstCount;
  let releaseFirstList;
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('connect-snapshot-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      if (countRequests === 1) {
        return new Promise(resolve => {
          releaseFirstCount = () => resolve(response(200, { count: 1 }));
        });
      }
      return response(200, { count: 2 });
    }
    listRequests += 1;
    if (listRequests === 1) {
      return new Promise(resolve => {
        releaseFirstList = () => resolve(response(200, { items: [oldItem], nextCursor: null }));
      });
    }
    return response(200, { items: [missedItem, oldItem], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  socket.serverEmit('connect');
  releaseFirstCount();
  releaseFirstList();
  await settle();

  assert.equal(countRequests, 2);
  assert.equal(listRequests, 2);
  assert.equal(fixture.badge.textContent, '2');
  assert.deepEqual(
    fixture.items.children.map(item => item.children[0].textContent),
    ['Missed', 'Old'],
  );
});

test('a read click keeps its local decrement until the post-204 count sync completes', async () => {
  const fixture = uiRoot();
  const socket = new FakeSocket();
  const unreadItem = notification({ id: 'read-race', title: 'Read race' });
  let countRequests = 0;
  let releaseStaleCount;
  let releaseRead;
  globalThis.io = () => socket;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('read-race-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      if (countRequests === 2) {
        return new Promise(resolve => {
          releaseStaleCount = () => resolve(response(200, { count: 2 }));
        });
      }
      return response(200, { count: countRequests === 1 ? 2 : 1 });
    }
    if (options.method === 'PATCH') {
      return new Promise(resolve => {
        releaseRead = () => resolve(response(204));
      });
    }
    return response(200, { items: [unreadItem], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  socket.serverEmit('connect');
  await settle();
  fixture.items.children[0].dispatch('click');
  assert.equal(fixture.badge.textContent, '1');
  releaseStaleCount();
  await settle();
  assert.equal(fixture.badge.textContent, '1');
  releaseRead();
  await settle();

  assert.equal(countRequests, 3);
  assert.equal(fixture.badge.textContent, '1');
});

test('the inbox loads 20 newest notifications and deduplicates cursor pages without concurrent loads', async () => {
  const fixture = inboxRoot();
  const first = notification({ id: 'first', title: 'First' });
  const second = notification({ id: 'second', title: 'Second' });
  const third = notification({ id: 'third', title: 'Third' });
  let releaseMore;
  const listUrls = [];
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('inbox-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 3 });
    listUrls.push(url);
    if (listUrls.length === 1) {
      return response(200, { items: [first, first, second], nextCursor: 'next page' });
    }
    return new Promise(resolve => {
      releaseMore = () => resolve(response(200, { items: [second, third], nextCursor: null }));
    });
  };

  NotificationClient.start(fixture.element);
  await settle();

  assert.match(listUrls[0], /[?&]limit=20(?:&|$)/);
  assert.deepEqual(fixture.inboxItems.children.map(row => row.dataset.notificationId), ['first', 'second']);
  assert.equal(fixture.loadMore.hidden, false);
  fixture.loadMore.dispatch('click');
  fixture.loadMore.dispatch('click');
  await settle();
  assert.equal(listUrls.length, 2);
  assert.match(listUrls[1], /[?&]cursor=next\+page(?:&|$)/);
  assert.equal(fixture.loadMore.disabled, true);
  assert.equal(fixture.loadMore.getAttribute('aria-busy'), 'true');

  releaseMore();
  await settle();

  assert.deepEqual(
    fixture.inboxItems.children.map(row => row.dataset.notificationId),
    ['first', 'second', 'third'],
  );
  assert.equal(fixture.loadMore.hidden, true);
  assert.equal(fixture.list.getAttribute('aria-busy'), 'false');
});

test('the unread-only checkbox immediately filters loaded inbox rows', async () => {
  const fixture = inboxRoot();
  const unreadItem = notification({ id: 'unread' });
  const readItem = notification({ id: 'read', readAt: '2026-08-30T07:00:00.000Z' });
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('filter-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 1 });
    return response(200, { items: [unreadItem, readItem], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.filter.checked = true;
  fixture.filter.dispatch('change');

  assert.deepEqual(fixture.inboxItems.children.map(row => row.dataset.notificationId), ['unread']);
  assert.equal(fixture.inboxEmpty.hidden, true);
});

test('an inbox read waits for 204, ignores duplicate clicks, and then updates the row and badge', async () => {
  const fixture = inboxRoot();
  const item = notification({ id: 'read-once' });
  let releaseRead;
  let readRequests = 0;
  let countRequests = 0;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('read-once-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      return response(200, { count: countRequests === 1 ? 1 : 0 });
    }
    if (options.method === 'PATCH') {
      readRequests += 1;
      return new Promise(resolve => {
        releaseRead = () => resolve(response(204));
      });
    }
    return response(200, { items: [item], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  const action = readButton(inboxRow(fixture, item.id));
  action.dispatch('click');
  action.dispatch('click');
  await settle();

  assert.equal(readRequests, 1);
  assert.equal(inboxRow(fixture, item.id).classList.contains('unread'), true);
  assert.equal(fixture.badge.textContent, '1');
  assert.equal(readButton(inboxRow(fixture, item.id)).disabled, true);

  releaseRead();
  await settle();

  assert.equal(inboxRow(fixture, item.id).classList.contains('unread'), false);
  assert.equal(fixture.badge.hidden, true);
  assert.equal(readButton(inboxRow(fixture, item.id)), null);
});

test('a failed inbox read keeps unread state and exposes an inline retry', async () => {
  const fixture = inboxRoot();
  const item = notification({ id: 'read-retry' });
  let readRequests = 0;
  let countRequests = 0;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('read-retry-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      return response(200, { count: readRequests < 2 ? 1 : 0 });
    }
    if (options.method === 'PATCH') {
      readRequests += 1;
      return response(readRequests === 1 ? 503 : 204);
    }
    return response(200, { items: [item], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  readButton(inboxRow(fixture, item.id)).dispatch('click');
  await settle();

  assert.equal(inboxRow(fixture, item.id).classList.contains('unread'), true);
  const retry = readButton(inboxRow(fixture, item.id));
  assert.equal(retry.textContent, '다시 시도');
  assert.equal(
    inboxRow(fixture, item.id).querySelector('[data-notification-row-status]').textContent,
    '읽음 처리하지 못했습니다.',
  );
  retry.dispatch('click');
  await settle();

  assert.equal(readRequests, 2);
  assert.equal(inboxRow(fixture, item.id).classList.contains('unread'), false);
});

test('read-all applies only after 201 and leaves a socket notification arriving during the request unread', async () => {
  const fixture = inboxRoot();
  const socket = new FakeSocket();
  const first = notification({ id: 'read-all-1' });
  const second = notification({ id: 'read-all-2' });
  const incoming = notification({ id: 'after-cutoff', title: 'After cutoff' });
  let releaseReadAll;
  let countRequests = 0;
  globalThis.io = () => socket;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('read-all-token'));
    if (url.endsWith('/unread-count')) {
      countRequests += 1;
      return response(200, { count: countRequests === 1 ? 2 : 1 });
    }
    if (options.method === 'POST') {
      return new Promise(resolve => {
        releaseReadAll = () => resolve(response(201, { changedCount: 2 }));
      });
    }
    return response(200, { items: [first, second], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.readAll.dispatch('click');
  await settle();
  assert.equal(fixture.readAll.disabled, true);
  assert.equal(inboxRow(fixture, first.id).classList.contains('unread'), true);
  socket.serverEmit('notification:new', incoming);
  socket.serverEmit('notification:new', incoming);
  assert.deepEqual(
    fixture.inboxItems.children.map(row => row.dataset.notificationId),
    ['after-cutoff', 'read-all-1', 'read-all-2'],
  );

  releaseReadAll();
  await settle();

  assert.equal(inboxRow(fixture, first.id).classList.contains('unread'), false);
  assert.equal(inboxRow(fixture, second.id).classList.contains('unread'), false);
  assert.equal(inboxRow(fixture, incoming.id).classList.contains('unread'), true);
  assert.equal(fixture.badge.textContent, '1');
  assert.equal(fixture.readAll.disabled, false);
});

test('a failed read-all preserves state and can be retried inline', async () => {
  const fixture = inboxRoot();
  const item = notification({ id: 'read-all-retry' });
  let attempts = 0;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('read-all-retry-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: attempts < 2 ? 1 : 0 });
    if (options.method === 'POST') {
      attempts += 1;
      return attempts === 1
        ? response(503)
        : response(201, { changedCount: 1 });
    }
    return response(200, { items: [item], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.readAll.dispatch('click');
  await settle();

  assert.equal(inboxRow(fixture, item.id).classList.contains('unread'), true);
  assert.equal(fixture.status.hidden, false);
  assert.match(fixture.statusMessage.textContent, /모두 읽음 처리하지 못했습니다/);
  fixture.statusRetry.dispatch('click');
  await settle();

  assert.equal(attempts, 2);
  assert.equal(inboxRow(fixture, item.id).classList.contains('unread'), false);
  assert.equal(fixture.status.hidden, true);
});

test('inbox failures preserve the cursor and retry into an empty or later page', async () => {
  const fixture = inboxRoot();
  const item = notification({ id: 'cursor-item' });
  const cursors = [];
  let listRequests = 0;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('cursor-retry-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 1 });
    listRequests += 1;
    const parsed = new URL(url);
    cursors.push(parsed.searchParams.get('cursor'));
    if (listRequests === 1) return response(503);
    if (listRequests === 2) return response(200, { items: [item], nextCursor: 'same-cursor' });
    if (listRequests === 3) return response(503);
    return response(200, { items: [], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  assert.equal(fixture.error.hidden, false);
  assert.equal(fixture.loading.hidden, true);
  fixture.retry.dispatch('click');
  await settle();
  fixture.loadMore.dispatch('click');
  await settle();
  assert.equal(fixture.error.hidden, false);
  fixture.retry.dispatch('click');
  await settle();

  assert.deepEqual(cursors, [null, null, 'same-cursor', 'same-cursor']);
  assert.deepEqual(fixture.inboxItems.children.map(row => row.dataset.notificationId), ['cursor-item']);
  assert.equal(fixture.loadMore.hidden, true);
});

test('socket inbox rows use safe text links and a restarted session ignores a stale page', async () => {
  const firstFixture = inboxRoot();
  let releaseStale;
  let listRequests = 0;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token(`stale-token-${listRequests}`));
    if (url.endsWith('/unread-count')) return response(200, { count: 1 });
    listRequests += 1;
    if (listRequests === 1) {
      return new Promise(resolve => {
        releaseStale = () => resolve(response(200, {
          items: [notification({ id: 'stale' })],
          nextCursor: null,
        }));
      });
    }
    return response(200, {
      items: [notification({
        id: 'current',
        title: '<img src=x>',
        body: 'x'.repeat(400),
        linkUrl: 'https://unsafe.example',
      })],
      nextCursor: null,
    });
  };

  NotificationClient.start(firstFixture.element);
  await settle();
  const secondFixture = inboxRoot();
  NotificationClient.start(secondFixture.element);
  await settle();
  releaseStale();
  await settle();

  assert.equal(firstFixture.inboxItems.children.length, 0);
  const row = inboxRow(secondFixture, 'current');
  const link = row.querySelector('[data-notification-link]');
  assert.equal(link.getAttribute('href'), '/notifications');
  assert.equal(link.textContent, '<img src=x>');
  assert.equal(row.querySelector('[data-notification-body]').textContent, 'x'.repeat(400));
});

test('a successful empty inbox shows its empty state', async () => {
  const fixture = inboxRoot();
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('empty-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 0 });
    return response(200, { items: [], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();

  assert.equal(fixture.inboxEmpty.hidden, false);
  assert.equal(fixture.inboxEmpty.textContent, '알림이 없습니다.');
  assert.equal(fixture.loading.hidden, true);
  assert.equal(fixture.error.hidden, true);
});

test('socket connect merges a missed first page without replacing the current load-more cursor', async () => {
  const fixture = inboxRoot();
  const socket = new FakeSocket();
  const oldItem = notification({ id: 'inbox-old', title: 'Old' });
  const refreshedOldItem = { ...oldItem, readAt: '2026-08-30T07:00:00.000Z' };
  const missedItem = notification({ id: 'inbox-missed', title: 'Missed' });
  const olderItem = notification({ id: 'inbox-older', title: 'Older' });
  const listUrls = [];
  globalThis.io = () => socket;
  globalThis.fetch = async url => {
    if (url === '/api/realtime/token') return response(200, token('inbox-connect-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: 3 });
    listUrls.push(url);
    if (listUrls.length === 1) {
      return response(200, { items: [oldItem], nextCursor: 'older-cursor' });
    }
    if (listUrls.length === 2) {
      return response(200, { items: [missedItem, refreshedOldItem], nextCursor: 'ignored-head-cursor' });
    }
    return response(200, { items: [olderItem], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  socket.serverEmit('connect');
  await settle();

  assert.deepEqual(
    fixture.inboxItems.children.map(row => row.dataset.notificationId),
    ['inbox-missed', 'inbox-old'],
  );
  assert.equal(inboxRow(fixture, oldItem.id).classList.contains('unread'), false);
  fixture.loadMore.dispatch('click');
  await settle();
  assert.equal(new URL(listUrls[2]).searchParams.get('cursor'), 'older-cursor');
  assert.deepEqual(
    fixture.inboxItems.children.map(row => row.dataset.notificationId),
    ['inbox-missed', 'inbox-old', 'inbox-older'],
  );
});

test('read-all also clears a loaded header item when the inbox page failed', async () => {
  const fixture = inboxRoot();
  const recentOnly = notification({ id: 'recent-only', title: 'Recent only' });
  let readAllDone = false;
  globalThis.fetch = async (url, options = {}) => {
    if (url === '/api/realtime/token') return response(200, token('recent-read-all-token'));
    if (url.endsWith('/unread-count')) return response(200, { count: readAllDone ? 0 : 1 });
    if (options.method === 'POST') {
      readAllDone = true;
      return response(201, { changedCount: 1 });
    }
    const limit = new URL(url).searchParams.get('limit');
    return limit === '20'
      ? response(503)
      : response(200, { items: [recentOnly], nextCursor: null });
  };

  NotificationClient.start(fixture.element);
  await settle();
  fixture.trigger.dispatch('click');
  await settle();
  assert.equal(fixture.items.children[0].classList.contains('unread'), true);
  fixture.readAll.dispatch('click');
  await settle();

  assert.equal(fixture.items.children[0].classList.contains('unread'), false);
  assert.equal(fixture.badge.hidden, true);
});
