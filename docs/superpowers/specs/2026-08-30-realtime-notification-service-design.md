# OMS 실시간 알림 서비스 설계

## 1. 목적

OMS의 주문, 결제, 배송, 반품, 환불 상태 변화를 고객에게 실시간으로 전달하고 90일 동안 알림함에서 다시 확인할 수 있게 한다. 실시간 연결과 알림 이력은 별도 Node.js 서비스가 담당하며, OMS는 업무 상태와 사용자 인증의 원본으로 남는다.

이번 구조는 이후 고객과 관리자의 1:1 상담 메신저를 같은 실시간 연결 기반 위에 추가할 수 있어야 한다. 다만 대화방과 메시지는 이번 구현 범위에 포함하지 않는다.

## 2. 결정 사항

- 별도 저장소와 프로세스인 `jhg-realtime-service`를 만든다.
- Node.js 24 LTS, TypeScript, NestJS, Socket.IO, Prisma 8, PostgreSQL을 사용한다.
- 실시간 서비스는 OMS와 물리적으로 분리된 PostgreSQL 데이터베이스를 소유한다.
- OMS는 트랜잭셔널 Outbox에 알림 이벤트를 기록하고 HMAC 인증 HTTP로 전달한다.
- 브라우저는 OMS 로그인 세션으로 5분짜리 실시간 연결 JWT를 발급받는다.
- 실시간 서비스는 JWT를 검증한 뒤 서버가 결정한 사용자 채널에만 연결한다.
- 알림은 PostgreSQL 커밋 이후 Socket.IO로 전송한다.
- 알림 이력은 읽음 여부와 관계없이 생성 후 90일 동안 보존한다.
- Kafka, RabbitMQ, Redis, CQRS, API Gateway는 1차 범위에서 도입하지 않는다.

## 3. 서비스 경계

### OMS

- 로그인 세션과 회원, 주문, 결제, 배송, 반품, 환불 상태의 원본
- 실시간 연결 JWT 발급
- 업무 상태 변경과 같은 트랜잭션에서 Outbox 이벤트 저장
- Outbox 이벤트 재시도와 실패 상태 관리

### 실시간 서비스

- OMS 내부 이벤트의 서명과 계약 검증
- 이벤트 멱등 수신과 알림 문구 생성
- 알림 이력, 읽음 상태, 만료 시각 저장
- 사용자별 알림 목록과 미확인 개수 API 제공
- Socket.IO 연결과 사용자별 실시간 전달
- 90일 만료 데이터 삭제

실시간 서비스는 OMS의 주문 상태를 수정하거나 자체적으로 업무 상태를 추론하지 않는다. 알림 데이터는 표시와 전달을 위한 파생 데이터다.

## 4. 전체 흐름

```text
OMS 업무 상태 변경
  -> 같은 DB 트랜잭션에서 Outbox 저장
  -> Outbox 발행기가 HMAC 서명 HTTP 전송
  -> 실시간 서비스가 eventId로 멱등 검사
  -> 알림 문구를 생성해 PostgreSQL 커밋
  -> 온라인 사용자에게 Socket.IO 전송

브라우저 로그인 세션
  -> OMS에 실시간 연결 JWT 요청
  -> Socket.IO handshake auth로 JWT 전달
  -> 실시간 서비스가 서명과 클레임 검증
  -> member:{memberId} 채널 연결
```

사용자가 오프라인이거나 Socket.IO 전송에 실패해도 저장된 알림은 유지된다. 재접속 후 브라우저는 HTTP API로 알림함과 미확인 개수를 다시 조회한다.

## 5. 사용자 인증과 권한

### JWT 발급

브라우저는 기존 `OMSSESSION`으로 인증된 상태에서 다음 OMS API를 호출한다.

```http
POST /api/realtime/token
```

이 요청은 기존 세션 인증과 CSRF 검증을 그대로 적용한다. OMS는 RS256 개인키로 5분짜리 JWT를 발급하고, 실시간 서비스는 공개키만 보유한다.

필수 클레임은 다음과 같다.

```json
{
  "iss": "oms",
  "aud": "realtime-service",
  "sub": "7",
  "role": "USER",
  "jti": "4e4578cc-47e5-4799-81dd-26ec9fb95d8d",
  "iat": 1788070800,
  "exp": 1788071100
}
```

