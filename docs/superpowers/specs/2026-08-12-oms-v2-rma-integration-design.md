# OMS V2 RMA 연동 설계

## 1. 목표와 범위

OMS 고객이 배송 완료된 주문의 상품을 수량 단위로 반품 신청하고, WMS의 RMA 입고·검수 결과를 안정적으로 동기화해 Thymeleaf 주문 화면에서 확인할 수 있게 한다.

이번 작업은 다음 흐름까지 완성한다.

```text
고객 반품 신청
→ OMS 요청 영속화
→ WMS RMA 접수
→ WMS 입고·검수
→ OMS 결과 동기화
→ 고객 반품 현황 표시
```

토스페이먼츠 결제·환불은 포함하지 않는다. 이후 작업에서 `COMPLETED` 품목의 승인 수량을 환불 입력으로 사용한다.

## 2. 책임 경계

### OMS

- 고객 반품 신청과 `requestKey`
- 주문상품별 요청 수량과 잔여 반품 가능 수량
- WMS `rmaId`와 동기화 상태
- WMS 검수 결과의 승인 수량과 처분
- 고객용 반품 상세와 주문 상세의 반품 현황
- WMS 접수 재시도와 상태 조회 보상 스윕

### WMS

- 주문 출고 여부 검증
- RMA 접수·입고·검수·취소
- 품목별 승인 수량과 `RESTOCKED`·`DISPOSED`·`REJECTED` 판정
- `RESTOCKED` 재고 증가와 `RETURN` 원장
- OMS 결과 콜백과 RMA 단건 조회 API

WMS는 `orderItemId`를 불투명 값으로 저장·반환한다. 출고량과 누적 반품량은 `orderId + productId` 합계로 검증하고, OMS는 `orderItemId`별 신청 가능 수량과 결과 일치 여부를 검증한다.

## 3. 배송 상태

현재 `DeliveryStatus.COMP`는 실제 배송 완료가 아니라 WMS 출고 완료를 뜻한다. 이를 다음 상태로 분리한다.

```text
READY → SHIPPED → DELIVERED
```

- 기존 관리자 출고 처리는 WMS 출고 성공 후 `SHIPPED`로 전환한다.
- 관리자가 Thymeleaf 배송 관리 화면에서 `SHIPPED → DELIVERED`를 수동 처리한다.
- 고객 반품 신청은 `DELIVERED` 주문만 허용한다.
- 기존 주문 취소는 `READY`에서만 가능하며 `SHIPPED` 이후에는 차단한다.
- 기존 DB의 `COMP` 값은 의미가 같은 `SHIPPED`로 마이그레이션한다.

실제 택배사 연동과 배송 추적은 비범위다.

## 4. OMS 데이터 모델

### `CustomerReturn`

| 필드 | 규칙 |
|---|---|
| `id` | OMS 반품 식별자 |
| `order` | 반품 대상 주문 |
| `requestKey` | OMS가 생성하는 UUID, UNIQUE |
| `rmaId` | WMS RMA 식별자, UNIQUE, 접수 전 nullable |
| `status` | OMS 반품 상태 |
| `reason` | 고객 반품 사유 |
| `failureReason` | 영구 접수 실패 원인, nullable, 고객에게 원문 미노출 |
| `requestedAt` | 고객 신청 시각 |
| `updatedAt` | 마지막 상태 변경 시각 |
| `completedAt` | 최종 상태 전환 시각, nullable |

### `CustomerReturnItem`

| 필드 | 규칙 |
|---|---|
| `id` | OMS 반품 품목 식별자 |
| `customerReturn` | 소속 반품 |
| `orderItem` | 대상 주문상품 |
| `requestedQuantity` | 고객 요청 수량, 1 이상 |
| `acceptedQuantity` | WMS 승인 수량, 완료 전 nullable |
| `disposition` | `RESTOCKED`, `DISPOSED`, `REJECTED`, 완료 전 nullable |

