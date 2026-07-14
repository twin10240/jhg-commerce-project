# OMS 문서 최신화 설계

## 목표

별도 `jhg-wms-project`의 2026-07-13 `master` 상태를 기준으로 OMS 저장소의 현재 상태 문서 5개를 최신화한다. 현재 구조와 운영 방식을 앞에 두고, 상세 변경 이력은 `CLAUDE.md`에만 보존한다.

## 기준 사실

- Phase 3 WMS 물리 분리는 완료됐다.
- WMS는 별도 Spring Boot 3.5.5·Java 21 앱과 PostgreSQL을 사용한다.
- OMS는 `InventoryPort`·`InventoryQueryPort`의 REST 어댑터와 발주 어댑터로 WMS를 호출한다.
- WMS 전체 경로는 HTTP Basic 인증을 요구하며 OMS 어댑터도 같은 자격증명을 전송한다.
- WMS에는 Railway 공개 도메인이 있고, OMS 간 통신은 계속 private networking을 사용한다.
- WMS 예약 원장이 상품별 예약 수량을 저장하며 ship/release는 호출자 수량 대신 원장을 재생한다.
- WMS 재고는 `productId`당 한 행만 허용하고 낙관적 락을 사용한다.
- 재고 보충 콜백과 OMS의 주기적 스윕이 백오더 승격을 복구한다.
- 2026-07-14 OMS 전체 테스트는 163건 통과했다. WMS 문서에는 76건 통과가 기록돼 있다.

## 문서별 역할과 수정 범위

### `README.md`

신규 사용자와 개발자의 진입 문서로 유지한다. Phase 3 완료·Phase 4 선택 상태, 실제 OMS 저장소 패키지 구조, 별도 WMS 실행 조건, Basic 인증 환경변수, Railway 공개/비공개 접근 경계를 간결하게 반영한다. 제거된 인프로세스 WMS 도메인·서비스 설명은 삭제한다.

### `CLAUDE.md`

개발 작업용 상세 기준 문서로 유지한다. 기존 단계별 변경 이력은 보존하되 문서 앞부분의 현재 아키텍처·도메인 모델·배포 정보를 최신화한다. WMS 공개 도메인, Basic 인증 배포 순서, 예약 수량 SSOT, `productId` 유니크와 낙관적 락, 최신 열린 위험을 반영한다.

### `risk.md`

열린 위험을 먼저 보여주는 운영 리스크 문서로 바꾼다. 해결된 두 항목은 짧은 이력으로 접고, OMS 콜백의 private-network 의존, 관리자 재고조정 false-timeout, Flyway V2~V4 자동 검증 부족, Basic 자격증명 불일치 시 전면 401 위험을 명확히 적는다.

### `docs/기획서.md`

제품 비전과 로드맵 문서로 유지한다. Phase 3를 완료로 바꾸고 현재 OMS/WMS 책임 분리와 세 통신 채널을 결과 중심으로 설명한다. Phase 4는 선택 사항으로 남긴다.

### `src/main/java/com/jhg/hgpage/oms/README.md`

OMS 개발자용 경계 문서로 유지한다. 모놀리스·향후 물리 분리 표현을 제거하고 실제 REST 어댑터, Basic 인증, 장애 폴백·재시도·스윕, 별도 WMS의 예약 원장 책임을 설명한다.

## 범위 밖

- 애플리케이션 코드·설정·테스트 변경
- 과거 `docs/superpowers/plans`와 `docs/superpowers/specs` 기록 수정
- WMS 저장소 문서 수정
- Phase 4 구현 또는 새로운 운영 기능 추가

## 검증

- 다섯 문서에서 `Phase 3`가 완료 상태로 일치하는지 확인한다.
- 인프로세스 `Inventory`·`PurchaseOrderService`가 OMS에 남아 있다는 현재형 설명을 제거한다.
- `WMS_BASIC_USER`·`WMS_BASIC_PASSWORD`, 공개 도메인, private `WMS_BASE_URL` 설명이 서로 모순되지 않는지 확인한다.
- 열린 리스크와 해결된 리스크가 섞이지 않는지 확인한다.
- Markdown 링크와 참조 파일 경로가 실제로 존재하는지 확인한다.
- 최종 diff가 다섯 대상 문서와 이 설계·구현 계획 문서에만 한정되는지 확인한다.
