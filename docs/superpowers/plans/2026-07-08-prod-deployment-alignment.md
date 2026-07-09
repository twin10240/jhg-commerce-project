# 운영(Railway) 배포 정합성 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** S1~S4(WMS 물리 분리 + 회복탄력성)를 운영에 반영 — base-url 환경변수화, WMS prod 스택(Postgres·Dockerfile), OMS V3(WMS 테이블 DROP), Railway 실배포 + 스모크.

**Architecture:** 스펙 `docs/superpowers/specs/2026-07-08-prod-deployment-alignment-design.md` 참조. 통신은 Railway Private Networking(`*.railway.internal`), WMS는 공개 도메인 없음. WMS prod 스키마는 `ddl-auto: update`(Flyway 없음), 데이터 이관 없음(빈 DB 시드). 배포 순서: **WMS 먼저 → OMS(V3 자동 실행)**.

**Tech Stack:** Spring Boot 3.5.5 양쪽, OMS Java 17 / WMS Java 21, PostgreSQL(Railway), Flyway(OMS만), Docker 멀티스테이지.

## Global Constraints

- 빌드 JVM은 머신 로컬 `~/.gradle/gradle.properties`의 `org.gradle.java.home`(JDK 21)이 지정 — JAVA_HOME 수동 설정 불필요(2026-07-09부터. 이전의 레포 내 `org.gradle.java.home`은 Windows 경로가 컨테이너 빌드를 죽여 제거 — OMS Railway 배포 실패의 원인이었음).
- OMS 작업은 브랜치 `feature/prod-alignment`(base: master), WMS 작업은 master 직접 커밋.
- base-url 프로퍼티 형태(정확히): OMS `wms.base-url: ${WMS_BASE_URL:http://localhost:8081}`, WMS `oms.base-url: ${OMS_BASE_URL:http://localhost:8080}` — 기본값으로 로컬 동작 불변.
- V3 파일명: `V3__drop_wms_tables.sql`. DROP 순서: purchase_order_item → purchase_order → reservation → inventory (FK 자식 먼저).
- Railway 내부 포트 고정: OMS `PORT=8080`, WMS `PORT=8081` (internal URL 예측 가능하게 Variables로 명시).
- 로컬 검증 시 8080은 무관 프로세스 점유 — OMS는 8090으로 기동.
- 코드 변경은 yml/SQL/Dockerfile 위주 — 단위 테스트 신설 없음, 기존 전체 스위트 green이 게이트.

---

### Task 1: [OMS] wms.base-url 환경변수화 + V3 마이그레이션

**Files:**
- Modify: `C:\study\jhg-commerce-project\src\main\resources\application.yml` (33-34행 `wms:` 블록)
- Create: `C:\study\jhg-commerce-project\src\main\resources\db\migration\V3__drop_wms_tables.sql`

**Interfaces:**
- Produces: `WMS_BASE_URL` env로 오버라이드 가능한 `wms.base-url`(어댑터 3종이 소비 — 코드 불변), V3 마이그레이션(prod 배포 시 Flyway가 자동 실행)

- [x] **Step 1: 브랜치 생성**

```
cd C:\study\jhg-commerce-project
git checkout -b feature/prod-alignment master
```

- [x] **Step 2: yml 수정** — `application.yml` 첫 문서의 `wms:` 블록을 교체:

```yaml
wms:
  # 로컬 기본 8081, 운영은 WMS_BASE_URL 주입(Railway private networking: http://<wms>.railway.internal:8081)
  base-url: ${WMS_BASE_URL:http://localhost:8081}
```

- [x] **Step 3: V3 SQL 신설** — `src/main/resources/db/migration/V3__drop_wms_tables.sql`:

```sql
-- V3: WMS 물리 분리(S1~S2) 반영 — 재고·예약·발주는 WMS DB가 단일 진실 공급원 (2026-07-08)
-- 주의: 이 마이그레이션 이후 S1 이전 OMS 빌드로 롤백 불가(테이블 소멸).
-- 데이터 이관 없음(데모 DB 결정) — WMS prod는 자체 시드(productId 1~20)로 시작.
DROP TABLE IF EXISTS purchase_order_item;  -- FK 자식 먼저
DROP TABLE IF EXISTS purchase_order;
DROP TABLE IF EXISTS reservation;
DROP TABLE IF EXISTS inventory;
DROP SEQUENCE IF EXISTS purchase_order_item_seq;
DROP SEQUENCE IF EXISTS purchase_order_seq;
DROP SEQUENCE IF EXISTS reservation_seq;
DROP SEQUENCE IF EXISTS inventory_seq;
```

> `FlywayMigrationTest`는 `target(V1)`로 제한돼 있어 V3의 영향 없음(수정 불필요 — 주석의 "V2 이후 마이그레이션은 Railway 배포로 최종 확인" 정책 그대로).

- [x] **Step 4: 전체 빌드**

Run: `cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat build`
Expected: BUILD SUCCESSFUL (yml 기본값 치환으로 기존 테스트 전부 불변 green)

- [x] **Step 5: 커밋**

```
git add src/main/resources/application.yml src/main/resources/db/migration/V3__drop_wms_tables.sql
git commit -m "feat(oms): wms.base-url 환경변수화 + V3 WMS 테이블 DROP 마이그레이션

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: [WMS] oms.base-url 환경변수화 + PORT + prod 프로파일 + postgres 의존성

**Files:**
- Modify: `C:\study\jhg-wms-project\src\main\resources\application.yml`
- Modify: `C:\study\jhg-wms-project\build.gradle` (dependencies 블록, 26행 h2 옆)

**Interfaces:**
- Produces: `OMS_BASE_URL` env로 오버라이드 가능한 `oms.base-url`(OmsReplenishmentNotifier가 소비 — 코드 불변), `PORT` env 바인딩, `prod` 프로파일(PG* 변수 소비)

- [x] **Step 1: build.gradle 의존성 추가** — dependencies 블록의 `runtimeOnly 'com.h2database:h2'` 아래에:

```groovy
	runtimeOnly 'org.postgresql:postgresql'   // prod(Railway Postgres) 전용 — H2와 공존
```

- [x] **Step 2: application.yml 수정** — 세 곳:

① `server:` 블록 교체:

```yaml
# OMS가 8080이므로 WMS는 8081로 동시 기동 가능. Railway는 PORT 주입(Variables로 8081 고정 권장).
server:
  port: ${PORT:8081}