- `sub`는 OMS 회원 ID다.
- OMS 세션 ID와 개인정보는 JWT에 넣지 않는다.
- 브라우저는 JWT를 `localStorage`나 URL에 저장하지 않고 메모리에서만 사용한다.
- Socket.IO의 handshake `auth.token`으로 전달한다.
- 실시간 서비스는 정확한 `iss`, `aud`, 서명, 만료 시각을 모두 검증한다.
- 서비스가 검증한 `sub`로만 `member:{sub}` 채널을 선택한다. 클라이언트가 보낸 회원 ID나 채널명은 신뢰하지 않는다.
- 사용자 REST API도 동일한 JWT로 인증하고 모든 조회와 변경을 `sub` 범위로 제한한다.
- 허용된 OMS Origin만 CORS와 Socket.IO Origin 검사에 통과시킨다.

소켓은 JWT 만료 시 연결을 종료하고 브라우저가 새 토큰을 발급받아 재연결한다. 로그아웃 시 브라우저는 즉시 소켓을 종료한다. 계정 차단과 전체 세션 강제 종료를 즉시 반영하는 서버 간 폐기 이벤트 및 Redis 기반 다중 인스턴스 전파는 메신저 확장 단계에서 추가한다. 1차 단계에서 유출된 연결권의 최대 유효 시간은 5분이다.

### 메신저 확장 시 권한

JWT는 사용자 신원과 역할만 증명한다. 대화방 목록을 JWT에 넣지 않는다. 대화방 입장과 메시지 전송 때마다 실시간 서비스가 참여자 또는 담당 관리자 관계를 데이터베이스에서 확인한다.

## 6. 서비스 간 HMAC 인증

OMS Outbox 발행기는 다음 헤더를 보낸다.

```http
X-OMS-Event-Id: <UUID>
X-OMS-Timestamp: <Unix seconds>
X-OMS-Signature: v1=<hex HMAC-SHA256>
```

서명 대상은 UTF-8 기준 `${timestamp}.${rawRequestBody}`이고 공유 비밀키로 HMAC-SHA256을 계산한다.

- 본문 파싱 전에 원문 바이트로 서명을 검증한다.
- 현재 시각과 타임스탬프 차이가 5분을 넘으면 거부한다.
- 헤더의 이벤트 ID와 본문의 `eventId`가 다르면 거부한다.
- 운영 통신은 HTTPS를 사용한다.
- HMAC 비밀키는 환경 변수로만 주입하고 로그에 남기지 않는다.

## 7. 알림 이벤트 계약

```json
{
  "schemaVersion": 1,
  "eventId": "e133d8bf-9993-4d59-8b7a-c80fd2d2d37b",
  "type": "DELIVERY_COMPLETED",
  "occurredAt": "2026-08-30T06:30:00Z",
  "recipientId": 7,
  "aggregate": {
    "type": "ORDER",
    "id": "12"
  },
  "data": {
    "orderId": 12
  }
}
```

- `schemaVersion`, `eventId`, `type`, `occurredAt`, `recipientId`, `aggregate`는 필수다.
- `occurredAt`은 UTC ISO-8601 형식이다.
- `data`에는 문구 생성과 링크 구성에 필요한 최소 식별자와 값만 넣는다.
- 고객 이름, 이메일, 전화번호, 주소는 보내지 않는다.
- OMS는 제목과 본문을 보내지 않는다.
- 실시간 서비스가 이벤트 타입별 제목, 본문, OMS 상대 링크를 생성해 알림 행에 확정 저장한다.
- 지원하지 않는 스키마 버전이나 이벤트 타입은 `422`로 거부한다.

### 1차 이벤트

| 이벤트 | 발생 조건 | 기본 표시 |
|---|---|---|
| `PAYMENT_APPROVED` | 결제 승인 완료 | 주문 결제가 완료되었습니다. |
| `PAYMENT_FAILED` | 결제 영구 실패 | 주문 결제에 실패했습니다. |
| `PAYMENT_REVIEW_REQUIRED` | 결제 수동 확인 필요 | 주문 결제 확인이 필요합니다. |
| `ORDER_BACKORDERED` | 결제 후 재고 부족 확정 | 상품이 입고 대기 중입니다. |
| `STOCK_ALLOCATED` | 백오더 주문의 재고 확보 | 상품의 재고가 확보되었습니다. |
| `ORDER_CANCELLED` | 주문 취소 완료 | 주문이 취소되었습니다. |
| `SHIPMENT_STARTED` | WMS 출고 완료 반영 | 주문이 출고되었습니다. |
| `DELIVERY_COMPLETED` | WMS 배송 완료 반영 | 배송이 완료되었습니다. |
| `RETURN_REQUESTED` | WMS 반품 접수 완료 | 반품 요청이 접수되었습니다. |
| `RETURN_REJECTED` | OMS 반품 승인 반려 | 반품 요청이 반려되었습니다. |
| `RETURN_RECEIVED` | WMS 창고 입고 반영 | 반품 상품이 창고에 도착했습니다. |
| `RETURN_COMPLETED` | WMS 검수 결과 반영 | 반품 처리가 완료되었습니다. |
| `RETURN_CANCELLED` | 반품 요청 취소 반영 | 반품 요청이 취소되었습니다. |
| `REFUND_COMPLETED` | 환불 성공 | 환불이 완료되었습니다. |
| `REFUND_REVIEW_REQUIRED` | 환불 수동 확인 필요 | 환불 확인이 필요합니다. |