`productId`는 중복 저장하지 않고 `orderItem.product.id`에서 얻는다. `(customer_return_id, order_item_id)`는 UNIQUE로 제한해 한 RMA 안의 주문상품 중복을 막는다.

## 5. 상태 모델

OMS 반품 상태는 다음과 같다.

```text
PENDING_SUBMISSION → REQUESTED → RECEIVED → COMPLETED
         │               └───────────────→ CANCELLED
         └→ SUBMISSION_FAILED
```

- `PENDING_SUBMISSION`: OMS에는 저장됐지만 WMS 접수가 확정되지 않은 상태다.
- `SUBMISSION_FAILED`: WMS 400·409처럼 동일 요청 재시도로 회복할 수 없는 접수 실패다.
- `REQUESTED`, `RECEIVED`, `COMPLETED`, `CANCELLED`: WMS 상태와 대응한다.
- WMS가 빠르게 처리하면 `REQUESTED → COMPLETED` 직접 전환을 허용한다.
- WMS 콜백이 접수 응답보다 먼저 도착하면 검증된 콜백으로
  `PENDING_SUBMISSION → REQUESTED/RECEIVED/COMPLETED/CANCELLED` 직접 수렴을 허용한다.
- `CANCELLED`는 `REQUESTED`에서만 허용한다.
- `RECEIVED` 이후에는 취소하지 않고 검수 결과로 완료한다.
- `COMPLETED`, `CANCELLED`는 변경할 수 없다.
- 같은 상태를 다시 받으면 멱등 no-op으로 처리한다.
- 이전 상태로 되돌아가는 응답은 적용하지 않고 식별자를 포함해 경고 로그를 남긴다.

`SUBMISSION_FAILED` 요청은 수량 집계에서 제외하므로 고객이 새 `requestKey`로 다시 신청할 수 있다.

## 6. 반품 수량과 동시성

OMS는 주문상품별로 다음 불변식을 검증한다.

```text
COMPLETED의 acceptedQuantity 합
+ PENDING_SUBMISSION/REQUESTED/RECEIVED의 requestedQuantity 합
+ 신규 requestedQuantity
≤ OrderItem.count
```

`CANCELLED`, `SUBMISSION_FAILED`, `COMPLETED`의 미승인 잔여 수량은 계산하지 않는다.

동일 주문에 대한 동시 신청은 주문 행을 `PESSIMISTIC_WRITE`로 잠근 뒤 기존 반품 수량을 조회하고 신규 요청을 저장해 직렬화한다. 기존 `Order.version` 낙관적 락은 반품 INSERT만으로 증가하지 않으므로 이 검증에 사용하지 않는다.

WMS 결과를 반영할 때 각 품목은 다음을 만족해야 한다.

```text
0 ≤ acceptedQuantity ≤ requestedQuantity
acceptedQuantity == 0 → disposition == REJECTED
acceptedQuantity > 0  → disposition == RESTOCKED 또는 DISPOSED
```

부분 승인에서 승인되지 않은 잔여 수량은 암묵적으로 거절한다. 동일 `productId`에 여러 `orderItemId`가 있어도 OMS 품목과 결과는 `orderItemId`별로 유지한다.

## 7. 고객 웹 흐름

### 주문 상세

`GET /orders/{orderId}`에 다음 정보를 추가한다.

- 주문상품 ID와 주문 수량
- 완료 승인 수량
- 처리 중 요청 수량
- 신규 신청 가능 수량
- 기존 반품 목록과 상태

`DELIVERED`이고 신청 가능 수량이 있는 주문에만 반품 폼을 표시한다.

### 반품 신청

```http
POST /orders/{orderId}/returns
```

폼은 `reason`과 `items[index].orderItemId`, `items[index].quantity`를 전송한다. 수량 0인 품목은 제외하며 선택 품목이 없으면 인라인 검증 오류를 표시한다. 성공과 실패 모두 PRG 패턴으로 주문 상세에 돌아온다.

### 반품 상세

