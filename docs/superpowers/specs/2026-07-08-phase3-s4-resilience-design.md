# Phase 3 — S4 회복탄력성 설계 (2026-07-08)

상위 설계: `2026-06-26-phase3-wms-physical-separation-design.md` §3(장애 처리)의 구현 스펙.
S3까지 완료 — 재고·발주는 WMS 단일 진실 공급원, 채널3(WMS→OMS 보충 콜백)으로 백오더 자동 승격.
S4 = WMS↔OMS 사이의 다운/hang/유실을 다루는 회복탄력성. 스키마 변경 없음(WMS 가드 제외 코드만).

## 확정된 결정 (브레인스토밍 2026-07-08)

| 결정 | 선택 |
|------|------|
| 범위 | 상위 설계 §3 그대로(가~라) + WMS 노티파이어 타임아웃 + WMS shipAll RELEASED 가드(리뷰 Flag 1) |
| 재시도 | 수동 루프 1회, **예약 경로만**. ship/release는 재시도 없이 사용자 안내(수동 재시도) |
| 보상 스윕 주기 | 60초 fixedDelay, yml 프로퍼티(`backorder.sweep-delay`, 기본 60s — 통합 검증 시 5s로 단축 가능) |
| @Async 통지 분리 | 생략(비범위) — 타임아웃 상한으로 충분, 필요 시 도입 |

## 컴포넌트별 설계

### ① 타임아웃 — 코드 0줄, yml 2줄 × 양쪽 앱

Spring Boot 3.4+의 `spring.http.client.connect-timeout` / `read-timeout` 프로퍼티가
자동구성 `RestClient.Builder`에 적용된다. 클라이언트 4개(OMS `WmsInventoryAdapter`·
`WmsInventoryQueryAdapter`·`WmsPurchaseOrderAdapter`, WMS `OmsReplenishmentNotifier`)
전부 주입받은 Builder로 만들므로 각 앱 yml 두 줄로 전부 커버:

```yaml
spring:
  http:
    client:
      connect-timeout: 1s
      read-timeout: 2s
```

- read timeout → `ResourceAccessException` → 예약 경로는 기존 catch로 강등 완성,
  노티파이어는 기존 `Exception` catch로 warn 유실. hang 무기한 블록 소멸.
- **검증 단계 필수**: 프로퍼티가 실제로 안 먹으면 폴백은 `RestClientCustomizer` 빈 1개
  (requestFactory에 `ClientHttpRequestFactorySettings` 적용).
- 테스트 yml(임베디드)은 무관 — 프로퍼티는 실 HTTP 요청에만 영향.

### ② 예약 재시도 — `WmsInventoryAdapter.reserveAll` 수동 1회

`ResourceAccessException` catch 시 즉시 1회 재시도, 재실패면 기존대로 `false`(→BACKORDERED 강등).

- 안전 근거: WMS `InventoryService.reserveAll`이 기존 orderId면 상태 기반 반환
  (RESERVED/SHIPPED→true) — 첫 요청이 실제 예약됐는데 응답만 타임아웃난 경우
  재시도가 true로 수렴(S0 멱등 원장의 회수 지점).
- ship/release/adjust에는 재시도 없음.

### ③ ship/release 실패 UX — `GlobalExceptionHandler` 핸들러 1개

`@ExceptionHandler(ResourceAccessException.class)` 추가, 기존 패턴(`isApiRequest` 분기) 준수:
- `/api/**` → 503 ProblemDetail
- 화면 → flash `"창고 시스템과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요."` + `redirect:/main`

트랜잭션은 예외로 롤백 — 주문 상태와 WMS 호출 결과가 함께 원위치(예외: WMS가 처리했는데 응답만 타임아웃난 반쪽 상태는 ⑤ 참고).
`/main` 무한루프 없음: 메인이 타는 `WmsInventoryQueryAdapter`는 이미 예외를 삼키고 빈 맵 폴백.
덤: 발주 화면(WmsPurchaseOrderAdapter)의 WMS 다운도 생 500 → 같은 flash 안내로 개선.

### ④ 보상 스윕 — 신규 `BackorderSweeper` (OMS `oms/service`)