`PENDING`, `PROCESSING`, `RETRYING`처럼 짧게 지나가는 내부 상태는 고객 알림으로 발행하지 않는다.

## 8. OMS Outbox

OMS DB에 다음 개념의 테이블을 추가한다. OMS는 로컬 H2와 운영 PostgreSQL을 모두 사용하므로 payload는 DB 전용 JSON 타입이 아닌 text로 저장한다.

```text
notification_outbox
- id                  UUID PK
- event_id            UUID UNIQUE
- event_type          VARCHAR
- aggregate_type      VARCHAR
- aggregate_id        VARCHAR
- recipient_id        BIGINT
- payload             TEXT
- status              VARCHAR
- attempt_count       INTEGER
- next_attempt_at     TIMESTAMP
- processing_at       TIMESTAMP NULL
- published_at        TIMESTAMP NULL
- last_error_code     VARCHAR NULL
- created_at          TIMESTAMP
- version             BIGINT
```

상태는 `PENDING`, `PROCESSING`, `PUBLISHED`, `FAILED`를 사용한다.

- 업무 상태 변경과 Outbox 저장은 반드시 같은 트랜잭션에서 수행한다.
- 발행기는 전송 가능한 행을 짧은 트랜잭션에서 선점한 뒤 외부 HTTP를 호출한다.
- 프로세스 중단으로 `PROCESSING`에 남은 행은 처리 제한 시간이 지나면 다시 선점할 수 있다.
- 네트워크 오류, 타임아웃, `429`, `5xx`는 지수 백오프로 재시도한다.
- 인증 실패, 계약 오류, 지원하지 않는 이벤트처럼 같은 요청으로 복구되지 않는 `4xx`는 `FAILED`로 전환한다.
- 일시 오류도 최대 시도 횟수를 넘으면 `FAILED`로 전환해 무한 재시도를 막는다.
- 관리자가 원인을 수정한 후 `FAILED` 행을 다시 대기 상태로 전환할 수 있어야 한다.
- `PUBLISHED` 행은 7일 후 삭제한다. 미발행 행은 자동 삭제하지 않는다.

## 9. 실시간 서비스 데이터 모델

```text
notifications
- id                  UUID PK
- event_id            UUID UNIQUE
- event_hash          VARCHAR
- recipient_id        BIGINT
- type                VARCHAR
- aggregate_type      VARCHAR
- aggregate_id        VARCHAR
- title               VARCHAR
- body                VARCHAR
- link_url            VARCHAR
- metadata            JSONB
- created_at          TIMESTAMPTZ
- read_at             TIMESTAMPTZ NULL
- expires_at          TIMESTAMPTZ
```

필수 인덱스는 다음과 같다.

- `(recipient_id, created_at DESC, id DESC)`
- 미확인 행을 위한 `recipient_id` 부분 인덱스(`read_at IS NULL`)
- `(expires_at)`

하나의 알림은 한 명에게만 전달되므로 별도 읽음 테이블을 만들지 않는다. 메신저 메시지는 참여자가 여러 명이므로 이후 별도 읽음 모델을 사용한다.

같은 `eventId`와 같은 본문 해시가 다시 들어오면 기존 성공 결과를 반환한다. 같은 `eventId`에 다른 본문이 들어오면 계약 위반으로 `409`를 반환한다.

## 10. HTTP API

### 내부 이벤트 수신

```http
POST /internal/v1/events
```