```http
GET /returns/{returnId}
```

요청·승인 수량, 상태, 품목별 처분을 표시한다. 타인의 주문 또는 반품은 존재를 숨기기 위해 `404`로 처리한다. 내부 WMS 오류 원문과 `failureReason`은 고객에게 노출하지 않는다.

## 8. WMS 계약

OMS 내부 포트는 두 동작만 제공한다.

```text
ReturnPort.create(request)
ReturnPort.find(rmaId)
```

`WmsReturnAdapter`는 기존 `wms.base-url`, `wms.basic.user/password`를 재사용한다.

### 접수

```http
POST /api/returns
```

```json
{
  "requestKey": "UUID",
  "orderId": 100,
  "reason": "상품 불량",
  "items": [
    {"orderItemId": 501, "productId": 1, "quantity": 1}
  ]
}
```

- 신규: `201`
- 같은 키와 같은 내용: `200`과 기존 `rmaId`
- 같은 키와 다른 내용: `409`
- 잘못된 상태·수량: `400` 또는 `409`

별도 `Idempotency-Key` 헤더는 사용하지 않는다.

### 단건 조회

```http
GET /api/returns/{rmaId}
```

조회 응답은 `rmaId`, `requestKey`, `orderId`, 상태와 모든 품목의 `orderItemId`, `productId`, 요청·승인 수량, 처분을 포함한다.

## 9. 안전한 접수와 재처리

WMS HTTP 호출은 OMS 저장 트랜잭션 안에서 수행하지 않는다.

```text
1. ReturnService가 PENDING_SUBMISSION 저장 후 커밋
2. ReturnSubmissionService가 영속 요청을 읽어 WMS 호출
3. 성공하면 별도 트랜잭션으로 rmaId + REQUESTED 저장
4. 실패하면 저장 상태에 따라 보상 스윕이 재처리
```

정상 요청은 컨트롤러가 1번 커밋 뒤 2번을 즉시 한 번 실행한다.

- 네트워크·WMS 5xx: `PENDING_SUBMISSION` 유지
- WMS 401·403: 설정 장애로 보고 `PENDING_SUBMISSION` 유지, 오류 로그
- WMS 400·409: `SUBMISSION_FAILED`

OMS 저장 뒤 WMS 호출 전에 프로세스가 종료되면 스윕이 요청을 전송한다. WMS 접수 뒤 OMS 응답 저장 전에 종료되면 같은 `requestKey` 재전송이 기존 `rmaId`를 반환해 수렴한다. 어댑터 자체의 숨은 재시도는 두지 않는다.

## 10. WMS 결과 콜백

```http
POST /api/return-status-events
Authorization: Basic ...
```

WMS는 `COMPLETED`, `CANCELLED` 커밋 후 콜백을 best-effort로 보낸다. OMS는 기존 `oms.callback.user/password` 인증을 재사용하고 보안 체인의 대상을 `/api/replenishments`, `/api/return-status-events`로 확장한다.

OMS는 `requestKey`로 반품을 찾고 다음을 원 요청과 대조한다.

- `rmaId`
- `orderId`
- 모든 `orderItemId`
- `orderItemId`별 `productId`
- 요청 수량
- 승인 수량과 처분 불변식

로컬 `rmaId`가 아직 null이면 검증된 콜백의 `rmaId`를 최초 결합하고, 이미 값이 있으면 반드시
일치해야 한다. WMS 커밋 후 콜백이 POST 응답보다 먼저 도착할 수 있기 때문이다. 뒤늦게 도착한
POST 응답은 같은 `rmaId`만 확인하고 이미 더 앞선 상태를 `REQUESTED`로 되돌리지 않는다.

정상 중복은 `200`, 계약 불일치는 상태를 변경하지 않고 `409`를 반환한다. `eventId`는 사용하지 않으며 `rmaId + status`와 상태 전이 규칙으로 멱등성을 보장한다.

