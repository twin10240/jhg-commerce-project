# OMS-WMS 포트폴리오 1차 수동 검증 시나리오 (OMS V1)

작성일: 2026-07-26

범위: 로컬 OMS(:8080)와 WMS(:8081)의 실제 HTTP 통신, 화면, 권한, 장애 복구를 검증한다.
이 파일이 두 저장소의 **통합 수동 검증 기준본**이다.

> 현재 검증 대상은 문서 마지막의 **OMS V2 RMA** 섹션이다. 아래 OMS V1 시나리오와
> 2026-08-04 완료 기록은 기존 주문·재고 회귀 기준으로 보존한다.

## 0. 실행 기준

- H2 `9092` -> WMS `8081` -> OMS `8080` 순서로 기동한다.
- OMS 사용자: `twin10240@naver.com / 1111`
- OMS 관리자: `admin@admin.com / 1111`
- WMS 운영자: `operator / operator`
- WMS 관리자: `manager / manager`
- 시나리오별 상품을 분리해 상태 오염을 방지한다.

| 용도 | 권장 상품 |
|---|---|
| 정상 주문·취소 | 상품 1 |
| 백오더·보충·입고 | 상품 2 |
| FIFO 검증 | 상품 3 |
| 발주 취소 | 상품 4 |
| 단건·일괄 출고 | 상품 5 |
| 수동 재고 조정 | 상품 6 |
| 장애 복구 | 상품 7 |
| 대시보드 집계 | 상품 8~10 |

각 시나리오 시작 전에 대상 상품의 `보유·예약·가용 수량`을 기록한다.

## 1. WMS 로그인·로그아웃

### 절차

1. 로그아웃 상태에서 `/admin/inventory`에 접근한다.
2. 잘못된 비밀번호로 로그인한다.
3. `operator/operator`로 로그인한다.
4. 로그아웃 후 관리자 화면에 다시 접근한다.

### 기대 결과

- 미인증 접근은 `/login`으로 리다이렉트된다.
- 잘못된 로그인은 오류 메시지를 표시한다.
- 정상 로그인 후 관리자 화면에 접근할 수 있다.
- 로그아웃 메시지가 표시되고 다시 로그인이 필요하다.

## 2. WMS API 인증

```bash
curl -i http://localhost:8081/api/inventory/rows
curl -i -u wms:wms http://localhost:8081/api/inventory/rows
```

### 기대 결과

- 미인증 요청은 `401`이며 `/login`으로 `302` 리다이렉트되지 않는다.
- Basic 인증 요청은 `200`이다.

## 3. 정상 주문과 예약

### 절차

1. OMS 사용자로 상품 1을 가용수량 이내로 주문한다.
2. 주문 상세와 배송관리를 확인한다.
3. WMS 예약과 재고를 확인한다.

### 기대 결과

- 주문상태는 `재고 확보`다. 배송상태 `READY`는 고객 화면에서 `출고 준비`, 관리자 배송관리에서 `출고 대기`로 표시된다.
- WMS 예약상태는 `RESERVED`다.
- 보유수량은 그대로이고 예약수량은 증가하며 가용수량은 감소한다.

## 4. 정상 주문 취소

### 절차

1. 출고 전 상품 1 주문을 취소한다.
2. OMS와 WMS 상태를 다시 확인한다.

### 기대 결과

- OMS 주문상태는 `주문 취소`다.
- WMS 예약상태는 `RELEASED`다.
- 보유수량은 그대로이고 예약수량은 감소하며 가용수량은 복구된다.

## 5. 백오더와 재고현황 검산

### 절차

1. 상품 2의 가용수량보다 많은 수량을 주문한다.
2. 사용자 주문 상세, 배송관리, 재고현황을 확인한다.

### 기대 결과

- 주문상태는 `입고 대기`이며 WMS 예약은 생성되지 않는다.
- 배송관리에 주문 상품과 `입고 필요`가 표시된다.
- `백오더 총수량 = 입고 필요 수량 + 할당 대기 수량`이 성립한다.
- 화면의 세 수량을 실제 상품별 수요와 가용수량으로 검산한다.

## 6. 보충 요청·승인·반려

### 절차

1. OMS에서 상품 2 보충 요청을 제출한다.
2. WMS에서 동일 요청을 확인한다.
3. `manager`가 요청을 승인한다.
4. 별도 상품으로 요청 하나를 추가해 반려한다.

### 기대 결과

- 승인 요청은 `APPROVED`가 되고 연결 발주가 생성된다.
- 발주는 `ORDERED`이며 발주량과 잔여량이 일치한다.
- 반려 요청은 `REJECTED`가 되고 메모가 표시되며 발주는 생성되지 않는다.

## 7. 부분입고와 전량입고

### 절차

1. 상품 2 발주수량 중 일부만 입고한다.
2. 아직 주문 확보에 부족한지 확인한다.
3. 남은 수량을 모두 입고한다.

### 기대 결과

- 부분입고 후 발주는 `PARTIALLY_RECEIVED`, 요청은 `APPROVED`다.
- 보유수량이 입고량만큼 증가하고 `RECEIVE` 원장이 생성된다.
- 주문 전체를 확보하기 부족하면 OMS는 `입고 대기`를 유지한다.
- 전량입고 후 발주는 `RECEIVED`, 요청은 `FULFILLED`가 된다.
- 콜백 또는 보상 스윕으로 OMS 주문이 `재고 확보`로 승격된다.

## 8. FIFO 백오더

### 절차

1. 상품 3으로 백오더 주문 두 건을 순서대로 생성한다.
2. 첫 주문만 충족할 수 있는 수량을 입고한다.
3. 추가 수량을 입고한다.

