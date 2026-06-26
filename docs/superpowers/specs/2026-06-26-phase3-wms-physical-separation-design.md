# Phase 3 — WMS 물리 분리 설계

작성일: 2026-06-26
상태: 설계 확정(구현 전)

## 목표

OMS 모놀리스에서 WMS(재고·발주·입고·출고)를 **완전히 별도의 Spring Boot 프로젝트**로 떼어내고
둘을 **REST로 통신**시킨다. 재고의 단일 진실 공급원은 WMS, OMS는 판매 가용 재고만 조회한다.
멘탈모델: **OMS = 지점 / WMS = 본사**(본사가 재고 마스터+입고, 지점은 재고0 + 주문/백오더 명단).

학습 포인트: **멱등 API, 보상(reconciliation) 처리, 분산 데이터 소유권.**

## 확정 결정사항

1. **별도 프로젝트** — WMS는 독립 Spring Boot 앱. **WMS = Java 21**, OMS = Java 17 유지. 두 프로젝트는 컴파일된 코드(jar)를 공유하지 않는다. 계약은 REST(JSON)뿐.
2. **DB 진짜 분리** — WMS가 `inventory`/`purchase_order`/`reservation` 테이블 소유. OMS DB엔 재고 테이블 없음.
3. **WMS는 `productId` + 수량 + 발주(매입)만 안다** — 상품명·판매가 모름. 재고 관리화면(조정/발주/입고)은 WMS 앱 자체에 두고 `productId + 수량`으로 표시. 매출/수익 분석은 OMS 영역(주문+판매가 모두 OMS 보유)이라 영향 없음.
4. **WMS 다운 시 백오더 강등(B)** — 예약 경로에 한해 WMS 무응답 시 거부 대신 `BACKORDERED` 접수. 목표 아키텍처는 B, 구현은 슬라이스로.
5. **멱등 = 도메인 자연키 `orderId`(A)** — 예약/출고/해제 요청에 orderId를 실어 WMS가 처리 여부를 판별. 재요청은 no-op.

## 1. 목표 아키텍처

```
┌─────────────────────────────┐         REST          ┌─────────────────────────────┐
│  OMS  (기존, Java 17)        │  ◄──── 채널3 콜백 ────  │  WMS  (신규, Java 21)        │
│  주문·장바구니·고객·catalog   │  ───── 채널1 조회 ───►  │  재고·발주·입고·출고          │
│                             │  ───── 채널2 변경 ───►  │                             │
│  contract/ 포트 = 그대로 유지 │                        │  Inventory / PurchaseOrder   │
│  포트 구현 = REST 클라이언트   │                        │  Reservation(멱등 원장)       │
│  OMS DB: 재고 테이블 없음     │                        │  WMS DB: 재고 마스터          │
└─────────────────────────────┘                        └─────────────────────────────┘
```

- **`contract/` 인터페이스는 OMS에 그대로 남는다.** 바뀌는 건 구현뿐: 인메모리 `InventoryService` → **REST 클라이언트 어댑터**. 호출부(`OrderAllocationService`, `OrderService`, 메인 그리드 등)는 변경 없음.
- **OMS의 `wms/` 패키지는 통째로 새 WMS 프로젝트로 이사**하고 OMS에선 삭제. OMS엔 포트 + REST 어댑터만 남는다.
- **공유 코드 0.** 두 프로젝트는 jar를 공유하지 않는다(공통 `contract` jar 만들지 않음 — 재결합·버전 lockstep 방지). 자바 17/21 자유 공존.

### 선결작업 (분리 전, 모놀리스 내부에서 미리 — 0번과 동일한 expand-in-monolith 패턴)

WMS가 아직 `catalog`를 import하는 마지막 두 지점을 끊는다:

1. **`PurchaseOrderItem → Product` 객체그래프 절단** → `productId: Long`(0번의 Inventory 절단을 발주에 동일 적용). `PurchaseOrderService.create`는 `productRepository.findById` 대신 productId 직접 사용, `receive()`의 `item.getProduct().getId()` → `item.getProductId()`.
2. **재고 관리화면(`InventoryService.findInventoryRows`)의 catalog 조인 제거** → `productId + onHandQty`만(결정 A). 화면 템플릿도 상품명 열 제거.

