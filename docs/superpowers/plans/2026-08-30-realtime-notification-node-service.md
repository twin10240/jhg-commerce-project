# Realtime Notification Node Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 별도 NestJS 서비스가 OMS 이벤트를 멱등 저장하고 인증된 사용자에게 알림 API와 Socket.IO 실시간 전달을 제공하게 한다.

**Architecture:** HMAC으로 보호된 내부 API가 이벤트를 검증하고 PostgreSQL에 알림을 먼저 커밋한다. 사용자 REST API와 Socket.IO는 OMS가 발급한 RS256 JWT를 검증하며, 서버가 JWT `sub`로 사용자 범위를 강제한다. 실시간 전달 실패는 저장된 알림함 조회로 복구한다.

**Tech Stack:** Node.js 24 LTS, TypeScript strict mode, NestJS, Socket.IO, Prisma 8, PostgreSQL, Jest, npm

**Spec:** `/Users/jo/study/jhg-commerce-project/docs/superpowers/specs/2026-08-30-realtime-notification-service-design.md`

## Global Constraints

- Repository root is `/Users/jo/study/jhg-realtime-service`.
- Use ESM and TypeScript `strict: true`.
- Use Prisma 8 with the PostgreSQL provider and commit the generated lockfile.
- Store notification history before emitting Socket.IO events.
- Never trust a client-provided member ID or room name.
- Never store a JWT, HMAC secret, private key, or database password in source control.
- Notification retention is exactly 90 days; read and unread rows expire identically.
- Start with one Node.js instance; do not add Redis, Kafka, RabbitMQ, CQRS, or an API Gateway.
- Use UTC instants at every HTTP and database boundary.

---

### Task 1: NestJS repository and PostgreSQL schema

**Files:**
- Create repository: `/Users/jo/study/jhg-realtime-service`
- Create/modify: `package.json`
- Create/modify: `tsconfig.json`
- Create: `.env.example`
- Create: `.gitignore`
- Create: `src/app.module.ts`
- Create: `src/config/env.validation.ts`
- Create: `prisma/contract.prisma`
- Create: `prisma.config.ts`
- Create: `src/prisma/prisma.module.ts`
- Create: `src/prisma/prisma.service.ts`
- Create: `src/notifications/notification.repository.ts`
- Test: `test/prisma/notification.repository.e2e-spec.ts`

**Interfaces:**
- Produces: injectable `PrismaService`
- Produces: `NotificationRepository.create`, `findByEventId`, `listForRecipient`, `markRead`, `markAllRead`, `countUnread`, and `deleteExpired`

- [ ] **Step 1: Scaffold NestJS and initialize Prisma 8**

Run from `/Users/jo/study`:

```bash
npx @nestjs/cli@latest new jhg-realtime-service --package-manager npm --strict --skip-git
cd jhg-realtime-service
git init
npx prisma@latest orm init --target postgres --authoring psl
npm install @nestjs/config @nestjs/schedule @nestjs/websockets @nestjs/platform-socket.io socket.io jose class-validator class-transformer
npm install --save-dev socket.io-client
```

Keep the generated Nest application shell and Prisma PostgreSQL contract configuration. Replace the sample `AppController` response with modules introduced in later tasks; do not add Prisma Compute deployment declarations because the service deploys as a normal long-running Nest process.

- [ ] **Step 2: Pin runtime scripts and environment validation**

The scripts must include:

```json
{
  "start:dev": "nest start --watch",
  "build": "nest build",
  "start:prod": "node dist/main.js",
  "test": "jest",
  "test:e2e": "jest --config ./test/jest-e2e.json",
  "lint": "eslint \"{src,test}/**/*.ts\"",
  "db:contract": "prisma contract emit",
  "db:migrate": "prisma db migrate"
}
```

Validate and expose these exact environment values at startup:

```text
PORT=3000
DATABASE_URL=postgresql://notification:password@localhost:5432/notification
OMS_ALLOWED_ORIGIN=http://localhost:8080
OMS_JWT_PUBLIC_KEY=<PEM>
OMS_JWT_ISSUER=oms
OMS_JWT_AUDIENCE=realtime-service
OMS_EVENT_HMAC_SECRET=<secret>
NOTIFICATION_RETENTION_DAYS=90
```

Blank keys or secrets must stop this service at startup; unlike OMS, this service has no useful unauthenticated mode.

- [ ] **Step 3: Write the failing repository E2E test**

