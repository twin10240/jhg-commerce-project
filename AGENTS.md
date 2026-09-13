# OMS 작업 가이드

## 목적

이 저장소는 Spring Boot 3.5.5 기반 학습용 OMS(`com.jhg`, Java 17)다. OMS는 회원·상품 카탈로그·장바구니·주문·결제·백오더·반품 고객 흐름을 소유하고, 재고·예약·출고·발주는 `jhg-wms-project`, 알림은 `jhg-realtime-service`가 소유한다.

핵심 정책은 재고가 없어도 주문을 접수하는 백오더다. 재고 정본은 WMS 한 곳이며 서비스 간 코드는 `contract/` 포트와 문서화된 REST 계약으로만 연결한다.

## 구조

- `catalog/`: 상품 카탈로그와 WMS 가용수량 조회 조합
- `oms/`: 주문·결제·회원·장바구니·배송·반품
- `wms/`: WMS REST adapter와 관리자 보충 요청 화면
- `contract/`: `InventoryPort`, `InventoryQueryPort`, `ReturnPort`, `StockReplenishedHandler`
- `web/`, `config/`, `exception/`: 공용 화면·보안·예외 처리

주문은 `OrderAllocationService`가 WMS에 전부-아니면-예약을 요청한다. 성공은 `ORDER`, 부족 또는 최종 통신 실패는 `BACKORDERED`다. 출고 시 WMS가 실물을 차감하고, 입고·재고 증가·예약 해제 후 백오더를 FIFO로 재할당한다.

## 개발 환경과 명령

macOS 기준이며 Windows 명령을 사용하지 않는다.

```bash
./gradlew test
./gradlew test --tests 'com.jhg.hgpage.service.OrderServiceTest'
./gradlew bootRun
./gradlew bootRun --args='--spring.profiles.active=local'
```

기본 OMS는 `:8080`, 개발 WMS는 `:8081`, realtime은 `:3000`이다. 로컬 OMS는 H2 TCP(`jdbc:h2:tcp://localhost/~/hgpage`)를 사용하고 테스트는 임베디드 H2를 사용한다. 기본 `ddl-auto`는 `update`, `local` 프로파일은 초기화용 `create`다. 운영 DB는 PostgreSQL/Flyway를 사용한다.

기동 전에 H2 TCP 서버가 필요하다. `local` 프로파일은 데이터 리셋이므로 기존 데이터가 필요하면 사용하지 않는다.

## 보안과 공개 기동

- 기본 설정에서 H2 콘솔은 비활성화되어야 한다. `/h2-console/**`를 공개하거나 CSRF 예외로 두지 않는다.
- 공개 OMS는 `oms.jhgsoft.com`, WMS는 `wms.jhgsoft.com`, realtime은 `rt.jhgsoft.com`이다. Cloudflare Tunnel 경로와 공개 기동 스크립트(`~/study/scripts/run-oms-public.sh`)를 변경할 때 비밀값을 출력하거나 커밋하지 않는다.
- Basic/JWT 자격증명과 DB 비밀번호는 환경변수·로컬 비밀 파일에서만 읽는다. 소스·로그·문서에 값을 남기지 않는다.
- 공개 변경 전에는 `curl -s -o /dev/null -w '%{http_code}\n' https://oms.jhgsoft.com/h2-console/`가 200이 아닌지 확인한다.

## 계약 변경

WMS 호출은 adapter에서만 만든다. 반품 접수 본문은 반품 자신의 `requestKey`, 주문의 `orderId`와 `orderRequestKey`, 사유, 품목을 구분한다. 주문에 `requestKey`가 없는 레거시 데이터는 `orderRequestKey`를 생략해 기존 WMS 경로를 사용한다.

계약 변경 시 OMS 단위 테스트와 WMS 계약 문서를 함께 확인하고, 새 UUID를 임의로 발급하지 않는다. 콜백·재시도·중복 요청은 멱등성을 깨지 않는지 검증한다.

## 테스트 원칙

행동을 검증하는 테스트를 먼저 작성하고 실패를 확인한 뒤 최소 구현한다. 전체 테스트는 `./gradlew test`; system-tests 통합 검증은 `/Users/jo/study/jhg-system-tests`에서 `npm test`로 실행한다.

실패 시 `build/test-results`와 system-tests `artifacts/<UUID>/`의 `scenario.log`, `revisions.txt`, 서비스 로그를 확인한다. 테스트가 만든 고객·주문·알림을 다음 시나리오에 남기지 않는다.

## 작업 규칙

- 변경 범위를 요청된 저장소와 계약으로 제한한다.
- 기존 포트·adapter·RepositoryQuery 패턴을 재사용하고 불필요한 추상화를 추가하지 않는다.
- OMS·realtime·system-tests는 Codex 담당이다. WMS 저장소는 Claude Code 담당이므로 직접 커밋하지 않는다.
- 공개하면 안 되는 교차 저장소 요청은 저장소 밖 `~/study/docs/codex-requests/`에 둔다.