### 기대 결과

- 먼저 접수된 주문만 우선 `재고 확보` 상태가 된다.
- 두 번째 주문은 `입고 대기`를 유지한다.
- 추가 입고 후 두 번째 주문도 승격된다.

## 9. 부분입고 발주 취소

### 절차

1. 상품 4 보충 요청을 승인해 10개를 발주한다.
2. 5개만 부분입고한다.
3. 재고수량과 원장 행 수를 기록한다.
4. `manager`가 발주를 취소한다.

### 기대 결과

- 발주와 연결 요청이 모두 `CANCELLED`가 된다.
- 이미 입고된 5개는 재고에 그대로 유지된다.
- 취소로 인한 재고 역산이나 신규 원장 행이 없다.
- 추가 입고 버튼이 비활성화되거나 입고 요청이 거부된다.

## 10. 수동 재고 조정과 원장

### 절차

1. 상품 6을 사유와 함께 `+5` 조정한다.
2. 예약수량을 침범하지 않는 범위에서 `-2` 조정한다.
3. 재고 트랜잭션 이력을 확인한다.
4. 재고가 0 미만 또는 예약수량 미만이 되도록 감소를 시도한다.

### 기대 결과

- 성공한 조정마다 `ADJUST` 원장이 생성된다.
- 증감량, 조정 전후 수량, 입력한 사유가 표시된다.
- 허용 범위를 넘는 감소는 거부되고 재고와 원장은 변하지 않는다.

## 11. 재고 트랜잭션 이력

### 절차

1. 전체·기초·입고·출고·조정 필터를 차례로 선택한다.
2. 앞선 시나리오에서 발생한 행을 확인한다.

### 기대 결과

- 유형이 `기초/입고/출고/조정`으로 표시된다.
- 상품 ID와 상품명이 표시된다.
- 입고에는 `발주 #N`, 출고에는 `주문 #N` 참조가 표시된다.
- 수동 조정에는 입력한 사유가 표시된다.
- `OPENING/RECEIVE/SHIP`의 사유가 비어 있는 것은 정상이다.
- 최신 200건까지만 노출된다.

## 12. 단건·일괄 출고와 배송 완료

### 절차

1. 상품 5로 출고 가능한 주문을 3건 생성한다.
2. 한 건은 `POST /admin/orders/ship` 단건 출고한다.
3. 나머지 두 건은 `POST /admin/orders/ships` 선택 일괄 출고한다.
4. 출고된 한 건을 `POST /admin/orders/deliver`로 배송 완료 처리한다.

### 기대 결과

- 출고 직후 OMS 배송상태는 `SHIPPED`(출고 완료)이고 배송 완료 처리 뒤 `DELIVERED`가 된다.
- WMS 예약상태는 `SHIPPED`가 된다.
- 보유수량과 예약수량이 주문수량만큼 함께 감소한다.
- `SHIP` 원장과 `주문 #N` 참조가 생성된다.
- 일괄 결과는 `성공 2건 / 실패 0건`이다.
- 취소·백오더·`SHIPPED`·`DELIVERED` 주문에는 출고 선택 체크박스가 없다.

## 13. WMS 권한

### 절차

1. `operator`로 조회, 재고조정, 입고를 수행한다.
2. `operator`로 보충요청 승인·반려, 발주 생성·취소를 시도한다.
3. `manager`로 동일 관리 작업을 수행한다.

### 기대 결과

- `operator`는 조회, 재고조정, 입고를 수행할 수 있다.
- `operator`는 승인·반려, 발주 생성·취소를 수행할 수 없다.
- `manager`는 모든 관리 기능을 수행할 수 있다.
- 권한 없는 버튼은 숨겨지고 직접 POST 요청도 `403`으로 차단된다.

## 14. 대시보드 집계

### 절차

1. `REQUESTED` 요청 1건을 유지한다.
2. `PARTIALLY_RECEIVED` 발주 1건을 유지한다.
3. 상품 하나의 가용수량을 0으로 조정한다.
4. 대시보드와 각 목록의 실제 건수를 비교한다.

### 기대 결과

- 검토 대기 요청, 부분입고 발주, 가용 0 SKU 수가 실제 목록과 일치한다.
- 발주와 예약의 상태별 집계도 실제 건수와 일치한다.

## 15. 장애와 복구

### WMS 중단 중 주문

1. WMS를 중단한 상태에서 상품 7을 주문한다.
2. WMS를 다시 기동하고 최대 60초 대기한다.

기대 결과:

- 주문은 실패하지 않고 `입고 대기`로 접수된다.
- WMS 복구 후 재고가 충분하면 보상 스윕으로 `재고 확보` 상태가 된다.

### WMS 중단 중 취소

1. 정상 상태에서 재고가 확보된 주문을 만든다.
2. WMS를 중단하고 주문을 취소한다.
3. WMS를 다시 기동한 뒤 취소를 재시도한다.

기대 결과:

- 첫 취소 시 `/main`으로 이동하고 통신 실패 메시지가 표시된다.
- 주문상태와 WMS 예약은 그대로 유지된다.
- WMS 복구 후 취소 재시도는 정상 처리된다.

### OMS 중단 중 입고

1. 백오더 주문을 만든 뒤 OMS를 중단한다.
2. WMS에서 필요한 재고를 입고한다.
3. OMS를 재기동하고 최대 60초 대기한다.

기대 결과:

- OMS가 중단돼도 WMS 입고는 성공한다.
- OMS 재기동 후 보상 스윕으로 주문이 승격된다.
- 장애 과정에서 500 에러나 재고 중복 반영이 발생하지 않는다.