Against a dedicated `notification_test` database, assert:

```text
create and find by eventId
unique eventId constraint
recipient 7 cannot list recipient 8 rows
stable (createdAt,id) cursor ordering
markRead is idempotent and recipient-scoped
markAllRead affects only rows at or before supplied instant
countUnread ignores read rows
deleteExpired removes read and unread rows before cutoff
```

Run: `npm run test:e2e -- notification.repository.e2e-spec.ts`

Expected: FAIL because the schema and repository are missing.

- [ ] **Step 4: Define the Notification model**

Use these exact fields and database types:

```text
id UUID primary key
eventId UUID unique
eventHash varchar(64)
recipientId bigint
type varchar(64)
aggregateType varchar(32)
aggregateId varchar(64)
title varchar(200)
body varchar(500)
linkUrl varchar(500)
metadata jsonb
createdAt timestamptz
readAt timestamptz nullable
expiresAt timestamptz
```

Add the three indexes from the spec directly to the Prisma contract.

- [ ] **Step 5: Implement one concrete repository**

Expose these TypeScript types; do not add a one-implementation repository interface:

```ts
export type NotificationCursor = { createdAt: Date; id: string };
export type CreateNotification = {
  id: string; eventId: string; eventHash: string; recipientId: bigint;
  type: string; aggregateType: string; aggregateId: string;
  title: string; body: string; linkUrl: string;
  metadata: Record<string, unknown>; createdAt: Date; expiresAt: Date;
};
```

Encode/decode the cursor as base64url JSON containing only ISO `createdAt` and UUID `id`. Reject malformed cursors with `400` at the controller boundary.

- [ ] **Step 6: Apply migration and run repository E2E test**

Emit the contract, generate a reviewable migration, apply it, then run the test:

```bash
npm run db:contract
npx prisma migration plan --name init-notifications
npm run db:migrate
npm run test:e2e -- notification.repository.e2e-spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add .
git commit -m "feat: bootstrap realtime notification service"
```

### Task 2: HMAC event ingestion and notification templates

**Files:**
- Modify: `src/main.ts`
- Create: `src/events/event-envelope.dto.ts`
- Create: `src/events/hmac-verifier.service.ts`
- Create: `src/events/events.controller.ts`
- Create: `src/events/events.service.ts`
- Create: `src/events/events.module.ts`
- Create: `src/notifications/notification-template.service.ts`
- Create: `src/notifications/notification.types.ts`
- Create: `src/notifications/notifications.module.ts`
- Test: `src/events/hmac-verifier.service.spec.ts`
- Test: `src/notifications/notification-template.service.spec.ts`
- Test: `test/events/events.e2e-spec.ts`

**Interfaces:**
- Consumes: `POST /internal/v1/events` plus raw request body
- Produces: committed notification returned to Task 4's realtime publisher

- [ ] **Step 1: Write failing HMAC unit tests**

Use Node `createHmac` to create the expected fixture. Assert valid signature, one-byte body mutation, wrong event ID, malformed hex, and timestamps at `now +/- 300 seconds`. Verify comparison uses equal-length buffers and `timingSafeEqual` without throwing on malformed input.

Run: `npm test -- hmac-verifier.service.spec.ts`

Expected: FAIL.

- [ ] **Step 2: Enable raw body and implement verifier**

Create Nest with `{ rawBody: true }`. Verify the canonical bytes for:

```ts
const canonical = Buffer.concat([
  Buffer.from(`${timestamp}.`, 'utf8'),
  rawBody,
]);
```

Reject missing, stale, future, malformed, or mismatched headers with `401`. Compare header event ID with parsed body event ID after signature verification.

- [ ] **Step 3: Write failing template tests for all 15 event types**

Each test asserts exact Korean title, body, relative OMS link, and metadata. Unknown type and unsupported `schemaVersion` must fail rather than create a generic notification.

Run: `npm test -- notification-template.service.spec.ts`

Expected: FAIL.

- [ ] **Step 4: Implement a total template map**

Use one `Record<NotificationEventType, TemplateFunction>` and no class hierarchy. All order links are `/orders/{orderId}`; return links point to the owning order with a return anchor; refund links point to the owning order payment view. Escape is handled by the OMS DOM text APIs, not by storing HTML.

- [ ] **Step 5: Write failing ingestion E2E tests**

Test `201` new event, `200` identical duplicate, `409` same event ID with different raw-body SHA-256, `400` invalid JSON/required field, `401` signature failures, `422` unsupported version/type, and `503` database failure. Assert no Socket.IO dependency is required to save.