```

② `oms:` 블록 교체:

```yaml
# 재고 보충 콜백(채널3) 대상 — OMS. 운영은 OMS_BASE_URL 주입(Railway private networking).
oms:
  base-url: ${OMS_BASE_URL:http://localhost:8080}
```

③ 파일 끝(local 프로파일 문서 뒤)에 prod 프로파일 문서 추가:

```yaml
---
# 운영 프로파일(Railway): PostgreSQL. SPRING_PROFILES_ACTIVE=prod + Postgres 플러그인 변수(PG*) 주입.
# 스키마는 ddl-auto update(신생 앱 — Flyway 미도입 결정), 빈 DB면 InitDb가 재고 1~20 시드.
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
    username: ${PGUSER}
    password: ${PGPASSWORD}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: false
        default_batch_fetch_size: 100
  h2:
    console:
      enabled: false
```

- [x] **Step 3: 전체 빌드**

Run: `cd C:\study\jhg-wms-project; .\gradlew.bat build`
Expected: BUILD SUCCESSFUL (테스트는 임베디드 H2 — prod 프로파일 비활성이라 불변)

- [x] **Step 4: 커밋 + push**

```
git add build.gradle src/main/resources/application.yml
git commit -m "feat(wms): prod 프로파일(Postgres)·PORT 바인딩·oms.base-url 환경변수화

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push
```

---

### Task 3: [WMS] Dockerfile + .dockerignore

**Files:**
- Create: `C:\study\jhg-wms-project\Dockerfile`
- Create: `C:\study\jhg-wms-project\.dockerignore`

**Interfaces:**
- Consumes: Task 2의 prod 프로파일(런타임에 `SPRING_PROFILES_ACTIVE=prod`로 활성화)
- Produces: Railway가 Nixpacks 대신 사용할 컨테이너 빌드(존재만으로 자동 선택)

- [x] **Step 1: Dockerfile 작성** — OMS 패턴 복제 + JDK/JRE 21. ~~핵심 함정 처리: `org.gradle.java.home` sed 제거~~ (2026-07-09 갱신: 근본 해결로 대체 — 해당 라인을 OMS·WMS 레포 `gradle.properties`에서 아예 제거하고 `~/.gradle/gradle.properties`로 이동. 같은 라인이 OMS 레포에도 7/1 커밋 `b328ef7`에 묻어 들어가 Railway OMS 빌드를 즉사시키고 있었음. sed는 불필요해져 Dockerfile에서 제거):

```dockerfile
# ---- 빌드 스테이지: JDK 21 + Gradle 래퍼로 실행 가능한 boot jar 생성 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# 래퍼/빌드스크립트 먼저 복사해 의존성 레이어 캐시 활용
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# 소스 복사 후 boot jar 빌드(테스트는 로컬/CI에서 수행 — 배포 빌드는 패키징만)
COPY . .
RUN chmod +x gradlew && ./gradlew clean bootJar --no-daemon -x test

# ---- 런타임 스테이지: JRE만 담은 가벼운 이미지 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# Railway가 ${PORT}를 주입하면 application.yml의 server.port가 받는다(기본 8081).
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java -jar app.jar"]
```

- [x] **Step 2: .dockerignore 작성** — OMS 것 복제:

```
# 빌드 컨텍스트에서 제외 — 이미지 빌드 속도/재현성 향상
.git
.gradle
build
.idea
*.iml
*.iws
*.ipr
out
bin
spy.log
HELP.md
docs
*.zip
```

- [x] **Step 3: 로컬 docker 빌드 검증(데몬 있으면)** — 2026-07-09 확인: docker 데몬 미가용 → 스킵, Railway 빌드로 최종 검증

Run: `cd C:\study\jhg-wms-project; docker build -t jhg-wms-test .`
Expected: 이미지 빌드 성공. **docker 데몬이 없거나 미설치면 스킵하고 보고서에 "Railway 빌드로 최종 검증" 명시**.

- [x] **Step 4: 커밋 + push**

```
git add Dockerfile .dockerignore
git commit -m "feat(wms): Dockerfile(멀티스테이지 JDK21) + .dockerignore — Railway 컨테이너 배포

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push
```

---

### Task 4: [로컬 검증] base-url env 치환 실증

**Files:** 없음(검증만). 사전 조건: H2 TCP 서버 기동(안 떠 있으면 `"C:\Program Files\Java\jdk-17\bin\java" -cp <gradle 캐시의 h2-2.3.232.jar> org.h2.tools.Server -tcp -tcpPort 9092` 백그라운드 실행).

두 방향 모두 "기본값 동작"과 "env 오버라이드 동작"을 실증한다. OMS는 8090 포트(8080 점유).

- [x] **V1 — OMS 기본값(로컬 불변)**: WMS 정상 기동(`cd C:\study\jhg-wms-project; .\gradlew.bat bootRun --args='--oms.base-url=http://localhost:8090'`) + OMS 기동(`cd C:\study\jhg-commerce-project; $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"; .\gradlew.bat bootRun --args='--server.port=8090'`) → `http://localhost:8090/main`에서 상품 카드에 재고 표시.
  Expected: 기본값 `http://localhost:8081`로 WMS 통신 성공 = 로컬 동작 불변.

- [x] **V2 — OMS env 오버라이드**: OMS만 재기동, PowerShell에서 `$env:WMS_BASE_URL = "http://localhost:9999"; .\gradlew.bat bootRun --args='--server.port=8090'` → 메인 로드.
  Expected: 전 카드 가용수량 0(입고 대기) + 로그에 `WMS 연결 실패 — 가용수량 0으로 폴백` warn = env가 placeholder를 실제로 치환함. 확인 후 `$env:WMS_BASE_URL` 제거(`Remove-Item Env:WMS_BASE_URL`) 후 재기동해 정상 복귀 확인.

- [x] **V3 — WMS env 오버라이드(콜백 방향)**: WMS를 env로 재기동 — `$env:OMS_BASE_URL = "http://localhost:8090"; .\gradlew.bat bootRun` (--args 없이) → OMS에서 백오더 하나 만들고(재고 0 상품 주문 또는 재고조정으로 0 만든 뒤 주문) → OMS 관리자 재고조정 +delta → 승격 확인.
  Expected: 콜백이 8090의 OMS에 도착해 백오더 승격 = `OMS_BASE_URL` env 치환 실증. 확인 후 env 제거.

- [x] **마무리**: 기동한 bootRun 프로세스 종료(H2는 유지). 검증 결과를 보고서에 기록.

> **Task 4 검증 결과(2026-07-09)**: V1 — OMS(8090) 기본값 `localhost:8081`로 WMS 실가용수량(상품1: 119 등) 조회, 폴백 warn 없음 = 로컬 불변. V2 — `WMS_BASE_URL=http://localhost:9999` 주입 시 메인 카드 10/10 "입고 대기"(가용 0 폴백) + `WmsInventoryQueryAdapter` warn에 9999 URL 확인, env 제거 재기동으로 정상 복귀 = placeholder 치환 실증. V3 — WMS를 `OMS_BASE_URL=http://localhost:8090`으로 기동, 상품3(가용 50)에 60개 주문 → 주문 102 BACKORDERED 접수 → 관리자 재고조정 +20 → 주문 102 ORDER 승격. 승격 UPDATE가 OMS HTTP 워커 스레드(`nio-8090-exec-3`)에서 실행됨 = 스윕(scheduling-1) 아닌 콜백 경로 확정. 조정 응답 379ms(false-timeout #23 미발생).

---

### Task 5: [문서 + 병합]

**Files:**
- Modify: `C:\study\jhg-commerce-project\CLAUDE.md`
- Modify: `C:\study\jhg-wms-project\README.md`

- [x] **Step 1: OMS CLAUDE.md 갱신** — 두 곳: (+추가 반영: JAVA_HOME 컨벤션을 머신 로컬 gradle.properties 방식으로 교체, 배포 DB 서술을 Flyway+validate로 교정 — 최종 리뷰 발견)

① `## 배포 (Railway)` 섹션 끝에 항목 추가:

```markdown
- **WMS 앱(2026-07-08~)**: 별도 Railway 서비스(`jhg-wms-project` 리포) + 자체 PostgreSQL. `SPRING_PROFILES_ACTIVE=prod` + PG* 변수, 스키마는 `ddl-auto: update`(Flyway 미도입), 빈 DB면 `InitDb`가 재고 1~20 시드. **공개 도메인 없음** — OMS↔WMS는 Railway Private Networking(`http://<service>.railway.internal:<PORT>`, PORT는 Variables로 OMS 8080/WMS 8081 고정). 통신 주소는 env로 주입: OMS `WMS_BASE_URL`, WMS `OMS_BASE_URL`(로컬 기본값은 localhost — yml placeholder). **V3 마이그레이션**이 OMS DB에서 inventory·reservation·purchase_order* 테이블/시퀀스 DROP(데이터 이관 없음, 이후 S1 이전 빌드로 롤백 불가). 관리 작업(재고조정·발주·입고)은 OMS 관리자 화면이 어댑터로 프록시 — WMS UI 접근 불필요. 부수 효과: `/api/replenishments`·WMS API 인터넷 미노출로 #15의 콜백 보호가 네트워크 격리로 해소.
```

② `### 심각도 낮음`의 15번 항목을 교체:

```markdown
15. H2 콘솔 `permitAll` + CSRF 예외 — 개발용으로는 무방하나 운영 배포 시 제거. ~~`/api/replenishments`(무인증 콜백)도 운영 시 보호 대상.~~ → 콜백/WMS API는 Railway Private Networking으로 인터넷 미노출(2026-07-08 해소). H2 콘솔 항목만 잔존.
```

③ `### 개선 우선순위` 1번을 완료로 반영해 목록 갱신:

```markdown
1. (선택) Phase 4 — REST → 이벤트/메시지 기반 전환(콜백·통지를 브로커로).
2. (선택) Phase 2 잔여 — 컨트롤러·DTO의 컨텍스트별 분리 정리.
3. 운영 배포 단계 시 `update` 대신 Flyway 마이그레이션 도입 검토(#15 H2 콘솔 정리 포함. WMS도 스키마 진화 시작되면 Flyway 도입 검토).
```

- [x] **Step 2: WMS README 갱신** — 적절한 위치(포트/실행 안내 부근)에 섹션 추가: (README 커밋 7a131fa는 병행 세션의 `feature/admin-ui` 브랜치에 실림 — 해당 브랜치 병합 시 master 반영)

```markdown
## 운영 배포 (Railway)

- Dockerfile(멀티스테이지 JDK21) 존재 시 Railway가 자동 사용. `.dockerignore`로 build/·.git/ 제외.
- `prod` 프로파일: PostgreSQL(PG* 변수), `ddl-auto: update`, H2 콘솔 off. 빈 DB면 `InitDb`가 재고 1~20 시드.
- Variables: `SPRING_PROFILES_ACTIVE=prod`, `PORT=8081`(private networking 주소 고정용), `OMS_BASE_URL=http://<oms>.railway.internal:8080`.
- 공개 도메인 없음 — OMS가 private networking으로만 호출. 관리 작업은 OMS 관리자 화면이 프록시.
- 주의: Gradle 빌드 JVM(JDK 21)은 머신 로컬 `~/.gradle/gradle.properties`의 `org.gradle.java.home`으로 지정 — 레포 `gradle.properties`에 커밋하지 않는다(Windows 경로가 컨테이너 빌드를 죽임).
```

- [x] **Step 3: 커밋 + 최종 리뷰 + 병합** — 최종 리뷰 통과(코드 결함 0, 문서 교정 1건 반영). 추가 fix: 양 리포 gradle.properties에서 `org.gradle.java.home` 제거(Railway 컨테이너 빌드 실패 원인) + WMS Dockerfile sed 우회 제거.

```
cd C:\study\jhg-wms-project
git add README.md
git commit -m "docs: Railway 운영 배포 안내 — prod 프로파일·private networking·Dockerfile

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push

cd C:\study\jhg-commerce-project
git add CLAUDE.md docs/superpowers/plans/2026-07-08-prod-deployment-alignment.md
git commit -m "docs: 운영 배포 정합성 기록 — WMS 서비스·private networking·V3 반영 + 구현 플랜

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git checkout master
git merge feature/prod-alignment
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat build
git push
```

Expected: BUILD SUCCESSFUL. (병합 전 최종 whole-branch 리뷰는 실행 스킬의 게이트가 수행 — OMS 브랜치 + WMS 커밋 범위 함께.)

---

### Task 6: [배포] Railway 실배포 + 스모크 (사용자와 함께 — 서브에이전트 디스패치 금지)

**Files:** 없음. 대시보드 조작은 사용자가 수행하고, 컨트롤러가 단계별로 안내·확인한다. 사용자가 railway CLI를 쓰면 명령을 대신 안내.

- [ ] **Step 1: WMS 서비스 생성(사용자)** — Railway 프로젝트에:
  1. New Service → GitHub Repo → `jhg-wms-project`(master)
  2. New → Database → PostgreSQL (WMS 전용 인스턴스)
  3. WMS 서비스 Variables: `SPRING_PROFILES_ACTIVE=prod`, `PORT=8081`, `PGHOST=${{Postgres.PGHOST}}` `PGPORT=${{Postgres.PGPORT}}` `PGDATABASE=${{Postgres.PGDATABASE}}` `PGUSER=${{Postgres.PGUSER}}` `PGPASSWORD=${{Postgres.PGPASSWORD}}` (신설한 WMS Postgres 참조 — OMS Postgres와 혼동 금지), `OMS_BASE_URL=http://<OMS 서비스명>.railway.internal:8080`
  4. 공개 도메인 발급하지 않음(Settings → Networking에서 Public 도메인 없음 확인)

- [ ] **Step 2: WMS 배포 확인** — 배포 로그에서: prod 프로파일 활성 / Hibernate 스키마 생성 / InitDb 시드(재고 20건) / `Tomcat started on port 8081`. 실패 시 로그 기반 진단(1순위 의심: PG* 변수 참조 오류, Dockerfile sed).

- [ ] **Step 3: OMS Variables 갱신(사용자)** — OMS 서비스에 `WMS_BASE_URL=http://<WMS 서비스명>.railway.internal:8081` 추가, `PORT=8080` 고정(미설정이었다면).

- [ ] **Step 4: OMS 배포** — master push로 자동 배포(Task 5에서 push 완료). 배포 로그에서: **Flyway V3 적용**(`Migrating schema ... to version "3"`) / 기동 성공. V3는 되돌릴 수 없으므로 이 로그를 반드시 확인·기록.

- [ ] **Step 5: 스모크 테스트 5종** — 공개 OMS URL에서:
  1. 메인 그리드 재고 표시(채널1) — 시드 재고 15~300이 카드에 보임
  2. 주문 → 즉시 ORDER(채널2 reserve)
  3. 관리자 재고조정 +delta → 백오더 승격 라운드트립(채널3 — 재고 0 상품으로 백오더 먼저 생성)
  4. 발주 생성 → 입고 → 재고 증가 + 승격
  5. 주문 취소 → 재고 복구
  통신 실패 시 1차 조치: 양쪽 `server.address: "::"` 검토(Railway private networking IPv6) — 적용 시 yml 수정·커밋·재배포.

- [ ] **Step 6: 완료 기록** — 스모크 결과를 CLAUDE.md 배포 섹션 항목 끝에 한 줄(`두 앱 Railway 스모크 5/5 통과(2026-07-08)` 형식)로 추가 후 master에 직접 커밋·push.

---

## Self-Review 결과

- **스펙 커버리지**: ①base-url env화(T1·T2 + T4 실증) ②WMS prod 스택(T2 의존성/프로파일/PORT, T3 Dockerfile/.dockerignore — gradle.properties 함정 처리 포함) ③V3(T1 — FlywayMigrationTest 무영향 확인 명시) ④배포 절차(T6 — WMS 먼저, PORT 고정, private networking, 도메인 미발급) ⑤스모크(T6 Step 5 — IPv6 1차 조치 포함) + 문서(T5). 알려진 한계 3건은 스펙에 있고 T5 문서가 #15 해소·롤백 불가를 반영.
- **플레이스홀더 없음** 확인(모든 yml/SQL/Dockerfile 실코드 포함).
- **타입/값 일관성**: env 키 `WMS_BASE_URL`/`OMS_BASE_URL`, PORT 8080/8081, V3 파일명·DROP 순서가 태스크 간 동일.
- **특이 구조**: T4·T6은 검증/배포 태스크(코드 없음). T6은 사용자 인터랙티브 — 서브에이전트에 위임하지 말 것.