## 16. 최종 화면·자동 검증

- 고객 화면을 375px와 1280px에서 확인한다.
- 상품 탐색 -> 장바구니 -> 주문 -> 내 주문 -> 취소 흐름을 확인한다.
- 상태, 오류, 빈 화면의 한글 표현과 레이아웃 겹침을 확인한다.
- OMS와 WMS에서 각각 `./gradlew test`를 실행한다.
- 검증 결과에 주문번호, 발주번호, 전후 수량과 화면 캡처를 기록한다.

## 재배포 시 확인

다음 항목은 현재 1차 로컬 검증에서 제외하고 Railway 재배포 시 확인한다.

- 배포 환경의 OMS -> WMS 재고 조회·예약·출고
- 배포 환경의 WMS -> OMS 보충 콜백
- PostgreSQL 기반 데이터의 서비스 재기동 후 보존

WMS Docker Compose는 WMS 수평 확장 데모이므로 전체 OMS-WMS 배포 검증의 대체 수단으로 간주하지 않는다.

## 검증 기록

검증 완료: 2026-08-04 (사용자 수행·완료 확인)

| # | 시나리오 | 결과 | 근거(주문·발주 번호, 수량, 캡처) | 비고 |
|---|---|---|---|---|
| 1 | WMS 로그인·로그아웃 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 2 | WMS API 인증 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 3 | 정상 주문과 예약 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 4 | 정상 주문 취소 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 5 | 백오더와 재고현황 검산 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 6 | 보충 요청·승인·반려 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 7 | 부분입고와 전량입고 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 8 | FIFO 백오더 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 9 | 부분입고 발주 취소 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 10 | 수동 재고 조정과 원장 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 11 | 재고 트랜잭션 이력 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 12 | 단건·일괄 출고 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 13 | WMS 권한 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 14 | 대시보드 집계 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 15 | 장애와 복구 | ✅ 통과 | 사용자 수행·완료 확인 | |
| 16 | 최종 화면·자동 검증 | ✅ 통과 | 사용자 수행·완료 확인 | |

## OMS V2 RMA — 현재 수동 검증 대상 (2026-08-12)

상태: **완료(V2-1~V2-9, 2026-08-15)**. 자동 계약 테스트와 별개로, 깨끗한 검증용 데이터에서 OMS(:8080)와
호환 WMS(:8081)를 함께 실행하고 아래 증거를 기록한다. 서비스 Basic 자격증명은 양쪽의
`WMS_BASIC_USER/WMS_BASIC_PASSWORD`와 `OMS_CALLBACK_USER/OMS_CALLBACK_PASSWORD`가 일치해야 한다.
복구 검증 시간을 줄일 때만 OMS의 `returns.sweep-delay=5s`를 사용하고 실제 값을 증거에 남긴다.

공통 준비:

1. 고객 주문을 WMS 출고 후 OMS 배송 완료까지 처리해 `DELIVERED`로 만든다.
2. 주문 ID, 주문상품 ID, 상품 ID, 주문 수량과 WMS 재고·원장 전후 값을 기록한다.
3. 고객 주문 상세의 반품 신청과 `/returns/{returnId}`, WMS `/admin/returns/{rmaId}`를 함께 확인한다.
4. 각 시나리오는 별도 주문이나 품목을 사용하고 `returnId`, `requestKey`, `rmaId`를 기록한다.

### V2-1. 단일 품목 전량 승인 `RESTOCKED` (single-line full approval RESTOCKED)

1. 배송 완료 주문의 한 품목 전량을 OMS에서 신청한다.
2. WMS `/admin/returns/{rmaId}`에서 입고 후 요청 수량 전부를 `RESTOCKED`로 검수 완료한다.
3. OMS 반품 상세와 WMS 재고·`RETURN` 원장을 확인한다.

기대 결과: OMS는 `COMPLETED`, 승인 수량은 요청 수량, 처분은 `RESTOCKED`다. WMS 재고는 승인
수량만큼 증가하고 `RETURN` 원장이 한 번만 생긴다.

### V2-2. 복수 품목 부분 승인과 한 품목 `REJECTED` (multi-line partial approval with one REJECTED line)

1. 두 품목 이상을 한 반품으로 신청한다.
2. 한 품목은 요청 수량보다 적은 양을 `RESTOCKED` 또는 `DISPOSED`로 승인하고, 다른 품목은 승인 수량
   `0`, 처분 `REJECTED`로 검수 완료한다.
3. OMS 반품 상세에서 주문상품별 결과를 확인한다.

기대 결과: 전체 반품은 `COMPLETED`이고 각 `orderItemId`의 요청·승인 수량과 처분이 WMS 결과와
일치한다. 부분 승인 잔여 수량은 거절된 것으로 처리되어 다시 신청할 수 있다.

### V2-3. `DISPOSED` 승인과 OMS 재고 비소유 (DISPOSED approval without OMS inventory ownership)

1. 반품 품목 일부 또는 전부를 `DISPOSED`로 승인해 검수 완료한다.
2. WMS 재고와 원장을 전후 비교하고 OMS 반품 상세를 확인한다.

기대 결과: OMS에는 승인 수량과 `DISPOSED` 결과만 표시된다. WMS 재고는 증가하지 않고 OMS에는
재고 수량이나 반품 재고 원장이 생성되지 않는다.

### V2-4. `REQUESTED` 취소 반영 (REQUESTED cancellation reflected in OMS)

1. WMS에 접수되어 `REQUESTED`인 RMA를 입고 전에 `/admin/returns/{rmaId}`에서 취소한다.
2. 콜백 뒤 OMS 반품 상세를 확인한다.

