# OMS V2 모의 결제·비동기 환불 설계

## 1. 목표

OMS V2의 배송 완료·반품 흐름에 모의 결제와 환불을 연결해 다음 주문 생명주기를 완성한다.

```text
주문 생성
→ 결제 승인
→ 재고 확보 또는 백오더
→ 주문 취소 시 전액 환불
→ 반품 검수 승인 수량만큼 부분 환불
```

V2는 결제·환불 도메인과 장애 복구를 검증하는 단계다. 실제 PG 테스트 결제, 웹훅, 쿠폰, 포인트는 V3로 분리한다.

## 2. 범위

### 포함

- 모의 카드 결제 승인
- 결제 실패 주문 보존과 고객 재결제
- 결제 승인 후 비동기 WMS 재고 할당
- 재고가 부족한 유료 주문의 `BACKORDERED` 처리
- 일반·백오더 주문 취소 시 전액 환불
- WMS 반품 검수 승인 수량 기준 부분 환불
- DB 기반 비동기 환불 작업과 제한 재시도
- 환불·재고 할당 수동 확인 관리자 화면
- 고객 결제·환불 현황 표시
- 멱등성, 동시성, 재기동 복구

### 제외

- 실제 PG API·결제창·웹훅
- 카드번호·비밀번호 등 결제 수단 정보 저장
- 쿠폰, 포인트, 배송비, 세금, 복합 할인 배분
- 환불 계좌, 현금 결제, 다중 통화
- Kafka 등 별도 메시지 브로커
- 관리자의 근거 없는 강제 환불 완료 처리

## 3. 책임과 구성

```text
OrderController / ReturnSyncService
              ↓
        PaymentFacade
        ├─ OrderService
        ├─ PaymentService
        ├─ RefundService
        ├─ AllocationService
        └─ PaymentGateway
             └─ MockPaymentGateway (V2)
```

- `PaymentFacade`는 주문·결제·취소·환불 유스케이스의 진입점이다.
- 파사드는 외부 호출을 포함하는 장기 DB 트랜잭션을 열지 않는다.
- 각 서비스는 짧은 트랜잭션으로 상태를 저장한다.
- `PaymentGateway`는 승인·환불 외부 계약이다. V3는 이 포트 뒤에 실제 PG 어댑터를 추가한다.
- WMS 할당과 결제·환불 작업은 영속 상태를 먼저 저장한 후 처리한다.

## 4. 주문 상태

`OrderStatus`에 결제·할당 상태를 추가한다.

```text
PAYMENT_PENDING
PAYMENT_FAILED
PAYMENT_REVIEW
ALLOCATION_PENDING
ALLOCATION_PROCESSING
ALLOCATION_REVIEW
CANCEL_REQUESTED
ORDER
BACKORDERED
CANCEL
```

- `PAYMENT_PENDING`: 결제 승인 결과를 기다린다.
- `PAYMENT_FAILED`: 명시적으로 거절된 결제다. 고객이 다시 결제할 수 있다.
- `PAYMENT_REVIEW`: 승인 결과를 확정하지 못해 관리자 확인이 필요하다.
- `ALLOCATION_PENDING`: 결제됐고 WMS 할당을 기다린다.
- `ALLOCATION_PROCESSING`: 작업자가 해당 주문의 WMS 할당을 수행 중이다.
- `ALLOCATION_REVIEW`: 할당 재시도 소진 또는 영구 실패다.
- `CANCEL_REQUESTED`: 결제 승인 또는 재고 할당 처리 중 고객 취소가 들어와 후속 정리가 필요하다.
- 기존 `ORDER`, `BACKORDERED`, `CANCEL` 의미와 배송 상태 머신은 유지한다.

주문에는 할당 복구용 `allocationAttemptCount`, `nextAllocationAttemptAt`, `allocationFailureCode`, `allocationProcessingAt`을 저장한다.

## 5. 결제 데이터 모델

### `Payment`

주문당 하나의 결제 집계를 저장한다.

| 필드 | 규칙 |
|---|---|
| `id` | 결제 식별자 |
| `order` | `order_id UNIQUE` |
| `status` | 결제 집계 상태 |
| `orderAmount` | 주문 당시 총액, KRW 정수 |
| `paidAmount` | 승인 완료 금액 |
| `pendingRefundAmount` | 처리 중·수동 확인 중 환불액 |
| `refundedAmount` | 완료된 누적 환불액 |
| `approvedAt` | 승인 시각, nullable |
| `updatedAt` | 마지막 변경 시각 |
| `version` | 낙관적 락 |

