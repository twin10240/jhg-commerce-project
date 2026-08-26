# OMS 반품 승인 단계 설계

## 목적

고객의 반품 신청을 곧바로 WMS에 전송하지 않고, OMS 관리자가 판매 정책 관점에서 신청 전체를 승인하거나 반려한 뒤 승인된 건만 기존 WMS RMA 흐름으로 보낸다.

OMS 승인은 반품 가능 여부를 판단한다. 실제 도착 수량과 상품 상태, 승인 수량 및 처분(`RESTOCKED`, `DISPOSED`, `REJECTED`)은 기존처럼 WMS 검수에서 판단한다.

## 범위

포함:

- 고객 반품 신청의 OMS 승인 대기 상태
- OMS 관리자 반품 목록과 상태 필터
- 신청 전체 승인 또는 사유를 포함한 반려
- 고객 주문·반품 상세의 승인 대기·반려 상태와 반려 사유 표시
- 승인 후 기존 WMS 전송 및 장애 재시도
- 승인자와 처리 시각 기록

제외:

- OMS 관리자의 품목별 수량 변경 또는 부분 승인
- WMS RMA API·상태 머신 변경
- 자동 승인 규칙이나 관리자 역할 세분화
- 고객의 별도 반품 취소 기능

## 상태 모델

`CustomerReturnStatus`에 다음 상태를 추가한다.

- `PENDING_APPROVAL`: 고객 신청이 저장되어 OMS 관리자 판단을 기다린다.
- `REJECTED`: OMS 관리자가 사유와 함께 신청을 반려한 종결 상태다.

전체 흐름:

```text
PENDING_APPROVAL
  ├─ OMS 승인 → PENDING_SUBMISSION → REQUESTED → RECEIVED → COMPLETED
  │                              └────────────────────────→ CANCELLED
  └─ OMS 반려 → REJECTED

PENDING_SUBMISSION → SUBMISSION_FAILED  (WMS의 영구 거절)
```

기존 `PENDING_SUBMISSION`은 승인 완료 후 WMS 전송을 기다리는 기술 상태로 유지한다. 이를 승인 대기로 재사용하지 않는다. 기존 스위퍼가 `PENDING_SUBMISSION`만 재전송하기 때문에 미승인 요청이 WMS로 넘어가지 않는다.

## 도메인과 데이터

`CustomerReturn.create(...)`는 새 신청을 `PENDING_APPROVAL`로 생성한다.

`CustomerReturn`에 다음 nullable 필드를 추가한다.

- `reviewedBy`: 승인 또는 반려한 관리자 이메일
- `reviewedAt`: 승인 또는 반려 시각
- `rejectionReason`: 반려 사유. `REJECTED`에서만 필수

도메인 메서드는 다음 전이만 허용한다.

- `approve(reviewer)`: `PENDING_APPROVAL → PENDING_SUBMISSION`
- `reject(reviewer, reason)`: `PENDING_APPROVAL → REJECTED`

승인자 이메일과 반려 사유는 앞뒤 공백을 제거한다. 승인자는 비어 있을 수 없고 반려 사유는 1자 이상 500자 이하로 제한한다. 이미 처리된 신청의 재승인·재반려와 승인 후 반려는 거부한다.

반품 가능 수량 계산에서 `PENDING_APPROVAL`, `PENDING_SUBMISSION`, `REQUESTED`, `RECEIVED`는 요청 수량을 사용 중인 것으로 센다. `REJECTED`, `CANCELLED`, `SUBMISSION_FAILED`는 0으로 세어 고객이 다시 신청할 수 있게 한다. `COMPLETED`는 기존처럼 WMS 승인 수량만 센다.

## 서비스와 동시성

관리자 승인·반려 서비스는 대상 `CustomerReturn`을 비관적 쓰기 잠금으로 조회한다. 따라서 두 관리자가 동시에 처리해도 하나의 전이만 성공한다.

승인 흐름:

1. 승인 트랜잭션에서 `PENDING_APPROVAL → PENDING_SUBMISSION`으로 변경하고 승인자를 기록한다.
2. 트랜잭션 종료 후 기존 `ReturnSubmissionService.submit(returnId)`를 호출한다.
3. WMS가 성공하면 기존 동기화 로직이 `REQUESTED` 이상의 상태로 전진시킨다.
4. 일시 장애나 인증 장애면 `PENDING_SUBMISSION`에 남고 기존 `ReturnReconciliationSweeper`가 재시도한다.
5. WMS 영구 거절이면 기존대로 `SUBMISSION_FAILED`로 전환한다.

반려 흐름은 OMS 트랜잭션 안에서 `REJECTED`로 끝나며 WMS를 호출하지 않는다.