기대 결과: WMS와 OMS가 모두 `CANCELLED`이며 승인 수량·처분과 재고 변화가 없다. 동일 콜백 재전송도
상태를 바꾸지 않는다.

### V2-5. 같은 `requestKey` 재시도 멱등성 (same requestKey retry returns the same rmaId)

1. OMS가 WMS에 보낸 `POST /api/returns` 요청의 `requestKey`와 전체 JSON을 기록한다.
2. 같은 Basic 인증, 같은 `requestKey`, 같은 내용으로 WMS `POST /api/returns`를 다시 호출한다.
3. WMS RMA 목록과 응답을 비교한다.

기대 결과: 재시도 응답은 최초와 같은 `rmaId`이고 RMA가 추가 생성되지 않는다.

### V2-6. 고객 신청 중 WMS 중단 후 스윕 복구 (WMS unavailable during customer submission then sweeper recovery)

1. WMS를 중단하고 배송 완료 주문의 반품을 OMS에서 신청한다.
2. OMS 반품 상세가 `PENDING_SUBMISSION`(WMS 전송 중)이고 요청이 보존됐는지 확인한다.
3. WMS를 재기동하고 `returns.sweep-delay` 한 주기 이상 기다린다.

기대 결과: 고객 신청 자체는 사라지지 않는다. 스윕이 같은 `requestKey`로 접수해 `rmaId`를 결합하고
OMS가 `REQUESTED`로 수렴한다.

### V2-7. WMS 완료 콜백 유실 후 단건 조회 복구 (OMS unavailable during WMS completion then GET recovery)

1. RMA를 WMS에서 입고해 `RECEIVED`로 만든 뒤 OMS를 중단한다.
2. WMS에서 검수 완료해 OMS 콜백이 실패하는 것을 확인한다.
3. OMS를 재기동하고 `returns.sweep-delay` 한 주기 이상 기다린다.

기대 결과: WMS 완료 트랜잭션은 성공한다. OMS 스윕이 `GET /api/returns/{rmaId}`로 결과를 회수해
`COMPLETED`와 모든 품목 결과로 수렴하며 WMS 재고·원장은 중복 반영되지 않는다.

### V2-8. 변조 콜백 거부 (tampered callback item/product/quantity rejected with 409)

1. 진행 중인 반품의 정상 콜백 JSON을 준비한다.
2. 올바른 callback Basic 인증을 사용하되 `orderItemId`, `productId`, 요청 수량을 각각 원 요청과 다르게
   바꿔 `POST /api/return-status-events`로 보낸다.
3. 각 응답과 OMS 반품 상태를 확인한다.

기대 결과: 세 변조 요청은 각각 `409 Conflict`이고 OMS 상태·품목 결과는 변경되지 않는다.

### V2-9. 잘못된 콜백 인증 거부 (wrong callback Basic credentials rejected with 401)

1. 유효한 콜백 JSON을 잘못된 Basic 자격증명으로 `POST /api/return-status-events`에 보낸다.
2. 응답 헤더와 OMS 반품 상태를 확인한다.

기대 결과: 로그인 화면 리다이렉트 없이 `401 Unauthorized`이고 `Location` 헤더와 상태 변경이 없다.

### OMS V2 검증 기록

| 시나리오 | 결과 | 근거(`returnId`/`requestKey`/`rmaId`, 상태·수량·HTTP·캡처) |
|---|---|---|
| V2-1 단일 품목 전량 `RESTOCKED` | ✅ 통과 | `returnId=2`, `requestKey=5ff7d0fa-0749-407c-9f63-1d240dde2a4d`, `rmaId=152`(주문 12/`orderItemId=12`); 요청 2 전량 `RESTOCKED` 승인 → OMS `반품 완료`·승인 2·재입고, WMS 상품5 `70→72`, `RMA #152` RETURN 원장 1건 |
| V2-2 복수 품목 부분 승인 + `REJECTED` | ✅ 통과 | `returnId=3`, `rmaId=153`(주문 13/`orderItemId=13,14`); 상품6 요청 2→승인 1 `RESTOCKED`, 상품7 요청 1→승인 0 `REJECTED` → OMS가 품목별로 `1 재입고`/`0 거절` 표시, WMS 상품6 `91→92`·상품7 `104→104`, RETURN 원장 1건(승인분만) |
| V2-3 `DISPOSED`, OMS 재고 비소유 | ✅ 통과 | `returnId=4`, `rmaId=154`(주문 14/`orderItemId=15`); 요청 2 전량 `DISPOSED` → OMS `반품 완료`·승인 2·폐기, WMS 상품8 `118→118` 불변, RETURN 원장 **0건**(OMS에 재고·원장 없음) |
| V2-4 `REQUESTED` 취소 | ✅ 통과 | `returnId=5`, `rmaId=155`(주문 15/`orderItemId=16`); 입고 전 WMS에서 취소 → WMS `CANCELLED`·OMS `반품 취소`, 상품9 `134→134`, 원장 0건. `disposition=null` 계약은 유지하고 OMS 품목 처리 결과는 `취소`로 표시하도록 수정·회귀 테스트 완료 |
| V2-5 같은 `requestKey` 멱등 재시도 | ✅ 통과 | `returnId=2`, `requestKey=4633ff73-ecec-45e4-88fd-b8f1bb7c28c2`, `rmaId=2`; 동일 JSON 재전송도 `rmaId=2`, WMS RMA 총 2건 유지 |
| V2-6 WMS 중단 후 접수 스윕 | ✅ 통과 | `returnId=3`, `requestKey=31c4b2d7-6d75-4247-b23d-257beb58aca9`; 중단 중 `PENDING_SUBMISSION/rmaId=null`, 복구 후 `REQUESTED/rmaId=52` |
| V2-7 OMS 중단 후 단건 조회 스윕 | ✅ 통과 | `returnId=4`, `requestKey=e97035b6-59e8-46d5-97c2-7d7a3b6f29d3`, `rmaId=53`; 콜백 실패 후 5초 스윕으로 `COMPLETED/RESTOCKED`, 상품 13 재고 `194→195`, `RMA#53` RETURN 원장 1건 |
| V2-8 변조 콜백 `409` | ✅ 통과 | `returnId=2/rmaId=2`; `orderItemId`, `productId`, `requestedQuantity` 개별 변조 모두 `409`, `REQUESTED` 및 품목 결과 미변경 |
| V2-9 잘못된 callback Basic `401` | ✅ 통과 | 유효 JSON + `bad:bad` 요청이 `401`; `Location` 없음, `REQUESTED` 및 품목 결과 미변경 |


