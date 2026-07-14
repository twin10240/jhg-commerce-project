# OMS/WMS Open Risk Notes

현재 운영·설계 판단에 필요한 열린 위험만 앞에 둔다. 과거 해결 내역은 문서 끝에 짧게 보존한다.

## 1. 재고보충 콜백이 OMS 공개 도메인에서도 무인증으로 열려 있다

- 위치: OMS `SecurityConfig`, `/api/replenishments`
- 현재 상태: WMS는 private `OMS_BASE_URL`로 호출하지만 OMS 서비스 자체에 공개 도메인이 있다. `/api/replenishments`는 `permitAll`·CSRF 예외이므로 같은 애플리케이션의 public ingress에서도 접근 가능하다. private 주소 사용은 WMS→OMS 전송 경로를 보호할 뿐 공개 라우트를 닫지 않는다.
- 영향: 제3자가 임의 상품 ID로 백오더 재할당을 반복 호출해 WMS 조회·주문 락 경합과 로그 노이즈를 만들 수 있다. 승격은 실제 WMS 재고와 주문 상태를 다시 확인해 임의 재고 생성은 되지 않지만, 운영 엔드포인트가 인증 없이 노출된 상태다.
- 현재 대응: 콜백의 자연 멱등성과 실제 재고 재검증이 데이터 오염을 막는다. 접근 통제 자체는 아직 없다.
- 변경 조건: 별도 조건 없이 인증 보강 대상이다. WMS→OMS 전용 Basic 계정 또는 요청 서명을 추가한다.

## 2. 관리자 재고조정은 성공 후 false-timeout이 날 수 있다

- 위치: OMS `WmsInventoryAdapter.adjust`, WMS `InventoryService.adjust`와 `OmsReplenishmentNotifier`
- 원인: OMS→WMS 조정 요청 안에서 WMS 커밋 후 콜백→OMS 백오더 승격→WMS 예약이 동기 중첩된다. 전체 체인이 OMS의 read timeout을 넘길 수 있다.
- 영향: WMS 조정은 이미 커밋됐는데 관리자에게 실패로 보일 수 있다. `adjust`는 비멱등이므로 같은 `+delta`를 재시도하면 재고가 이중 증가한다.
- 현재 대응: 운영 스모크에서는 재현되지 않았고, 실패 시 재시도하지 않고 WMS 재고를 먼저 확인한다.
- 변경 조건: 실제 false-timeout이 관측되면 WMS 보충 통지를 비동기로 분리하거나 조정 요청에 멱등 키를 도입한다.

## 3. OMS Flyway 자동 검증이 전체 마이그레이션을 포괄하지 않는다

- 위치: `src/test/java/com/jhg/hgpage/FlywayMigrationTest.java`
- 현재 상태: 테스트는 V1 중심이며 V2~V4의 PostgreSQL 적용을 자동 검증하지 않는다.
- 영향: H2 테스트가 통과해도 PostgreSQL 문법이나 순차 마이그레이션 문제가 배포 때 발견될 수 있다.
- 현재 대응: V3/V4는 Railway 배포 로그와 운영 스모크로 검증했다.
- 변경 조건: 마이그레이션 변경이 잦아지면 Testcontainers 기반 PostgreSQL 전체 마이그레이션 테스트를 추가한다.

## 4. Basic 자격증명 불일치는 OMS→WMS 전면 장애를 만든다

- 위치: 양 서비스의 `WMS_BASIC_USER`·`WMS_BASIC_PASSWORD`, OMS WMS 어댑터 3종
- 현재 상태: WMS 전체 경로가 HTTP Basic 인증을 요구하고 OMS가 동일 자격증명을 모든 호출에 전송한다.
- 영향: 한쪽만 값을 바꾸거나 WMS를 먼저 배포하면 가용 조회·예약·출고·해제·재고조정·발주·입고가 모두 401로 실패한다.
- 현재 대응: 두 서비스에 같은 값을 설정하고, 변경 시 OMS를 먼저 배포한 뒤 WMS를 배포한다.
- 변경 조건: 운영 주체나 클라이언트가 늘어나면 서버간 계정과 관리자 계정을 분리한다.

## 의도된 정책 한계

- 백오더는 전부-아니면-할당과 단순 FIFO를 사용한다. 앞선 대량 주문을 충족하지 못하면 뒤의 소량 주문도 기다리는 부분 기아가 생길 수 있다.
- 현재 학습 범위에서는 유지하며, 실제 요구가 생길 때 부분 할당이나 우선순위 정책을 도입한다.

## 해결 이력

- 2026-07-10: `catalog.Product`의 사용되지 않는 `CartItem` 역참조를 제거해 catalog→OMS 결합을 해소했다.
- 2026-07-10: `WmsPurchaseOrderAdapter.create()`가 WMS 4xx를 사용자용 `IllegalArgumentException`으로 변환하도록 수정하고 테스트를 추가했다.