결제 상태는 다음과 같다.

```text
PENDING
PAYMENT_FAILED
PAYMENT_REVIEW
PAID
PARTIALLY_REFUNDED
REFUNDED
CANCELLED
```

### `PaymentAttempt`

고객의 결제 시도마다 별도 행을 저장한다.

| 필드 | 규칙 |
|---|---|
| `payment` | 소속 결제 |
| `requestKey` | 승인 멱등키, UNIQUE |
| `status` | `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW`, `CANCELLED` |
| `gatewayTransactionId` | 모의 또는 실제 PG 거래 식별자 |
| `attemptCount` | 같은 키의 자동 재시도 횟수 |
| `nextAttemptAt` | 다음 자동 재시도 시각 |
| `failureCode`, `failureReason` | 내부 운영 정보 |
| 시각 필드 | 생성·완료·마지막 변경 시각 |

자동 재시도는 같은 `requestKey`를 사용한다. 고객이 `다시 결제`를 누르면 새 `PaymentAttempt`와 새 `requestKey`를 생성한다.

## 6. 환불 데이터 모델

### `RefundRequest`

| 필드 | 규칙 |
|---|---|
| `payment` | 환불 대상 결제 |
| `requestKey` | 환불 멱등키, UNIQUE |
| `sourceType` | `ORDER_CANCEL`, `RETURN` |
| `sourceId` | 주문 ID 또는 OMS 반품 ID |
| `amount` | 환불 요청 금액 |
| `status` | 환불 작업 상태 |
| `gatewayTransactionId` | 성공한 모의 또는 실제 PG 환불 거래 식별자 |
| `attemptCount` | 최초 시도를 포함한 실행 횟수 |
| `nextAttemptAt` | 다음 실행 시각 |
| `lastFailureCode`, `lastFailureReason` | 내부 운영 정보 |
| 시각 필드 | 생성·완료·마지막 변경 시각 |
| `version` | 낙관적 락 |

환불 상태는 다음과 같다.

```text
PENDING → PROCESSING → SUCCEEDED
                 ├─→ RETRYING
                 └─→ MANUAL_REVIEW
RETRYING → PROCESSING
MANUAL_REVIEW → PROCESSING (관리자 재시도)
```

`(source_type, source_id)`에 UNIQUE 제약을 둔다. 주문 취소와 WMS 반품 완료 콜백이 중복돼도 같은 업무 원천에서 환불 요청을 두 번 만들 수 없다.

## 7. 주문·결제 흐름

```text
1. 주문과 Payment를 한 트랜잭션으로 저장
2. 주문은 PAYMENT_PENDING, 결제는 PENDING으로 두고 결제 페이지로 이동
3. 고객이 최종 결제 버튼을 누르면 첫 PaymentAttempt를 만들고 PaymentGateway.approve를 호출
4. 승인 성공
   - PaymentAttempt → SUCCEEDED
   - Payment → PAID
   - Order → ALLOCATION_PENDING
5. 명시적 승인 거절
   - PaymentAttempt → FAILED
   - Payment → PAYMENT_FAILED
   - Order → PAYMENT_FAILED
6. 통신 실패 또는 결과 불명
   - 같은 requestKey로 제한 재시도
   - 소진 시 Payment/Order → PAYMENT_REVIEW
```

결제에 성공하기 전에는 WMS를 호출하지 않는다. 재고가 없어도 결제는 승인하며, 이후 할당 결과가 부족이면 `BACKORDERED`로 전환한다.

승인 작업은 시도를 `PROCESSING`으로 원자적 선점한 뒤 게이트웨이를 호출한다. 처리 중 고객 취소가 들어오면 주문을 `CANCEL_REQUESTED`로 둔다. 늦게 도착한 승인 결과가 성공이면 결제를 `PAID`로 확정하고 즉시 전액 환불 요청을 생성한다. 거절이면 결제와 시도를 `CANCELLED`로 바꾸고 환불 없이 주문 취소를 확정한다. 아직 선점되지 않은 `PENDING` 시도는 취소와 함께 `CANCELLED`로 종료한다.