```java
@Scheduled(fixedDelayString = "${backorder.sweep-delay:60s}",
           initialDelayString = "${backorder.sweep-delay:60s}")  // 기동 직후 발화 방지
// 1. BACKORDERED 주문의 distinct productId 조회 (OrderRepositoryQuery에 쿼리 1개 추가)
// 2. 비어 있으면 스킵(WMS 호출 0)
// 3. backorderAllocator.allocate(productIds)  — 기존 승격 정책(FIFO·전부-아니면-백오더) 그대로 재사용
```

- `@EnableScheduling` 신설(config).
- 회수 대상 유실 3종: OMS 다운 중 콜백 유실 / 노티파이어 타임아웃 유실 / "예약 못 해본 백오더"(WMS 다운 중 접수분).
- 동시성: 스윕과 콜백이 같은 주문을 동시 승격 시도해도 예약 원장 orderId 멱등으로 둘 다 같은 결과 — 안전.
- `BackorderAllocator.allocate(Collection<Long>)`는 public `@Transactional`이라 그대로 호출 가능.

### ⑤ WMS shipAll RELEASED 가드 (리뷰 Flag 1)

`InventoryService.shipAll`에 가드 1줄: `status == RELEASED`면 `IllegalStateException`.

- 결함 시나리오(가드 없을 때): 취소 시 WMS가 release를 처리했으나 응답만 타임아웃 →
  OMS 롤백으로 주문은 ORDER 잔존, WMS 예약은 RELEASED(반쪽 상태). 이때 출고하면
  `Reservation.ship()`이 무가드로 RELEASED→SHIPPED 덮어쓰고 `Inventory.ship()`이
  `reservedQty`를 음수로 만들어 가용수량 부풀림(침묵 오염).
- 가드 후: 출고가 명시적 실패(500 아닌 도메인 예외) → 반쪽 상태는 "취소 재시도로 해소되는
  일시 상태"로 격하(release 재시도는 no-op, 주문만 CANCEL로 수렴).

### ⑥ WMS 노티파이어 — yml 외 코드 변경 없음

①의 타임아웃 프로퍼티가 적용되고 기존 `send()`의 `Exception` catch가 타임아웃을 삼킨다.

## 알려진 한계 (의도된 수용)

1. **관리자 재고조정 경로의 중첩 체인 false-timeout**: OMS→WMS adjust(외곽 read 2s)의 응답은
   WMS의 afterCommit 통지(→OMS 동기 승격→WMS reserve)가 끝난 뒤에 나간다. 승격 대상이 있으면
   내부 체인이 외곽 2s를 넘겨 조정은 커밋됐는데 관리자에겐 에러로 보일 수 있고, adjust는
   비멱등이라 재시도 시 +delta 이중 적용. 빈도 낮음(WMS가 건강하면 내부 체인도 빠름).
   근본 회피는 통지 @Async 분리 — 필요해지면 도입.
2. **WMS 다운 중 스윕 비용**: 백오더 주문당 connect-timeout 1s씩 소모(allocate가 주문당
   reserveAll 1회). 단일 스케줄러 스레드에 다른 잡 없음 + 학습 규모라 무해.
3. 백오더 FIFO 부분 기아는 기존 한계 #22 그대로(S4 무관).

## 테스트 전략

- **어댑터 재시도/강등**: `MockRestServiceServer` — 실패→재시도 성공 true / 2연속 실패 false / 성공 시 재시도 없음(호출 1회).
- **BackorderSweeper**: Mockito 단위 — 백오더 있으면 `allocate` 호출, 없으면 미호출(WMS 호출 0 보장).
- **repository 쿼리**: `@DataJpaTest` — BACKORDERED만 distinct productId 추출.
- **GlobalExceptionHandler**: MvcTest — 화면 flash+redirect, API 503.
- **WMS shipAll 가드**: WMS 단위 테스트 — RELEASED 출고 시 예외 + 재고 불변.
- **통합 검증(수동, 두 앱 기동)**: ① WMS 다운 중 주문 → 2~3s 내 BACKORDERED 접수 ② WMS 재기동 → 스윕 주기 내 자동 승격 ③ WMS 다운 중 취소 → flash 안내 + 주문 상태 불변 ④ 정상 경로 회귀(주문·취소·조정·입고 콜백 승격).

## 비범위 (YAGNI)

- outbox 큐잉(ship/release), @Async 통지 분리, 관리자 화면 WMS 상태 배지, 스윕 분산 락(단일 인스턴스), adjust 멱등화(한계 1의 근본 해결).