#### 계약 스모크 (2026-08-15, 실행 로그)

| 확인 | 결과 |
|---|---|
| 없는 RMA 조회 | `GET /api/returns/999999` → WMS `404` (`RMA가 없습니다. rmaId=999999`). 직전까지 `400`이었고 WMS `e3673c0`에서 분리 |
| OMS의 404 분류 | `WmsReturnAdapter:88`이 `404 → RemoteReturnNotFound`로 매핑(자동 테스트 `WmsReturnAdapterTest`·`ReturnReconciliationSweeperTest`에 존재). **런타임 재현은 미수행** — 스윕이 없는 `rmaId`를 조회하는 상황을 UI로 만들 수 없어 코드·테스트로만 확인 |
| 잘못된 callback Basic | `bad:bad`로 `POST /api/return-status-events` → `401`, `Location` 헤더 없음. 대조군(`wms:wms`)은 `200` |
| 동일 `requestKey` 재전송 | `rmaId=152`의 원 요청과 같은 내용 재전송 → `200`, 같은 `rmaId=152`, RMA 총 건수 불변 |

**환경 주의**: OMS DB만 초기화하면 주문 ID가 1부터 다시 시작하는데 WMS에는 이전 `orderId 1~11` 예약이
남아 있어, 접수가 옛 예약의 품목과 대조돼 `400`으로 거부된다(실제로 겪음). 두 시스템이 `orderId`를 공유
키로 쓰므로 검증용 데이터는 **양쪽을 함께 초기화**해야 한다.

## OMS V2 모의 결제·환불 수동 검증 (2026-08-15)

이 12개 시나리오는 스위트 시작 시 OMS와 WMS의 개발 DB를 함께 **한 번만** 초기화한 뒤 순서대로 실행한다.
WMS 저장소와 OMS 저장소의 각 터미널에서 다음 명령을 실행하고, 스위트가 끝날 때까지 WMS를 유지한다.

```bash
# WMS 저장소: jdbc:h2:tcp://localhost/~/jhg-wms
./gradlew bootRun --args='--spring.profiles.active=local'

# OMS 저장소: jdbc:h2:tcp://localhost/~/hgpage
./gradlew bootRun --args='--spring.profiles.active=local'
```

이후에는 어느 앱에도 `local` 프로파일을 다시 사용하지 않는다. 실행 전 각 주문 ID, 결제 ID, 승인/환불
`requestKey`, WMS 예약 또는 RMA ID를 기록한다.

### 실행 결과 (2026-08-21)

브라우저 UI 캡처 런타임은 사용할 수 없어 화면 캡처는 남기지 못했다. 아래 결과는 실제 HTTP/CSRF 요청과
OMS·WMS DB 직접 조회로 확인했다. 시나리오 11은 공개 런타임 제어만으로 `reserveAll`을 결정적으로
중단할 수 없어 수동 통과로 기록하지 않고 자동 동시성 테스트 근거를 별도로 남긴다.