승인 직후 프로세스가 중단되어 즉시 WMS 호출이 실행되지 않아도 `PENDING_SUBMISSION`이 저장되어 있으므로 스위퍼가 복구한다. WMS 요청의 기존 `requestKey` 멱등성도 그대로 사용한다.

## 관리자 화면

OMS 내비게이션에 `반품 관리` 링크를 추가하고 `/admin/returns`에서 목록을 제공한다.

목록에는 다음 정보를 표시한다.

- 반품번호, 주문번호, 고객명
- 상품과 요청 수량
- 고객 신청 사유
- 현재 상태와 신청일시
- 처리된 건의 승인자·처리시각
- 반려 사유 또는 WMS 전송 실패 사유

기본 목록은 승인 대기 건을 먼저, 같은 상태에서는 오래된 신청부터 정렬한다. 상태 필터는 승인 대기, WMS 전송 중, WMS 접수, 창고 입고, 완료, 반려, 접수 실패, 취소를 제공한다.

`PENDING_APPROVAL` 행에만 다음 동작을 노출한다.

- `POST /admin/returns/{returnId}/approve`
- `POST /admin/returns/{returnId}/reject` (`reason` 필수)

두 요청은 `ADMIN` 권한과 CSRF를 요구한다. 성공과 이미 처리된 상태 오류는 flash 메시지로 목록에 표시한다.

## 고객 화면

고객이 신청을 완료하면 WMS 접수 완료 메시지 대신 `반품 신청이 접수되어 관리자 승인을 기다리고 있습니다.`를 표시한다.

주문 상세와 반품 상세의 한글 상태는 다음을 추가한다.

- `PENDING_APPROVAL`: `OMS 승인 대기`
- `REJECTED`: `반품 반려`

반려 건에는 관리자가 입력한 반려 사유를 표시한다. 반려된 요청 수량은 새 신청 가능 수량으로 돌아간다.

## 기존 데이터와 호환성

기존 `PENDING_SUBMISSION`, `SUBMISSION_FAILED`, `REQUESTED`, `RECEIVED`, `COMPLETED`, `CANCELLED` 행의 의미와 처리는 바꾸지 않는다. 새 감사 필드는 nullable이므로 기존 행을 백필하지 않는다.

상태는 문자열로 저장되므로 새 enum 값이 기존 행의 값을 바꾸지 않는다. 로컬·테스트 H2는 기존 Hibernate 스키마 관리를 사용하고, 운영 PostgreSQL에는 Flyway `V10` 마이그레이션으로 nullable 감사 열을 추가한다. WMS 데이터와 API 계약은 변경하지 않는다.

## 오류 처리

- 없는 반품 ID: 기존 `EntityNotFoundException` 처리
- 승인 대기가 아닌 상태: 도메인 전이 오류, 관리자 목록에 오류 flash
- 빈 승인자 또는 빈·500자 초과 반려 사유: 입력 오류로 거부
- WMS 일시·인증 장애: 승인 성공은 유지하고 `PENDING_SUBMISSION`에서 재시도
- WMS 영구 거절: 기존 `SUBMISSION_FAILED`와 고객용 실패 안내 사용
- 동시 승인·반려: 비관적 잠금과 상태 가드로 한 요청만 성공

## 검증

- 도메인: 생성 상태, 승인, 반려, 필수 사유, 중복·잘못된 전이 거부
- 서비스: 승인·반려 감사 정보, 반려 후 수량 재신청, 승인 대기 중 중복 수량 차단
- 전송: 고객 신청만으로 WMS 미호출, 관리자 승인 후 호출, 장애 시 스위퍼 재시도
- 관리자 MVC: 목록·필터·버튼, 승인·반려, ADMIN 권한, CSRF, flash
- 고객 MVC: 승인 대기·반려 상태와 반려 사유 표시
- 저장소: 관리자 목록 정렬과 상태 필터, 새 enum 문자열 저장
- 회귀: 기존 WMS 콜백·멱등성·부분 환불·반품 동시성 테스트와 전체 테스트

## 완료 기준

- 고객 신청만으로 WMS RMA가 생성되지 않는다.
- OMS 관리자가 승인한 건만 기존 WMS RMA 흐름에 진입한다.
- OMS 반려 건은 WMS에 전송되지 않고 반려 사유가 고객에게 보인다.
- 승인 대기 중에는 중복 수량 신청이 차단되고 반려 후에는 다시 신청할 수 있다.
- 중복 또는 동시 관리자 처리로 WMS 요청이 두 번 생성되지 않는다.
- 기존 승인 후 WMS 장애 복구와 콜백 동기화가 그대로 동작한다.