- [ ] **Step 6: Implement DTO validation and idempotent transaction**

Allow only exact event names, positive safe-integer `recipientId`, UUID event ID, ISO UTC instant, nonblank aggregate values, and a JSON object `data`. Compute `eventHash` from the raw body with SHA-256. On unique conflict, load by event ID and compare the hash.

Persist:

```ts
createdAt = new Date(envelope.occurredAt)
expiresAt = new Date(createdAt.getTime() + 90 * 24 * 60 * 60 * 1000)
```

Return a discriminated result `{ kind: 'created', notification } | { kind: 'duplicate' }`.

- [ ] **Step 7: Run all focused tests and commit**

Run:

```bash
npm test -- hmac-verifier.service.spec.ts notification-template.service.spec.ts
npm run test:e2e -- events.e2e-spec.ts
git add src test
git commit -m "feat: ingest signed OMS notification events"
```

Expected: all tests PASS.

### Task 3: OMS JWT authentication and notification REST API

**Files:**
- Create: `src/auth/auth-user.ts`
- Create: `src/auth/jwt-verifier.service.ts`
- Create: `src/auth/bearer-auth.guard.ts`
- Create: `src/auth/auth.module.ts`
- Create: `src/notifications/notifications.controller.ts`
- Create: `src/notifications/notifications.service.ts`
- Test: `src/auth/jwt-verifier.service.spec.ts`
- Test: `test/notifications/notifications.e2e-spec.ts`

**Interfaces:**
- Produces: `AuthUser { memberId: bigint; role: 'USER' | 'ADMIN'; expiresAt: Date }`
- Produces: the four `/api/v1/notifications` endpoints from the spec

- [ ] **Step 1: Install and test JOSE verification**

Install `jose`. In tests sign with an in-memory RSA private key and assert success plus failures for expired token, invalid signature, wrong issuer, wrong audience, missing/invalid numeric `sub`, unsupported role, and lifetime longer than five minutes.

Run: `npm test -- jwt-verifier.service.spec.ts`

Expected: FAIL before implementation.

- [ ] **Step 2: Implement the verifier and bearer guard**

Import the configured public PEM once with `importSPKI(..., 'RS256')`. Use `jwtVerify` with exact issuer, audience, and algorithm. Store the verified `AuthUser` on the request through a typed property; never accept member ID from query, body, or route.

- [ ] **Step 3: Write failing REST E2E tests**

Assert:

```text
missing/invalid bearer                       -> 401
GET list                                     -> newest first, cursor, max 100
GET unread-count                             -> current user's count
PATCH own unread/read notification           -> 204 both times
PATCH another user's or missing notification -> 404
POST read-all                                -> changed count, request-time cutoff
recipient data never crosses JWT sub         -> true for every endpoint
```

- [ ] **Step 4: Implement service and controller**

Controller signatures must derive `memberId` only from `AuthUser`. Parse `limit` as an integer defaulting to 20 and reject values outside 1..100. Return decimal database IDs as strings where JavaScript number precision could be lost.

- [ ] **Step 5: Run API tests and commit**

Run:

```bash
npm test -- jwt-verifier.service.spec.ts
npm run test:e2e -- notifications.e2e-spec.ts
git add package.json package-lock.json src test
git commit -m "feat: secure notification inbox APIs"
```

Expected: PASS.

### Task 4: Socket.IO authenticated delivery

**Files:**
- Create: `src/realtime/realtime.gateway.ts`
- Create: `src/realtime/realtime.publisher.ts`
- Create: `src/realtime/realtime.module.ts`
- Modify: `src/events/events.service.ts`
- Test: `test/realtime/realtime.e2e-spec.ts`

**Interfaces:**
- Consumes: committed notification from Task 2
- Produces: `notification:new` on server-owned `member:{sub}` rooms

- [ ] **Step 1: Write failing Socket.IO E2E tests**

Use `socket.io-client` as a dev dependency. Assert missing, expired, wrong-audience, and malformed handshake tokens receive `connect_error`; users 7 and 8 join isolated channels; a committed event for 7 reaches only 7; and the server disconnects a socket when its JWT expires.

- [ ] **Step 2: Implement handshake authentication and channel selection**

Read only `socket.handshake.auth.token`, call the existing JWT verifier, store `AuthUser` in `socket.data`, and join `member:${memberId}`. Schedule one disconnect timer for `exp`; clear it on disconnect. Ignore client attempts to select or join member rooms.