| 시나리오 | 결과 | 실행 근거 |
|---|---|---|
| S1 정상 결제 후 `ORDER` | 통과 | `orderId=8`, `paymentId=8`: `PAID`, 결제액 `11,000`; `attemptId=8` `SUCCEEDED`; WMS `reservationId=1` `RESERVED` |
| S2 `BACKORDERED` 입고 후 승격 | 통과 | `orderId=9`, `paymentId=9`: 최초 `BACKORDERED`/`PAID 552,000`, 예약 없음; WMS `productId=3` 1개 입고 후 `ORDER`, `allocationAttemptCount=1`; `reservationId=2` `RESERVED`, 수량 46 |
| S3 거절 후 고객 재결제 | 통과 | `orderId=52`, `paymentId=52`: `DECLINED` 후 `PAYMENT_FAILED`, 결제액 0; `attemptId=52` `FAILED`/`MOCK_DECLINED`, WMS 예약 없음; 성공 재결제 후 `ORDER`/`PAID 13,000`, `attemptId=102` `SUCCEEDED`, `reservationId=3` `RESERVED` |
| S4 유료 `BACKORDERED` 취소 | 통과 | `orderId=102`, `paymentId=102`: `BACKORDERED`/`PAID 1,064,000`에서 `CANCEL`/`REFUNDED`; `refundRequestId=52` `SUCCEEDED`, 금액 `1,064,000`; 반복 취소 후 환불 행 1건·합계 `1,064,000`, WMS 예약 없음 |
| S5 재고 확보 주문 취소 | 통과 | `orderId=103`, `paymentId=103`: 취소 후 `CANCEL`/`REFUNDED 15,000`, 환불 행 1건; WMS `reservationId=4` `RELEASED` |
| S6 전량 반품 승인 | 통과 | `orderId=104`, `orderItemId=104`, `customerReturnId=2`, `rmaId=1`, WMS `itemId=1`: 요청 2·승인 2 `RESTOCKED`; 결제 `REFUNDED`, 결제액·환불액 `32,000`, 환불 `SUCCEEDED` |
| S7 부분 승인 환불 | 통과 | `orderId=105`, `orderItemId=105`, `customerReturnId=3`, `rmaId=2`, WMS `itemId=2`: 요청 2·승인 1 `DISPOSED`; 결제 `PARTIALLY_REFUNDED`, 결제액 `34,000`, 환불액 `17,000`, 환불 `SUCCEEDED` |
| S8 전량 거절 | 통과 | `orderId=106`, `orderItemId=106`, `customerReturnId=4`, `rmaId=3`, WMS `itemId=3`: 요청 1·승인 0 `REJECTED`; 결제 `PAID 18,000`, 환불액 0, 환불 요청 없음 |
| S9 일시 실패 후 자동 복구 | 통과 | `orderId=152`, `paymentId=152`, `refundRequestId=102`: 취소 환불 첫 실행은 `RETRYING`, `attemptCount=1`, `MOCK_RETRYABLE_FAILURE`, pending `19,000`; 재시도 시각 경과 후 기본 설정으로 재기동해 `SUCCEEDED`, `attemptCount=2`, `REFUNDED 19,000`, pending 0 |
| S10 영구 실패 후 관리자 재시도 | 통과 | `orderId=202`, `paymentId=202`, `refundRequestId=152`: 첫 실행 `MANUAL_REVIEW`, `attemptCount=1`, pending `20,000`, `nextAttemptAt=null`; 기본 설정 재기동 후 관리자 CSRF POST 재시도로 `SUCCEEDED`, `attemptCount=2`, `REFUNDED 20,000`, pending 0 |
| S11 결제 성공·할당 중 취소 경합 | 수동 미실행 | 공개 런타임 제어로 `reserveAll` 중단을 재현하지 못함. `PaymentCancellationConcurrencyTest.할당처리중_취소는_예약성공을_해제한뒤_한번의_전액환불로_수렴한다`와 `PaymentAdminConcurrencyTest.미확정_취소할당_관리자와_처리기가_경합해도_같은주문을_한번만_예약한다`에서 자동 검증 |
| S12 OMS·WMS 재기동 복구 | 통과 | OMS 재기동이 S9 미완료 환불을 복구. WMS 재기동 후 예약 9건·RMA 3건 유지; 예약 1~3 `RESERVED`, 4·8·9 `RELEASED`, 5~7 `SHIPPED`; RMA 1~3 `COMPLETED` |

모의 게이트웨이는 공개 제어 API 없이 OMS 프로세스 시작 시 환경변수로 결과를 선택한다. 허용값은 승인
`SUCCESS`·`DECLINED`·`RETRYABLE_FAILURE`·`PERMANENT_FAILURE`·`UNKNOWN`, 환불도 같은 값이며 기본은
둘 다 `SUCCESS`다. 이 설정은 로컬 검증 전용 `payment-faults` 프로파일에서만 환경변수를 읽는다. 결과를
바꿀 때는 OMS만 `Ctrl-C`로 종료하고 아래 명령 중 하나로 재시작한다. 모든 명령은 같은 OMS datasource를
명시하며 `ddl-auto=update`인 기본 설정을 유지하므로, 실행 중인 WMS와 양쪽 검증 이력을 보존한다.

```bash
MOCK_PAYMENT_APPROVAL_OUTCOME=DECLINED ./gradlew bootRun --args='--spring.profiles.active=payment-faults --spring.datasource.url=jdbc:h2:tcp://localhost/~/hgpage'
MOCK_PAYMENT_REFUND_OUTCOME=RETRYABLE_FAILURE ./gradlew bootRun --args='--spring.profiles.active=payment-faults --spring.datasource.url=jdbc:h2:tcp://localhost/~/hgpage'
MOCK_PAYMENT_REFUND_OUTCOME=PERMANENT_FAILURE ./gradlew bootRun --args='--spring.profiles.active=payment-faults --spring.datasource.url=jdbc:h2:tcp://localhost/~/hgpage'
```

명시적인 성공 복귀는 fault 프로파일과 환경변수를 모두 제거한 기본 성공 빈으로 재시작한다.

```bash
env -u MOCK_PAYMENT_APPROVAL_OUTCOME -u MOCK_PAYMENT_REFUND_OUTCOME ./gradlew bootRun --args='--spring.datasource.url=jdbc:h2:tcp://localhost/~/hgpage'
```

### 1. 정상 결제 후 `ORDER`

- 준비: 가용 재고가 있는 상품을 선택한다.
- 동작: 고객이 결제하고 주문한 뒤 결제·할당 스윕 한 주기 안에 주문 상세와 WMS 예약을 조회한다.
- 기대 OMS: 결제 `PAID`, 주문 `ORDER`, 환불 요청 없음.
- 기대 WMS: 주문 ID 하나의 `RESERVED` 예약.
- 결제/환불: 주문 총액이 한 번 승인되고 환불은 `0`원이다.
- 멱등성: 같은 승인 작업 재실행은 추가 승인이나 예약을 만들지 않는다.

### 2. 정상 결제 후 `BACKORDERED`, 입고 뒤 승격