→ 결과: `wms/` 패키지가 `catalog`를 전혀 import하지 않음(의존 단방향 완성). 추출 준비 완료.

## 2. 통신 채널 (로드맵 4개 → 실제 3개)

| # | 채널 | 방향 | 엔드포인트(안) | 멱등 |
|---|------|------|--------------|------|
| **1** | 재고 조회 | OMS→WMS | `GET /api/inventory/availability?productIds=1,2,3` → `{1:5, 2:0}` | 읽기, 자연 멱등 |
| **2** | 재고 변경 | OMS→WMS | `POST /api/inventory/reservations` `{orderId, lines:[{productId,qty}]}` (예약)<br>`POST /api/inventory/shipments` `{orderId}` (출고)<br>`DELETE /api/inventory/reservations/{orderId}` (해제) | **orderId 키** |
| **3** | 재고 보충 콜백 | WMS→OMS | `POST /api/replenishments` `{productIds}` → OMS가 백오더 승격 | productIds 통지 |

- 채널1 = `InventoryQueryPort.availableByProductIds`의 REST 버전.
- 채널2 = `InventoryPort.reserveAll/shipAll/releaseAll`의 REST 버전. **포트 시그니처에 `orderId` 추가**가 유일한 변경.
- 채널3 = 로드맵의 "입고 통지" + "출고 콜백"을 합친 것. "이 상품 가용분이 늘었다"는 역방향 통지 하나. 입고(receive)·재고조정(adjust) 둘 다 발화. 기존 `StockReplenishedHandler`가 그대로 이 채널의 REST 버전이 됨(WMS가 호출, OMS가 수신→`BackorderAllocator`).

### 멱등 원장 (결정 A 구현)

WMS에 `Reservation` 도입: `{orderId(unique), status: RESERVED→SHIPPED/RELEASED, lines}`.

- `reserve(orderId, lines)` → 원장에 orderId 있으면 같은 결과 반환(중복 예약 안 함). 없으면 가용성 검사 후 예약 + 원장 기록.
- `ship(orderId)` / `release(orderId)` → 원장 보고 상태 전이, 재요청은 no-op.
- 덤: WMS가 주문별 예약 내역을 보유 → **출고/해제 요청은 `orderId`만 전송**하면 됨(lines 재전송 불필요).

## 3. 장애 처리 — WMS 다운 시 백오더 강등 (결정 B)

비전에 직결되는 **예약 경로만** B로, 나머지는 단순 재시도.

### 가. 예약 경로 (`POST /orders/checkout` → 채널2 reserve) — 강등 적용
- WMS 응답 OK → 평소대로 ORDER / BACKORDERED.
- **WMS 무응답(타임아웃·연결거부) → 거부 대신 `BACKORDERED` 접수.** "예약 못 해본 백오더".
- **별도 플래그/상태 불필요**: OMS는 모든 BACKORDERED를 보상 잡이 재시도해도 orderId 멱등이라 안전. "예약 못 해본 백오더"와 "재고 부족 백오더"가 같은 BACKORDERED 상태로 수렴.

### 나. 보상 잡 (reconciliation sweep)
- `@Scheduled` 주기 스윕이 BACKORDERED 주문들의 productId로 `reserveAll` 재시도(= 기존 `BackorderAllocator.allocate` 재사용).
- WMS가 살아나면 가용분 있는 주문은 자동 예약→ORDER 승격. orderId 멱등으로 중복 0.
- "재고 보충 시 승격"과 **동일 함수, 트리거만 둘**(콜백 / 스케줄).

### 다. 출고/해제 경로 (cancel / completeDelivery → 채널2 ship/release) — 단순 재시도
- WMS 다운 시 그 동작만 실패(재시도 안내). 풀 outbox는 과함.
- `// ponytail: ship/release 실패 시 수동 재시도. 큐잉 필요해지면 outbox 도입.`

