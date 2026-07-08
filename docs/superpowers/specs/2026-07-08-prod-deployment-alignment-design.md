# 운영(Railway) 배포 정합성 설계 (2026-07-08)

Phase 3 완료(S0~S4) 후 첫 운영 반영. 현재 prod에 배포된 OMS는 **S0 시점 모놀리스**(마지막 마이그레이션 V2, 2026-06-30)이고, WMS는 운영 스택이 전무하다. 이 작업 = S1~S4 전체를 운영에 반영하는 배포 + 그에 필요한 설정/마이그레이션 정비.

## 확정된 결정 (브레인스토밍 2026-07-08)

| 결정 | 선택 |
|------|------|
| 도착점 | **실제 Railway 배포까지** (대시보드 조작은 사용자, 안내는 플랜이 제공) |
| WMS prod 스키마 | `ddl-auto: update` (Flyway 없음 — 신생 앱, YAGNI. OMS가 밟은 경로 그대로) |
| prod 재고/발주 데이터 | **이관 없음** — WMS 빈 DB 시드 시작, OMS V3는 그냥 DROP (데모 DB) |
| 서비스 간 통신 | **Railway Private Networking** (`http://<service>.railway.internal:<port>`) — WMS 공개 도메인 없음 |

## 컴포넌트별 설계

### ① base-url 환경변수화 — 양쪽 yml 각 1줄

- OMS `application.yml`(기본 문서): `wms.base-url: ${WMS_BASE_URL:http://localhost:8081}`
- WMS `application.yml`(기본 문서): `oms.base-url: ${OMS_BASE_URL:http://localhost:8080}`

로컬은 기본값으로 지금과 동일, prod는 Railway Variables로 internal 주소 주입.
프로파일별 오버라이드 대신 단일 메커니즘(env 치환)만 사용.

### ② WMS prod 스택 신설

- `build.gradle`: `runtimeOnly 'org.postgresql:postgresql'` 추가 (H2와 공존, prod에서만 사용 — OMS와 동일).
- `application.yml`:
  - `server.port: 8081` → `server.port: ${PORT:8081}` (Railway 주입 PORT 바인딩)
  - prod 프로파일 문서 추가 — OMS와 동일 패턴: `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD` 참조,
    `ddl-auto: update`, `format_sql: false`, H2 콘솔 off.
- Dockerfile 신설: OMS Dockerfile 복제 + `eclipse-temurin:21-jdk`/`21-jre`.
  **함정**: WMS `gradle.properties`의 `org.gradle.java.home=C:/Program Files/...`(Windows 경로)가
  컨테이너 빌드를 즉사시킴 → Dockerfile에서 복사 후 해당 라인 제거(`sed -i '/org.gradle.java.home/d'`).
  `.dockerignore` 신설(OMS 것 복제 — build/·.git/ 제외).
- 시드: 기존 `InitDb`가 멱등(count>0 skip)이며 productId 1~20에 onHand 15~300 — prod 첫 기동 시 자동 시드.
  코드 변경 불필요.

### ③ OMS V3 마이그레이션 — WMS 테이블 DROP

`src/main/resources/db/migration/V3__drop_wms_tables.sql`:

```sql
-- V3: WMS 물리 분리(S1~S2) 반영 — 재고·예약·발주는 WMS DB가 단일 진실 공급원 (2026-07-08)
-- 주의: 이 마이그레이션 이후 S1 이전 OMS 빌드로 롤백 불가(테이블 소멸).
-- 데이터 이관 없음(데모 DB 결정) — WMS prod는 자체 시드로 시작.
DROP TABLE IF EXISTS purchase_order_item;  -- FK 자식 먼저
DROP TABLE IF EXISTS purchase_order;
DROP TABLE IF EXISTS reservation;
DROP TABLE IF EXISTS inventory;
DROP SEQUENCE IF EXISTS purchase_order_item_seq;
DROP SEQUENCE IF EXISTS purchase_order_seq;
DROP SEQUENCE IF EXISTS reservation_seq;
DROP SEQUENCE IF EXISTS inventory_seq;
```