- 준비: 가용 수량보다 큰 주문 수량을 준비한다.
- 동작: 결제 후 할당을 실행하고, 필요한 재고를 WMS에 입고한 뒤 백오더 할당을 기다린다.
- 기대 OMS: 처음에는 `BACKORDERED`, 입고 뒤 `ORDER`; 결제는 계속 `PAID`다.
- 기대 WMS: 부족 시 예약 없음, 입고 뒤 주문 ID 하나의 `RESERVED` 예약.
- 결제/환불: 최초 총액만 승인되고 환불은 `0`원이다.
- 멱등성: 입고 콜백과 스윕을 반복해도 예약과 승인은 각각 한 번이다.

### 3. 결제 거절, WMS 미호출, 고객 재결제

- 준비: 공동 초기화 상태에서 OMS만 종료하고 위의 승인 `DECLINED` 명령으로 OMS를 시작한다.
- 동작: 주문을 결제하고 WMS 호출 기록을 확인한다. OMS를 종료한 뒤 성공 복귀 명령으로 재시작하고 고객이
  다시 결제한다.
- 기대 OMS: 처음 `PAYMENT_FAILED`, 재결제 뒤 `ALLOCATION_PENDING`을 거쳐 `ORDER` 또는 `BACKORDERED`.
- 기대 WMS: 첫 시도에는 예약 없음, 재결제 성공 뒤에만 한 번 할당된다.
- 결제/환불: 거절 금액은 승인 `0`원, 성공 재결제만 주문 총액을 승인하며 환불은 `0`원이다.
- 멱등성: 최초 거절 승인 키를 재실행해도 WMS 호출이 생기지 않고, 재결제는 새 승인 키 하나만 만든다.

### 4. `BACKORDERED` 취소 후 전액 환불

- 준비: 유료 `BACKORDERED` 주문을 만든다.
- 동작: 고객이 출고 전 취소하고 환불 스윕을 실행한다.
- 기대 OMS: 주문 `CANCEL`, 결제 `REFUNDED`, 환불 요청 `SUCCEEDED`.
- 기대 WMS: 예약과 해제가 모두 없다.
- 결제/환불: 승인 총액과 환불 총액이 같고 pending 환불은 `0`원이다.
- 멱등성: 취소 요청과 환불 작업을 반복해도 환불 요청과 게이트웨이 환불은 한 건이다.

### 5. 재고 확보 주문 취소 후 예약 해제·전액 환불

- 준비: 결제 완료 `ORDER`와 WMS `RESERVED` 예약을 만든다.
- 동작: 고객이 취소하고 취소 해제 작업과 환불 작업을 모두 완료한다.
- 기대 OMS: 주문 `CANCEL`, 결제 `REFUNDED`.
- 기대 WMS: 해당 주문 예약이 정확히 `RELEASED`다.
- 결제/환불: 승인 총액 전액을 한 번 환불한다.
- 멱등성: 해제/환불 재시도는 중복 WMS 해제나 중복 환불을 만들지 않는다.

### 6. 전량 반품 승인 후 전액 환불

- 준비: 결제 완료 주문을 출고와 배송 완료까지 처리한다.
- 동작: 고객이 전 수량 반품을 신청하고 WMS가 전량 `RESTOCKED` 또는 `DISPOSED`로 검수 완료한다.
- 기대 OMS: 반품 `COMPLETED`, 결제 `REFUNDED`, 환불 요청 `SUCCEEDED`.
- 기대 WMS: RMA 검수 결과가 승인 수량과 일치하고 `RESTOCKED`만 재고·RETURN 원장을 증가시킨다.
- 결제/환불: 주문 총액 전액을 한 번 환불한다.
- 멱등성: 완료 콜백/환불 작업 재전송은 RMA 결과와 환불을 중복 반영하지 않는다.

### 7. 부분 승인 후 승인 수량만 환불

- 준비: 배송 완료된 복수 수량 주문을 준비한다.
- 동작: WMS가 요청 수량 일부만 `RESTOCKED` 또는 `DISPOSED`로 승인한다.
- 기대 OMS: 반품 `COMPLETED`, 결제 `PARTIALLY_REFUNDED`.
- 기대 WMS: 승인 수량만 `RESTOCKED` 재고·RETURN 원장을 반영하고 거절 수량은 반영하지 않는다.
- 결제/환불: `주문 당시 단가 x 승인 수량`만 한 번 환불하고 나머지는 미환불이다.
- 멱등성: 같은 완료 콜백은 같은 환불 요청을 반환하며 누적 환불액을 늘리지 않는다.

### 8. 검수 전량 거절 후 환불 없음

- 준비: 배송 완료 주문의 반품을 신청한다.
- 동작: WMS가 모든 품목을 승인 `0`, `REJECTED`로 완료한다.
- 기대 OMS: 반품 `COMPLETED`, 결제는 `PAID`, 환불 요청 없음.
- 기대 WMS: 재고와 RETURN 원장이 변하지 않는다.
- 결제/환불: 승인 총액은 유지되고 환불은 `0`원이다.
- 멱등성: 거절 콜백을 반복해도 환불 요청이 생성되지 않는다.

### 9. 환불 일시 실패 후 자동 복구

- 준비: OMS만 종료하고 위의 환불 `RETRYABLE_FAILURE` 명령으로 시작한 뒤 취소 또는 반품으로 pending
  환불 요청을 만든다.
- 동작: 첫 환불 스윕 뒤 `RETRYING`을 확인한다. OMS를 종료하고 성공 복귀 명령으로 재시작한 뒤 다음 재시도
  시각과 환불 스윕 한 주기 이상 기다린다.