기준 시간 이상 남은 `PROCESSING` 시도는 같은 `requestKey`로 복구한다. V3에서는 재호출 전에 PG 거래 조회로 승인 여부를 먼저 확인한다.

고객 재결제는 주문 소유권, `PAYMENT_FAILED` 상태, 주문 금액 불변을 확인한 후 새 승인 시도를 만든다. 이미 결제됐거나 취소된 주문은 다시 결제할 수 없다.

## 8. 비동기 재고 할당

결제 성공 트랜잭션은 주문을 `ALLOCATION_PENDING`으로 저장하고 종료한다. 처리기는 5초 이내 신규 대상을 찾는다.

```text
ALLOCATION_PENDING/재시도 도래
→ ALLOCATION_PROCESSING으로 원자적 선점
→ WMS reserveAll(orderId, quantities) 호출
→ 성공: ORDER
→ 재고 부족: BACKORDERED
→ 일시 실패: ALLOCATION_PENDING + 다음 시각
→ 영구 실패 또는 재시도 소진: ALLOCATION_REVIEW
```

처리기가 `ALLOCATION_PROCESSING`인 동안 고객 취소가 들어오면 주문을 `CANCEL_REQUESTED`로 바꾼다. 할당 결과가 성공이면 WMS 예약을 즉시 해제하고, 실패·부족이면 별도 해제 없이 취소를 확정한다. 그 후 전액 환불 요청을 생성한다.

처리 중 서버가 종료되면 기준 시간 이상 지난 `ALLOCATION_PROCESSING`을 다시 `ALLOCATION_PENDING`으로 복구한다. WMS 예약은 `orderId` 멱등성을 사용하므로 같은 주문을 재처리해도 중복 예약하지 않는다.

## 9. 주문 취소와 전액 환불

- `PAYMENT_PENDING`: 승인 전이면 결제를 `CANCELLED`로 바꾸고 즉시 `CANCEL`; 승인 처리 중이면 `CANCEL_REQUESTED`로 전환해 승인 결과에 따라 환불 여부 확정
- `PAYMENT_FAILED`: 결제를 `CANCELLED`로 바꾸고 즉시 `CANCEL`, 환불 없음
- `PAYMENT_REVIEW`: 관리자 확인 전 중복 결제 가능성을 배제할 수 없으므로 `CANCEL_REQUESTED`로 두고 결제 확인 후 환불 여부를 확정
- `ALLOCATION_PENDING`, `ALLOCATION_REVIEW`, `BACKORDERED`: 즉시 취소 후 결제 전액 환불 요청
- `ALLOCATION_PROCESSING`: 8장의 경합 절차 적용
- `ORDER`: 기존 WMS 예약 해제 성공 후 취소와 전액 환불 요청 저장
- `SHIPPED` 이후: 기존 정책대로 고객 취소 불가

주문 상태 변경과 `RefundRequest` 생성은 같은 OMS 트랜잭션에서 처리한다. 환불 게이트웨이 호출은 커밋 이후 별도 처리기가 수행한다.

결제되지 않은 주문 취소는 `RefundRequest`를 만들지 않는다.

## 10. 반품과 부분 환불

WMS 검수 결과가 `COMPLETED`로 검증될 때 같은 OMS 트랜잭션에서 환불 요청을 생성한다.

```text
반품 환불액 = Σ(주문 당시 단가 × acceptedQuantity)
```

- `RESTOCKED`, `DISPOSED`: 승인 수량만큼 환불
- `REJECTED`: 승인 수량이 0이므로 환불 없음
- 부분 승인: 요청 수량이 아니라 승인 수량만 환불
- 환불액 0: `RefundRequest`를 만들지 않음
- 동일 반품 콜백 재수신: 기존 환불 요청을 그대로 반환하는 멱등 no-op

환불 요청 생성 시 `Payment`를 잠그고 다음을 검증한다.

```text
refundedAmount + pendingRefundAmount + 신규 환불액 <= paidAmount
```

요청 저장과 동시에 `pendingRefundAmount`를 증가시킨다. 성공하면 해당 금액을 `pendingRefundAmount`에서 빼고 `refundedAmount`에 더한다. `MANUAL_REVIEW` 금액은 해결 전까지 pending에 포함해 과환불을 막는다.