### 라. RestClient 공통
- 짧은 타임아웃(예약 ~2s) + 1~2회 재시도. 멱등이라 재시도 안전.

## 4. 슬라이스 순서 (각 슬라이스 = 독립 빌드 green, 슬라이스별 plan 별도 작성)

| 슬라이스 | 내용 | 리스크 |
|---------|------|--------|
| **S0 선결** (모놀리스 내부) | ① `PurchaseOrderItem→Product` 절단(productId) ② 재고화면 catalog 조인 제거 ③ 포트에 `orderId` 추가 + `Reservation` 원장 도입, reserve/ship/release를 원장 기반 멱등으로. → `wms/` catalog import 0, 포트 orderId 멱등. | 낮음(인프로세스, 기존 테스트로 회귀검증) |
| **S1 읽기 추출** | 새 WMS 프로젝트(Java 21) 기동·자체 DB·`Inventory` 이사·시드. `GET /availability` 노출. OMS `InventoryQueryPort`→REST 어댑터. 메인 그리드가 HTTP로 재고 읽음. | 낮음(읽기 전용) |
| **S2 쓰기 추출** | reserve/ship/release를 WMS REST(채널2)로. `PurchaseOrder`+재고 관리화면 WMS로 이사. OMS `InventoryPort`→REST 어댑터. WMS가 재고 쓰기 전권. | 중간 |
| **S3 역방향 콜백** | 채널3(WMS→OMS replenish). OMS 콜백 엔드포인트→`BackorderAllocator`. WMS 입고/조정이 호출. 경계 넘는 인프로세스 핸들러 제거. | 중간 |
| **S4 회복탄력성(B)** | WMS 다운 시 예약 강등 + 보상 스윕 잡 + RestClient 타임아웃/재시도. 멱등·보상 학습 알맹이. | 핵심 |

- S1~S2는 동작·DB데이터·화면·URL 불변(통신 경로만 인프로세스→HTTP로 전환). S3는 콜백 경로만, S4는 신규 회복탄력성.
- 구현 직전 슬라이스마다 `writing-plans`로 상세 plan을 뽑는다.

## 영향 받는 기존 코드 (참고)

- **OMS에 남음**: `contract/`(포트 3종, orderId 추가), `OrderAllocationService`/`OrderService`/`BackorderAllocator`(호출부 불변), 신규 REST 어댑터(포트 구현), 신규 콜백 컨트롤러(채널3 수신), 보상 스윕 잡(S4).
- **WMS로 이사**: `wms/domain`(`Inventory`/`PurchaseOrder`/`PurchaseOrderItem`/enums + 신규 `Reservation`), `wms/repository`, `wms/service`(`InventoryService`(실 구현)/`InventoryAdjustmentService`/`PurchaseOrderService`), `wms/web`(재고 관리화면 + 신규 REST 컨트롤러 채널1·2), WMS 자체 시드.
- **OMS에서 삭제**: `wms/` 패키지 전체.

## 테스트 전략

- **S0**: 기존 테스트 조정(`PurchaseOrder` 픽스처 Product→productId, 재고화면 테스트, 포트 orderId 시그니처·멱등 단언). TDD RED→GREEN.
- **WMS 프로젝트**: 자체 스위트 — 도메인 단위(멱등 원장 상태전이), `@DataJpaTest`(리포지토리), `@WebMvcTest`(REST 컨트롤러 채널1·2).
- **OMS 어댑터**: `@RestClientTest`/MockWebServer로 REST 어댑터 검증(직렬화·타임아웃·재시도·강등 분기).
- **end-to-end**: 두 앱 동시 기동 수동 검증 절차 문서화(선택).

## 비범위 (YAGNI)

- 상품마스터(Product) WMS 동기화 채널 — WMS는 productId만 알면 됨(결정 A).
- 순이익/매출 리포팅 레이어 — 추후 분석 단계.
- 출고/해제 outbox 큐잉 — 단순 재시도로 시작, 필요 시 도입.
- 범용 Idempotency-Key 헤더 인프라 — orderId 자연키로 충분.
- Phase 4(REST→이벤트/메시지)는 별도 단계.