- 기대 OMS: 요청은 `RETRYING`을 거쳐 `SUCCEEDED`; 결제 pending 금액은 최종 `0`원이다.
- 기대 WMS: 취소라면 예약 해제는 한 번이며, 반품이라면 이미 확정된 RMA 재고 결과가 바뀌지 않는다.
- 결제/환불: 같은 환불 키와 같은 금액으로 재시도해 최종 한 번만 환불 완료한다.
- 멱등성: 반복 스윕은 성공 뒤 추가 환불을 호출하지 않는다.

### 10. 환불 영구 실패 후 관리자 재시도

- 준비: OMS만 종료하고 위의 환불 `PERMANENT_FAILURE` 명령으로 시작한 뒤 pending 환불 요청을 만든다.
- 동작: 환불 스윕 뒤 관리자 결제 화면에서 `MANUAL_REVIEW`를 확인한다. OMS를 종료하고 성공 복귀 명령으로
  재시작한 뒤 관리자 화면에서 해당 환불을 재시도한다.
- 기대 OMS: 처음 `MANUAL_REVIEW`, 재시도 뒤 `SUCCEEDED`; 결제 금액 불변식은 계속 유지된다.
- 기대 WMS: 예약 해제 또는 반품 RMA 상태를 재실행하지 않는다.
- 결제/환불: pending 환불액은 검토 중에도 보존되고, 성공 뒤 해당 금액만 누적 환불액으로 이동한다.
- 멱등성: 관리자와 스윕이 경합해도 한 작업자만 같은 환불 키로 게이트웨이를 호출한다.

### 11. 결제 성공·할당 중 고객 취소 경합

- 준비: 유료 주문의 WMS `reserveAll` 호출을 처리 중에 멈춘다.
- 동작: 할당 처리 중 고객 취소를 요청한 뒤 예약 결과를 성공과 부족 각각으로 완료한다.
- 기대 OMS: 중간은 `CANCEL_REQUESTED`, 최종은 `CANCEL`과 전액 환불 요청이다.
- 기대 WMS: 예약 성공 결과만 즉시 한 번 해제하고, 부족 결과는 예약을 만들지 않는다.
- 결제/환불: 늦은 결제 승인 또는 할당 결과가 있어도 주문 총액 전액만 한 번 환불한다.
- 멱등성: 같은 order ID 결과와 취소 재시도는 중복 예약·해제·환불을 만들지 않는다.

### 12. OMS·WMS 재기동 후 미완료 작업 복구

- 준비: `PAYMENT_PENDING`, `ALLOCATION_PENDING`, `REFUND RETRYING` 또는 처리 임계시간을 지난 작업을 각각 만든다.
- 동작: OMS와 WMS를 함께 중단하고 다시 기동한 뒤 각 작업 스윕 한 주기 이상 기다린다.
- 기대 OMS: 미완료 결제·할당·환불이 DB에서 발견되어 성공, 재시도, 또는 수동 확인 상태로 수렴한다.
- 기대 WMS: 동일 주문 ID의 예약/해제는 기존 결과를 재사용하며 중복 재고 원장이 없다.
- 결제/환불: 승인·환불은 원래 멱등키와 금액을 유지한다.
- 멱등성: 재기동을 반복해도 결제 승인, 환불, 예약, 해제, RETURN 원장 건수는 증가하지 않는다.

### 별도 결제 페이지·환불 거래번호 후속 검증 (2026-08-24)

OMS와 WMS를 `local` 프로파일로 함께 초기화하고 Chrome UI와 독립 HTTP/CSRF 세션으로 확인했다.

| 확인 | 결과 | 실행 근거 |
|---|---|---|
| 결제 페이지 이탈·재개 | 통과 | 상품1 주문 `orderId=8` 생성 후 `/orders/8/payment`에서 이탈. 내 주문에 `결제 대기`와 `결제 계속`이 표시됐고 같은 결제 페이지로 복귀 |
| 정상 승인 | 통과 | 주문 8의 `10,000원 결제하기` 실행 후 `결제가 승인되었습니다`, 결제액 `10,000원`, 재고 확인 상태 확인 |
| 결제 실패 후 재시도 | 통과 | 시드 주문 3의 `다시 결제하기` 실행 후 `결제 완료 10,000원`, `재고 확보`로 전이 |
| 전액 환불과 거래번호 | 통과 | 주문 8 취소 후 결제 `REFUNDED`, pending `0원`, 누적 환불 `10,000원`. 관리자 화면에 `requestKey=5cc2e915-fbf3-449e-b258-23584f653486`, `gatewayTransactionId=MOCK-REFUND-5cc2e915-fbf3-449e-b258-23584f653486` 표시 |
| 부분 환불 표시 | 통과 | 시드 주문 4가 결제 `20,000원`, 누적 환불 `10,000원`, `PARTIALLY_REFUNDED`; 환불 행에 `MOCK-REFUND-DEMO` 표시 |
| 수동 검토 재처리 | 통과 | 시드 주문 6의 `refundRequestId=3`, `MANUAL_REVIEW`, pending `20,000원`을 관리자 재시도. `SUCCEEDED`, 누적 환불 `20,000원`, `gatewayTransactionId=MOCK-REFUND-385fabd6-ef67-4edd-ba96-7a10f4584de9`로 수렴 |
| 동시 중복 승인 | 자동 검증 | UI에서 처리 중 상태를 결정적으로 정지할 수 없어 `PaymentApprovalProcessorTest.중복_결제승인은_진행중인_결제시도를_재사용한다`로 확인 |
| 환불 일시 실패 자동 재시도 | 자동 검증 | `RefundServiceTest.성공은_pending을_refunded로_이동하고_일시실패는_같은키로_재시도한다`와 `RefundSweeperTest`로 확인 |

강제 전체 재실행: `./gradlew test --rerun-tasks` → **511개, 실패 0, 오류 0, 제외 0**.