## 11. 재시도와 실패 분류

자동 재시도는 최초 실행 이후 최대 4회다.

```text
1분 → 5분 → 30분 → 2시간
```

- 일시 실패: 네트워크, 타임아웃, 외부 5xx. 다음 시각에 같은 멱등키로 재시도한다.
- 영구 실패: 잘못된 금액, 환불 가능 금액 초과 등 업무 오류. 즉시 `MANUAL_REVIEW`로 전환한다.
- 결과 불명: V2 모의 게이트웨이는 유형화된 결과를 반환한다. V3는 PG 조회 API로 거래 상태를 확인한 뒤 재시도 여부를 결정한다.
- 자동 재시도 소진: `MANUAL_REVIEW` 또는 `ALLOCATION_REVIEW`로 전환한다.

서버 재기동 후 처리기는 DB에서 미완료 작업을 다시 조회한다. 고객에게는 내부 실패 코드 대신 `결제 확인 중`, `환불 확인 중`, `재고 확인 지연`처럼 안전한 문구를 표시한다.

## 12. 동시성과 멱등성

- `Payment.order_id` UNIQUE
- `PaymentAttempt.request_key` UNIQUE
- `RefundRequest.request_key` UNIQUE
- `RefundRequest(source_type, source_id)` UNIQUE
- 결제·환불·할당 외부 호출은 재시도 시 같은 멱등키 사용
- 고객의 새 결제 시도만 새 승인 키 사용
- `Order`, `Payment`, `RefundRequest`에 낙관적 락 적용
- 환불 예약 금액 변경은 결제 행 잠금 아래 수행
- 작업 선점은 상태 조건부 갱신 또는 잠금 조회로 한 작업자만 수행
- 처리 완료 상태는 이전 상태로 회귀하지 않음
- 관리자 수동 재시도와 스케줄러가 경합해도 한 요청만 외부 호출하도록 선점 검증

## 13. 고객 화면과 엔드포인트

### 주문서

- 주문 생성 버튼은 `결제 단계로 이동`으로 표시한다.
- 주문 생성 뒤 별도 결제 페이지에서 상품·배송지·최종 금액을 다시 확인한다.
- 결제 수단은 `모의 카드` 하나이며 카드정보는 받거나 저장하지 않는다.
- 결제 페이지를 이탈한 주문은 `결제 대기`로 보존하고 내 주문에서 이어서 결제할 수 있다.

### 내 주문·주문 상세

- 결제 상태, 결제액, 처리 중 환불액, 누적 환불액
- 결제 실패 시 `다시 결제`
- `재고 확인 중`, `재고 확인 지연`, `주문 취소 처리 중`
- 유료 백오더는 `결제 완료 · 입고 대기`
- 기존 주문·배송 타임라인은 유지하고 결제 정보는 별도 영역에 표시

### 엔드포인트

```text
POST /orders/checkout
GET  /orders/{orderId}/payment
POST /orders/{orderId}/payment/approve
POST /orders/{orderId}/payment/retry
POST /orders/{orderId}/cancel
GET  /orders
GET  /orders/{orderId}
```

모든 고객 변경 요청은 `USER` 권한, 주문 소유권 검사, CSRF 검증을 적용한다. 타인 주문은 `404`로 숨긴다.

## 14. 관리자 화면과 권한

```text
GET  /admin/payments
POST /admin/refunds/{refundId}/retry
POST /admin/orders/{orderId}/allocation/retry
```

- 결제·환불 상태 필터
- 주문번호, 반품번호, 금액, 멱등키
- 시도 횟수, 마지막 실패 원인, 다음 시도 시각
- `MANUAL_REVIEW` 환불 다시 시도
- `ALLOCATION_REVIEW` 할당 다시 시도
- 대시보드의 `환불 확인 필요`, `재고 할당 확인 필요` 건수

관리자 변경 요청은 `ADMIN` 권한과 CSRF 검증을 적용한다. 실제 게이트웨이 근거 없이 상태를 성공으로 바꾸는 기능은 제공하지 않는다.

## 15. 모의 게이트웨이

```java
interface PaymentGateway {
    ApprovalResult approve(ApprovalCommand command);
    RefundResult refund(RefundCommand command);
}
```