Keep Socket.IO `serveClient: true` so the OMS can load the version-matched browser client from `/socket.io/socket.io.js` without adding a frontend package manager.

- [ ] **Step 3: Publish only after database commit**

`EventsService` calls `RealtimePublisher.notificationCreated(notification)` only after the repository transaction resolves. Publisher errors are logged with event ID and do not change the HTTP `201` result or delete the notification.

- [ ] **Step 4: Run Socket.IO and ingestion tests**

Run:

```bash
npm run test:e2e -- realtime.e2e-spec.ts events.e2e-spec.ts
```

Expected: PASS, including a test with no connected clients.

- [ ] **Step 5: Commit**

```bash
git add src/realtime src/events/events.service.ts test/realtime package.json package-lock.json
git commit -m "feat: deliver notifications over authenticated sockets"
```

### Task 5: Retention, health, CORS, and process behavior

**Files:**
- Modify: `src/main.ts`
- Create: `src/retention/retention.service.ts`
- Create: `src/retention/retention.module.ts`
- Create: `src/health/health.controller.ts`
- Create: `src/health/health.module.ts`
- Test: `src/retention/retention.service.spec.ts`
- Test: `test/health/health.e2e-spec.ts`

**Interfaces:**
- Produces: daily expiration deletion
- Produces: `/health/live` and `/health/ready`

- [ ] **Step 1: Write failing retention boundary tests**

Freeze time. Assert rows with `expiresAt < now` are deleted, `expiresAt == now` and future rows remain, read status is irrelevant, and repository failure is logged then retried only at the next schedule.

- [ ] **Step 2: Implement one daily scheduled cleanup**

Use `@nestjs/schedule`. Execute one PostgreSQL delete and log only deleted count and duration. Do not add a queue or batch loop until measured deletion volume requires it.

- [ ] **Step 3: Write and implement health E2E tests**

`live` returns `200 {"status":"up"}` without querying dependencies. `ready` performs `SELECT 1`, returns 200 when connected and 503 when unavailable. Do not add Terminus for two endpoints.

- [ ] **Step 4: Lock down startup behavior**

Enable CORS only for the exact `OMS_ALLOWED_ORIGIN`, only the required methods/headers, and no credentials because bearer tokens are used. Install shutdown hooks, close Prisma on termination, set a global validation pipe, and reject oversized internal event bodies above 32 KiB.

- [ ] **Step 5: Verify and commit**

Run:

```bash
npm test
npm run test:e2e
npm run lint
npm run build
git add src test
git commit -m "feat: add retention and service health checks"
```

Expected: all commands succeed.

### Task 6: CI, contract fixture, and operations documentation

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `test/contracts/realtime-event-v1.json`
- Create: `README.md`
- Create: `Dockerfile`
- Create: `railway.json`

**Interfaces:**
- Consumes: the approved event contract and local `test/contracts/realtime-event-v1.json`
- Produces: repeatable local, CI, and Railway startup instructions

- [ ] **Step 1: Add a contract fixture test**

Load the Node fixture through the ingestion DTO, sign its exact bytes, and assert it receives `201` followed by `200` on replay. The test must fail when fields, names, version, or event type diverge from the local contract types. Cross-repository verification uses `diff` during the final integration run rather than making either repository's CI depend on a sibling checkout.

- [ ] **Step 2: Add GitHub Actions PostgreSQL CI**

Use a PostgreSQL 16 service with health checks. Run install from lockfile, migrations against `notification_test`, unit tests, E2E tests, lint, and build. Generate an RSA test key pair inside the job and pass only the public key to the service tests.

- [ ] **Step 3: Add production container and Railway config**

Use a multi-stage Node 24 Alpine image, `npm ci`, non-root runtime user, `npm run build`, `npm run db:migrate` before startup, and `$PORT`. Do not bundle development secrets.

- [ ] **Step 4: Write the README**

Document architecture, module ownership, prerequisites, local database/user creation, environment variables, migration, run/test commands, health endpoints, HMAC contract, 90-day policy, single-instance limit, and Redis upgrade trigger.

- [ ] **Step 5: Run the final verification and commit**

Run:

```bash
npm ci
npm test
npm run test:e2e
npm run lint
npm run build
git diff --check
git add .
git commit -m "chore: add realtime service delivery pipeline"
```

Expected: all commands succeed and the working tree is clean.