- 새 OMS 코드는 이 테이블들을 전혀 참조하지 않으므로 DROP 시점 위험 없음.
- 기존 `FlywayMigrationTest`(임베디드 H2, V1까지 검증)와의 정합은 플랜에서 확인 —
  V3는 `IF EXISTS`뿐이라 H2에서도 무해하지만, 테스트가 V1만 돌리는 구조면 손대지 않는다.

### ④ Railway 배포 절차 (순서 중요 — WMS 먼저)

1. **WMS 서비스 신설**: GitHub `jhg-wms-project` 연결 → Postgres 플러그인 추가 →
   Variables: `SPRING_PROFILES_ACTIVE=prod`, `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD`(Postgres 참조 변수),
   `OMS_BASE_URL=http://<OMS서비스명>.railway.internal:<OMS 내부 포트>` → 배포 → 로그로 시드 확인.
   공개 도메인 발급하지 않음.
2. **OMS 갱신**: Variables에 `WMS_BASE_URL=http://<WMS서비스명>.railway.internal:<WMS 내부 포트>` 추가 →
   master 배포(Flyway V3 자동 실행) → 스모크 테스트.
3. 운영 관리 작업(재고조정·발주·입고)은 OMS 관리자 화면이 어댑터로 프록시 — WMS UI 접근 불필요.
   **부수 효과**: `/api/replenishments`·WMS API가 인터넷에 미노출 → 알려진 이슈 #15의 콜백 보호가 네트워크 격리로 해소.

### ⑤ 스모크 테스트 (배포 후, 사용자와 함께)

1. 메인 그리드에 재고 표시(=OMS→WMS 채널1 통신 성공)
2. 주문 → 즉시 ORDER (채널2 reserve)
3. 관리자 재고조정 +delta → 백오더 승격 라운드트립 (채널3 콜백, internal 양방향 검증)
4. 발주 생성→입고 → 재고 증가 + 승격
5. 주문 취소 → 재고 복구
- 검증 포인트: Railway private networking은 IPv6 기반 — Spring Boot/Tomcat 기본 바인딩(듀얼스택)으로
  보통 동작하나, 통신 실패 시 `server.address: "::"` 명시가 1차 조치.

## 알려진 한계 (의도된 수용)

1. **V3 이후 구버전 롤백 불가**: inventory·reservation·purchase_order* 소멸. 데모 DB라 수용.
2. **prod product id 정합 가정**: WMS 시드는 productId 1~20 고정. prod OMS product가 시드 기본(1~20)과
   다르면 해당 상품은 재고 0 표시 — OMS 관리자 재고조정으로 맞추면 됨(데이터 이관 없음 결정의 귀결).
3. WMS 관리 UI는 prod에서 접근 불가(공개 도메인 없음) — 필요해지면 그때 임시 공개 또는 인증 추가.

## 테스트 전략

- 코드 변경이 yml/SQL/Dockerfile 위주라 단위 테스트 대상이 거의 없음 — 기존 전체 스위트 green 유지가 게이트.
- base-url env 치환: 로컬에서 `--wms.base-url`/`--WMS_BASE_URL` 오버라이드 기동으로 실증(둘 다 Spring
  relaxed binding으로 동작해야 함).
- Dockerfile: 로컬 `docker build`로 양쪽 이미지 빌드 성공 확인(데몬 있으면. 없으면 Railway 빌드가 검증).
- 최종 검증 = ⑤ 스모크 테스트.

## 비범위 (YAGNI)

- WMS Flyway, 데이터 이관/백업, WMS 공개 도메인·인증, CI/CD 파이프라인, actuator 헬스체크,
  커스텀 도메인/HTTPS 설정(Railway 기본), 오토스케일/리소스 튜닝.