- `201 Created`: 새 알림 저장
- `200 OK`: 동일 이벤트의 정상 재전송
- `400 Bad Request`: 필수 필드 또는 JSON 형식 오류
- `401 Unauthorized`: HMAC 서명 오류 또는 허용 시간을 벗어난 요청
- `409 Conflict`: 동일 이벤트 ID의 본문 불일치
- `422 Unprocessable Entity`: 지원하지 않는 버전 또는 이벤트 타입
- `503 Service Unavailable`: 데이터베이스 장애 등 일시 오류

### 사용자 API

```http
GET   /api/v1/notifications?cursor={cursor}&limit=20
GET   /api/v1/notifications/unread-count
PATCH /api/v1/notifications/{id}/read
POST  /api/v1/notifications/read-all
```

- 목록은 `(created_at, id)` 기반 불투명 커서를 사용한다.
- 기본 limit는 20, 최대 limit는 100이다.
- 읽음 처리는 멱등이다.
- 다른 사용자의 알림 ID는 존재 여부를 숨기기 위해 `404`로 응답한다.
- `read-all`은 요청 시각 이전에 생성된 현재 사용자의 미확인 알림만 변경한다.

### 상태 확인

```http
GET /health/live
GET /health/ready
```

`live`는 프로세스 상태만 확인하고, `ready`는 PostgreSQL 연결을 포함한다.

## 11. Socket.IO 계약

- 경로: `/socket.io`
- 연결 인증: handshake `auth.token`
- 서버 채널: `member:{JWT sub}`
- 서버 이벤트: `notification:new`

```json
{
  "id": "81fcc668-9ce4-4fc9-b981-819857cb74aa",
  "type": "DELIVERY_COMPLETED",
  "title": "배송이 완료되었습니다.",
  "body": "주문 #12 배송이 완료되었습니다.",
  "linkUrl": "/orders/12",
  "createdAt": "2026-08-30T06:30:01Z",
  "readAt": null
}
```

실시간 전송은 알림 DB 커밋 이후에만 실행한다. Socket ACK는 전달 보장의 원본으로 사용하지 않는다. 브라우저는 연결 또는 재연결 때 알림 목록과 미확인 개수를 HTTP로 동기화한다.

## 12. OMS 화면 연동

- 인증된 고객 화면 헤더에 알림 버튼과 미확인 배지를 추가한다.
- 알림 버튼에서 최근 알림을 확인하고 전체 알림함으로 이동할 수 있게 한다.
- 전체 알림함은 최신순 목록, 읽음 구분, 개별 읽음, 전체 읽음을 제공한다.
- 새 `notification:new` 이벤트를 받으면 목록 앞에 추가하고 미확인 배지를 갱신한다.
- 실시간 서비스가 일시적으로 연결되지 않아도 주문, 결제, 배송 등 기존 OMS 기능은 정상 동작한다.
- JWT는 페이지 메모리에만 유지하고 만료 또는 재연결 때 OMS에서 다시 발급받는다.

## 13. 보존과 정리

- 알림 생성 시 `expires_at = created_at + 90일`로 저장한다.
- 매일 정리 작업이 만료된 알림을 삭제한다.
- 읽지 않은 알림도 90일이 지나면 동일하게 삭제한다.
- 정리 실패는 다음 실행에서 다시 시도하며 API와 실시간 전송을 중단시키지 않는다.
- 초기 규모에서는 단일 SQL 삭제로 시작하고, 삭제량이 커질 때 배치 삭제로 변경한다.

## 14. 애플리케이션 구성

```text
src/
├── auth/              JWT 검증과 사용자 컨텍스트
├── events/            HMAC 검증과 OMS 이벤트 수신
├── notifications/     저장, 조회, 읽음 처리, 문구 템플릿
├── realtime/          Socket.IO 연결과 사용자 채널
├── retention/         90일 만료 정리
├── health/            live/ready 상태 확인
├── prisma/            PostgreSQL 연결
└── common/            설정, 예외, 로깅
```

메신저 단계에서는 `conversations/`와 `messages/`를 추가한다. 알림과 메시지는 인증 및 소켓 기반만 공유하고 저장 모델과 권한 규칙은 분리한다.

주요 환경 변수는 다음과 같다.

```text
PORT=3000
DATABASE_URL=postgresql://...
OMS_ALLOWED_ORIGIN=http://localhost:8080
OMS_JWT_PUBLIC_KEY=...
OMS_EVENT_HMAC_SECRET=...
NOTIFICATION_RETENTION_DAYS=90
```

로컬 기본 데이터베이스 이름은 `notification`, 서비스 계정은 `notification`을 사용한다. 비밀번호는 저장소에 커밋하지 않는다.