`MockPaymentGateway`는 기본 실행에서 승인과 환불에 성공하고, 거래 식별자를 생성한다. 자동 테스트는 성공, 명시적 거절, 일시 실패, 영구 실패, 결과 불명을 주입할 수 있다. 실패 주입용 고객 UI나 운영 엔드포인트는 만들지 않는다.

## 16. 기존 데이터와 초기화

기존 주문은 실제 결제 근거가 없으므로 가상의 결제 이력을 역산하지 않는다. 구현 완료 후 OMS와 WMS 개발 DB를 함께 초기화하고 결제 포함 시드 데이터로 다시 검증한다.

배포 전에는 두 애플리케이션을 중지한 상태에서 함께 초기화한다. 한쪽 DB만 초기화해 주문번호가 재사용되는 상태를 만들지 않는다.

새 시드는 다음 상태를 최소 한 건씩 제공한다.

- 결제 완료·재고 확보
- 결제 완료·백오더
- 결제 실패
- 부분 환불
- 전액 환불
- 환불 `MANUAL_REVIEW`
- 할당 `ALLOCATION_REVIEW`

## 17. 자동 검증

- 모든 결제·환불·주문 상태 전이와 불법 전이
- 결제 실패 후 새 시도로 재결제
- 승인 전 WMS 미호출
- 결제 승인 후 재고 확보·백오더 분기
- 결제 승인 응답 유실과 같은 키 재처리
- 백오더·할당 대기·일반 주문 취소의 전액 환불
- 할당 처리와 고객 취소 경합
- 반품 승인 수량 기준 부분 환불
- 여러 반품의 누적 환불과 과환불 차단
- 중복 WMS 콜백과 중복 취소 멱등성
- 재시도 간격, 최대 4회, 영구 실패 분기
- 서버 재기동 시 미완료 작업 조회
- 관리자와 스케줄러의 작업 선점 경합
- 고객 소유권, 관리자 권한, CSRF
- 고객 화면 내부 실패 코드 미노출
- 기존 주문·배송·반품 전체 회귀 테스트

## 18. 수동 검증

1. 정상 결제 후 `ORDER`
2. 정상 결제 후 `BACKORDERED`, 입고 뒤 자동 승격
3. 결제 실패, WMS 미호출, 고객 재결제 성공
4. 백오더 취소 후 전액 환불
5. 재고 확보 주문 취소 후 예약 해제·전액 환불
6. 전량 반품 승인 후 전액 환불
7. 부분 승인 후 승인 수량만 환불
8. 검수 전량 거절 후 환불 없음
9. 환불 일시 실패 후 자동 복구
10. 환불 영구 실패 후 관리자 재시도
11. 결제 성공·할당 중 고객 취소 경합
12. OMS·WMS 재기동 후 미완료 작업 복구

## 19. V3 로드맵

- 실제 PG 테스트 결제창과 승인·취소 API
- 웹훅 서명 검증과 거래 상태 조회 보상
- 결제 수단 토큰화 및 PG 거래 조회
- 쿠폰 발급·사용·취소·복원
- 포인트 적립·사용·취소·복원
- 쿠폰·포인트·실결제 금액의 부분 환불 배분 정책
- 고객 결제 경험과 프로모션 UI 강화
- 처리량이 필요할 때 DB 작업 큐를 메시지 브로커로 교체

V2는 `orderAmount`, `paidAmount`, `pendingRefundAmount`, `refundedAmount`를 분리해 V3 할인 금액 필드를 추가할 수 있게 하되, 쿠폰·포인트 도메인을 미리 구현하지 않는다.

## 20. 완료 기준

- 이 문서의 상태 전이·금액 불변식·멱등 제약이 코드와 테스트로 검증된다.
- 결제·할당·환불 외부 호출이 DB에 복구 가능한 작업 상태를 남긴다.
- 고객은 결제 실패, 재고 확인, 환불 진행 상태를 확인하고 허용된 행동을 수행할 수 있다.
- 관리자는 수동 확인 대상을 조회하고 같은 멱등키로 다시 시도할 수 있다.
- OMS 전체 자동 테스트와 18절의 수동 시나리오가 통과한다.
- OMS·WMS 개발 DB를 함께 초기화한 뒤 주문부터 반품 환불까지 재검증한다.