`CANCELLED` 품목은 `acceptedQuantity = 0`, `disposition = null`을 허용한다. `COMPLETED` 품목은 `disposition`이 반드시 있어야 한다.

## 11. 보상 스윕

기존 Spring `@Scheduled` 패턴을 재사용한다.

```text
PENDING_SUBMISSION
→ POST /api/returns 재전송

REQUESTED / RECEIVED
→ GET /api/returns/{rmaId}
→ 더 최신 상태면 OMS에 반영
```

- 네트워크·5xx·인증 실패: 현재 상태 유지, 다음 주기에 재시도
- 조회 404·계약 불일치: 상태 변경 없이 오류 로그
- 빈 대상이면 WMS를 호출하지 않는다.

새 메시지 브로커나 outbox는 도입하지 않는다. 영속 요청과 멱등 WMS API, 단건 조회가 이 규모의 유실 복구를 충족한다.

## 12. 마이그레이션

`V5__add_customer_returns.sql` 하나에 다음을 포함한다.

- `customer_return`, `customer_return_item` 테이블
- Hibernate 기본 전략에 맞는 시퀀스
- FK와 UNIQUE 제약
- `delivery.status = 'COMP'`를 `SHIPPED`로 변경

운영 PostgreSQL은 기존대로 Flyway와 `ddl-auto: validate`를 사용한다. JPA 자동 스키마 생성을 운영 대안으로 사용하지 않는다.

## 13. 테스트

### 도메인

- 허용·금지 상태 전이와 최종 상태 불변성
- 승인 수량과 처분 조합
- 부분 승인 잔여 수량
- 배송 완료 전 신청 차단

### 서비스·저장소

- 상태별 누적 반품 수량 계산
- 주문 행 잠금으로 동시 초과 신청 차단
- 소유권 검증
- `requestKey`, `rmaId`, RMA 내 주문상품 UNIQUE 제약

### WMS 어댑터

- 접수 `201`, 멱등 `200`, 영구 실패 `400/409`
- 네트워크·5xx와 인증 실패 분류
- 단건 조회 계약
- 동일 `productId`의 여러 `orderItemId` 보존

### 콜백·스윕

- `COMPLETED`, `CANCELLED` 반영
- 중복 no-op과 이전 상태 무시
- 주문·상품·수량 불일치 `409`
- 접수 응답 유실과 결과 콜백 유실 복구
- 잘못된 Basic 인증 `401`

### MVC

- 신청 가능 수량과 복수 품목 폼
- 빈 요청·초과 수량 인라인 오류
- 반품 상태와 검수 결과 표시
- CSRF와 타인 주문·반품 접근 차단

마지막에 OMS 전체 Gradle 테스트를 캐시 없이 실행하고 수동 E2E를 확인한다.

## 14. 배포와 수동 검증

배포 순서는 호환 가능한 WMS 확장부터 시작한다.

```text
1. WMS RMA API·관리자 화면 배포
2. WMS 접수·조회·검수 계약 스모크 테스트
3. OMS V5 마이그레이션 포함 배포
4. 반품 신청 → 접수 → 입고 → 검수 → 콜백 확인
5. OMS 중단 상태에서 WMS 검수 완료
6. OMS 재기동 후 단건 조회 보상 복구 확인
```

WMS 확장은 기존 OMS 호출 경로를 바꾸지 않으므로 feature flag를 추가하지 않는다.

## 15. 로그

RMA 관련 로그에는 다음 식별자를 일관되게 포함한다.

```text
returnId, requestKey, rmaId, orderId, status
```

고객 반품 사유, 인증정보, 내부 응답 본문은 로그에 남기지 않는다.

## 16. 비범위

- 토스페이먼츠와 환불 상태
- 실제 택배사 회수·배송 추적
- 교환과 반품 배송비
- 접수 후 RMA 품목·수량 수정
- 품목별 분할 입고·분할 완료
- 동일 주문상품 승인 수량의 혼합 처분
- Kafka·outbox
- 모바일 앱