## 15. 장애와 복구

- **실시간 서비스 중단:** OMS Outbox가 이벤트를 보존하고 재시도한다.
- **PostgreSQL 중단:** 내부 이벤트 API는 `503`을 반환하며 OMS가 재시도한다.
- **Socket.IO 전송 실패:** 저장된 알림은 유지되고 재접속 시 HTTP로 복구한다.
- **OMS 중단:** 기존 소켓은 JWT 만료까지만 유지되며 새 토큰과 새 업무 이벤트는 발급되지 않는다.
- **중복 전송:** `eventId`와 `eventHash`로 정상 중복과 계약 충돌을 구분한다.
- **영구 실패:** Outbox를 `FAILED`로 전환하고 원인 확인 후 수동 재전송한다.

로그에는 `eventId`, 알림 ID, 이벤트 타입, 처리 결과를 구조화해 남긴다. JWT, HMAC 비밀키, 알림 본문 전체, 개인정보는 로그에 남기지 않는다.

## 16. 테스트 전략

### OMS

- 업무 상태 변경과 Outbox 저장의 원자성 통합 테스트
- 각 고객 노출 상태가 정확한 이벤트 한 건을 만드는 테스트
- 재시도, 처리 제한 시간 회수, 영구 실패, 수동 재전송 테스트
- JWT의 서명, `iss`, `aud`, `sub`, `role`, 5분 만료 테스트
- 미인증과 CSRF 실패 요청 거부 테스트

### 실시간 서비스

- 이벤트 타입별 문구와 링크 생성 단위 테스트
- HMAC 정상, 변조, 만료, 이벤트 ID 불일치 테스트
- 새 이벤트, 정상 중복, 충돌 중복 수신 통합 테스트
- 회원별 목록 격리, 커서 페이지네이션, 읽음 멱등성 테스트
- JWT 정상, 만료, 잘못된 발급자와 대상, 변조 테스트
- Socket.IO 연결 거부, 사용자 채널 격리, 신규 알림 수신 E2E 테스트
- 90일 경계와 만료 삭제 테스트

### 계약과 수동 시나리오

- 두 저장소가 같은 JSON 예제와 상태 코드 계약을 검증한다.
- 실시간 서비스 중단 중 주문 처리 후 재기동했을 때 알림이 한 번만 생성되는지 확인한다.
- 오프라인 주문 진행 후 로그인했을 때 알림함에서 이력을 확인한다.
- 다른 사용자의 JWT와 알림 ID로 데이터에 접근할 수 없는지 확인한다.
- JWT 만료 후 자동 재발급과 Socket.IO 재연결을 확인한다.

CI에서는 PostgreSQL 서비스 컨테이너를 사용하고 단위 테스트와 통합 테스트를 분리해 실패 원인을 확인할 수 있게 한다.

## 17. 배포와 확장

실시간 서비스는 OMS, WMS와 별도 Railway 서비스 및 PostgreSQL로 배포한다. 브라우저가 Socket.IO에 접근해야 하므로 실시간 서비스 공개 URL이 필요하며, 내부 이벤트 API는 동일 서비스에서 HMAC으로 보호한다.

1차는 Node.js 단일 인스턴스로 운영한다. 인스턴스가 여러 대가 되면 Redis와 Socket.IO Redis Adapter를 추가해 사용자 채널과 강제 연결 종료 이벤트를 공유한다. 이벤트 생산 서비스가 늘거나 HTTP Outbox 운영 비용이 실제 문제가 될 때 Kafka 또는 RabbitMQ를 검토한다.

실제 PG 연동, 쿠폰, 포인트, 푸시 알림, 이메일/SMS 채널, 대화방과 메시지, 파일 첨부, 상담 배정은 이번 범위에 포함하지 않는다.

## 18. 완료 기준

- OMS 상태 변경 트랜잭션과 알림 Outbox가 함께 커밋 또는 롤백된다.
- 실시간 서비스 중단 후 복구해도 알림이 유실되거나 중복 생성되지 않는다.
- 로그인 사용자가 자신의 알림만 실시간 및 알림함에서 확인할 수 있다.
- 알림의 개별 읽음, 전체 읽음, 미확인 개수가 일관된다.
- 만료된 알림이 90일 정책에 따라 삭제된다.
- JWT, HMAC, Origin, 사용자별 데이터 격리 테스트가 통과한다.
- 기존 OMS 주문 기능은 실시간 서비스 장애와 무관하게 동작한다.
