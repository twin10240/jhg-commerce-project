import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';

import NotificationClient from '../../main/resources/static/js/notification-client.js';

const original = {
  fetch: globalThis.fetch,
  io: globalThis.io,
  setTimeout: globalThis.setTimeout,
  clearTimeout: globalThis.clearTimeout,
  localStorage: globalThis.localStorage,
  sessionStorage: globalThis.sessionStorage,
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
