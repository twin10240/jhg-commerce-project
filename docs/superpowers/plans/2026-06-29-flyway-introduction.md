# Flyway 도입 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 운영 PostgreSQL의 `ddl-auto: update`를 Flyway 버전 관리 마이그레이션으로 교체해 스키마 drift를 방지한다.

**Architecture:** Flyway를 prod 프로파일에서만 활성화하고, `baseline-on-migrate=false` + `CREATE TABLE IF NOT EXISTS` 전략으로 기존 Railway DB와 신규 DB 모두 V1 마이그레이션이 안전하게 실행되도록 한다. local/test 프로파일은 H2 + `ddl-auto: create/create-drop`을 유지해 기존 개발 흐름을 건드리지 않는다.

**Tech Stack:** Spring Boot 3.5.5, Flyway 10.x (Spring Boot managed), PostgreSQL 16, H2 (로컬/테스트 유지)

## Global Constraints

- Java 17, Gradle, Spring Boot 3.5.5
- Flyway는 **prod 프로파일에서만** 활성화 — H2 환경(local, test)은 기존 ddl-auto 유지
- `application.yml` 파일 하나에 멀티 document(`---`) 구조 유지 (기존 방식과 일관)
- V1 SQL은 `CREATE TABLE IF NOT EXISTS` / `CREATE SEQUENCE IF NOT EXISTS` 사용 — 기존 Railway DB가 이미 이 스키마를 가지고 있으므로 멱등(idempotent)하게 작성
- 빌드/테스트 명령: `.\gradlew.bat build`, `.\gradlew.bat test`
- `JAVA_HOME`은 JDK 17 필요: `$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"`

---

## File Map

| 작업 | 파일 | 변경 |
|------|------|------|
| Task 1 | `build.gradle` | Flyway 의존성 추가 |
| Task 2 | `src/main/resources/db/migration/V1__init_schema.sql` | 신규 생성 — 전체 스키마 DDL (PostgreSQL) |
| Task 3 | `src/main/resources/application.yml` | prod: Flyway 활성화 + `ddl-auto: validate` / root: Flyway 비활성화 |
| Task 3 | `src/test/resources/application.yml` | `spring.flyway.enabled: false` 명시 |

---

### Task 1: Flyway 의존성 추가

**Files:**
- Modify: `build.gradle`

**Interfaces:**
- Produces: Flyway 10.x auto-configuration 활성화 (Spring Boot managed version 사용)

Spring Boot 3.5.x는 Flyway 10.x를 관리한다. PostgreSQL 지원은 Flyway 10부터 별도 모듈(`flyway-database-postgresql`)로 분리되었으므로 두 가지를 모두 추가해야 한다.

- [ ] **Step 1: `build.gradle` dependencies 블록에 추가**

```groovy
// 기존 dependencies 블록 안, runtimeOnly 'org.postgresql:postgresql' 아래에 추가:
implementation 'org.flywaydb:flyway-core'
runtimeOnly 'org.flywaydb:flyway-database-postgresql'
```

최종 결과 (`build.gradle` dependencies 블록):
```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'com.github.gavlyukovskiy:p6spy-spring-boot-starter:1.12.0'
    implementation 'org.springframework.security:spring-security-crypto'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.thymeleaf.extras:thymeleaf-extras-springsecurity6'
    implementation 'com.querydsl:querydsl-jpa:5.0.0:jakarta'
    annotationProcessor 'com.querydsl:querydsl-apt:5.0.0:jakarta'
    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
    compileOnly 'org.projectlombok:lombok'
    runtimeOnly 'com.h2database:h2'
    runtimeOnly 'org.postgresql:postgresql'
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly 'org.flywaydb:flyway-database-postgresql'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

- [ ] **Step 2: 빌드 확인 (H2 환경, Flyway 아직 비활성 — 이 단계에선 application.yml 미수정이라 Flyway가 H2에 붙으려다 실패할 수 있음. Task 3 완료 후 최종 빌드 확인)**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat dependencies --configuration runtimeClasspath | Select-String "flyway"
```

Expected: `flyway-core`, `flyway-database-postgresql` 라인 출력.

- [ ] **Step 3: Commit**

```powershell
git add build.gradle
git commit -m "build: Flyway 10.x 의존성 추가 (flyway-core + flyway-database-postgresql)"
```

---

### Task 2: V1 마이그레이션 SQL 작성

**Files:**
- Create: `src/main/resources/db/migration/V1__init_schema.sql`

**Interfaces:**
- Produces: Flyway가 로드하는 초기 스키마 마이그레이션 파일

**핵심 설계 결정:**
- `CREATE TABLE IF NOT EXISTS` 사용 — 기존 Railway DB(이미 스키마 존재)에서도 V1이 안전하게 실행됨
- `CREATE SEQUENCE IF NOT EXISTS` + `INCREMENT BY 50` — Hibernate 6의 기본 allocationSize=50과 일치
- Hibernate 6 + Spring Boot 3의 시퀀스 명명: `{entity_logical_name}_seq` (SpringPhysicalNamingStrategy 적용, camelCase → snake_case)
- `@GeneratedValue` (AUTO 전략) → PostgreSQL에서 SEQUENCE 전략으로 동작

- [ ] **Step 1: 마이그레이션 디렉터리 생성 및 V1 SQL 파일 작성**

`src/main/resources/db/migration/V1__init_schema.sql`:

```sql
-- V1: 초기 스키마 (2026-06-29)
-- IF NOT EXISTS 사용: 기존 Railway DB(이미 스키마 있음)에서도 멱등하게 실행됨.
-- Hibernate 6 기본 시퀀스 allocationSize=50 → INCREMENT BY 50.

-- ───────────────────────────────────────────
-- Sequences (Hibernate 6 AUTO → SEQUENCE)
-- ───────────────────────────────────────────
CREATE SEQUENCE IF NOT EXISTS account_seq         START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS member_seq          START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS cart_seq            START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS cart_item_seq       START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS product_seq         START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS delivery_seq        START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS orders_seq          START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS order_item_seq      START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS inventory_seq       START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS purchase_order_seq      START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS purchase_order_item_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS reservation_seq     START WITH 1 INCREMENT BY 50;

-- ───────────────────────────────────────────
-- Tables
-- ───────────────────────────────────────────

CREATE TABLE IF NOT EXISTS member (
    member_id BIGINT      NOT NULL DEFAULT nextval('member_seq'),
    name      VARCHAR(50) NOT NULL,
    phone     VARCHAR(20),
    city      VARCHAR(255),
    street    VARCHAR(255),
    zipcode   VARCHAR(255),
    CONSTRAINT pk_member PRIMARY KEY (member_id)
);

CREATE TABLE IF NOT EXISTS account (
    id        BIGINT       NOT NULL DEFAULT nextval('account_seq'),
    email     VARCHAR(190) NOT NULL,
    password  VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL,
    member_id BIGINT,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT ux_account_email UNIQUE (email),
    CONSTRAINT uq_account_member UNIQUE (member_id),
    CONSTRAINT fk_account_member FOREIGN KEY (member_id) REFERENCES member(member_id)
);

CREATE TABLE IF NOT EXISTS cart (
    cart_id   BIGINT NOT NULL DEFAULT nextval('cart_seq'),
    member_id BIGINT NOT NULL,
    CONSTRAINT pk_cart PRIMARY KEY (cart_id),
    CONSTRAINT uq_cart_member UNIQUE (member_id),
    CONSTRAINT fk_cart_member FOREIGN KEY (member_id) REFERENCES member(member_id)
);

CREATE TABLE IF NOT EXISTS product (
    product_id BIGINT       NOT NULL DEFAULT nextval('product_seq'),
    name       VARCHAR(255),
    price      INTEGER      NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (product_id)
);

CREATE TABLE IF NOT EXISTS cart_item (
    cart_item_id  BIGINT  NOT NULL DEFAULT nextval('cart_item_seq'),
    cart_id       BIGINT,
    product_id    BIGINT,
    product_price INTEGER NOT NULL,
    quantity      INTEGER NOT NULL,
    CONSTRAINT pk_cart_item PRIMARY KEY (cart_item_id),
    CONSTRAINT fk_cart_item_cart    FOREIGN KEY (cart_id)    REFERENCES cart(cart_id),
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

CREATE TABLE IF NOT EXISTS delivery (
    delivery_id BIGINT      NOT NULL DEFAULT nextval('delivery_seq'),
    city        VARCHAR(255),
    street      VARCHAR(255),
    zipcode     VARCHAR(255),
    status      VARCHAR(20),
    CONSTRAINT pk_delivery PRIMARY KEY (delivery_id)
);

CREATE TABLE IF NOT EXISTS orders (
    order_id    BIGINT      NOT NULL DEFAULT nextval('orders_seq'),
    member_id   BIGINT,
    delivery_id BIGINT,
    order_date  TIMESTAMP,
    status      VARCHAR(20),
    CONSTRAINT pk_orders    PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_member   FOREIGN KEY (member_id)   REFERENCES member(member_id),
    CONSTRAINT fk_orders_delivery FOREIGN KEY (delivery_id) REFERENCES delivery(delivery_id)
);

CREATE TABLE IF NOT EXISTS order_item (
    order_item_id BIGINT  NOT NULL DEFAULT nextval('order_item_seq'),
    order_id      BIGINT,
    product_id    BIGINT,
    order_price   INTEGER NOT NULL,
    count         INTEGER NOT NULL,
    CONSTRAINT pk_order_item PRIMARY KEY (order_item_id),
    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id)   REFERENCES orders(order_id),
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

CREATE TABLE IF NOT EXISTS inventory (
    inventory_id  BIGINT  NOT NULL DEFAULT nextval('inventory_seq'),
    version       BIGINT,
    on_hand_qty   INTEGER NOT NULL,
    reserved_qty  INTEGER NOT NULL,
    product_id    BIGINT,
    CONSTRAINT pk_inventory PRIMARY KEY (inventory_id)
);

CREATE TABLE IF NOT EXISTS purchase_order (
    purchase_order_id BIGINT       NOT NULL DEFAULT nextval('purchase_order_seq'),
    status            VARCHAR(20),
    memo              VARCHAR(255),
    created_at        TIMESTAMP,
    received_at       TIMESTAMP,
    CONSTRAINT pk_purchase_order PRIMARY KEY (purchase_order_id)
);

CREATE TABLE IF NOT EXISTS purchase_order_item (
    purchase_order_item_id BIGINT  NOT NULL DEFAULT nextval('purchase_order_item_seq'),
    purchase_order_id      BIGINT,
    product_id             BIGINT,
    quantity               INTEGER NOT NULL,
    CONSTRAINT pk_purchase_order_item PRIMARY KEY (purchase_order_item_id),
    CONSTRAINT fk_poi_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(purchase_order_id)
);

CREATE TABLE IF NOT EXISTS reservation (
    reservation_id BIGINT      NOT NULL DEFAULT nextval('reservation_seq'),
    order_id       BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL,
    CONSTRAINT pk_reservation  PRIMARY KEY (reservation_id),
    CONSTRAINT uq_reservation_order UNIQUE (order_id)
);
```

- [ ] **Step 2: Commit (yml 수정 전 단독 커밋)**

```powershell
git add src/main/resources/db/migration/V1__init_schema.sql
git commit -m "feat(flyway): V1 초기 스키마 마이그레이션 추가 (IF NOT EXISTS — 기존 Railway DB 멱등)"
```

---

### Task 3: application.yml 설정 — Flyway 프로파일별 구성

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`

**Interfaces:**
- Produces: prod 프로파일에서만 Flyway 실행, local/test는 기존 H2 + ddl-auto 유지

**설계:**
- 루트 문서(H2 로컬 기본): `spring.flyway.enabled: false` — H2에 Flyway가 붙지 않도록 명시 차단
- `local` 프로파일: Flyway 비활성 (루트에서 상속), `ddl-auto: create` 유지
- `prod` 프로파일: `spring.flyway.enabled: true`, `ddl-auto: validate`
- 테스트 yml: `spring.flyway.enabled: false` 명시

- [ ] **Step 1: `src/main/resources/application.yml` 수정**

```yaml
# 로컬 기본은 8080, Railway 등 PaaS는 주입되는 ${PORT}에 바인딩한다.
server:
  port: ${PORT:8080}

spring:
  datasource:
    url: jdbc:h2:tcp://localhost/~/hgpage
    username: sa
    password:
    driver-class-name: org.h2.Driver

  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        show_sql: true
        format_sql: true
        default_batch_fetch_size: 100

  # H2 환경(로컬/개발)에서는 Flyway 비활성 — H2 드라이버 모듈 없음 + ddl-auto로 스키마 관리
  flyway:
    enabled: false

  logging:
    level:
      org.hibernate.SQL: debug
      org.hibernate.type.descriptor.sql.BasicBinder: trace

---
# 리셋용 프로파일: 스키마 DROP & 재생성
spring:
  config:
    activate:
      on-profile: local
  jpa:
    hibernate:
      ddl-auto: create

---
# 운영 프로파일(Railway): PostgreSQL + Flyway
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
      # Flyway가 스키마를 관리한다. Hibernate는 검증만 수행.
      ddl-auto: validate
    properties:
      hibernate:
        show_sql: false
        format_sql: false
        default_batch_fetch_size: 100
  flyway:
    enabled: true
    # V1은 IF NOT EXISTS로 작성 → 기존 Railway DB에서도 안전하게 실행됨
    # 첫 배포 시 flyway_schema_history 테이블이 자동 생성되고 V1이 적용(기록)됨
    locations: classpath:db/migration
decorator:
  datasource:
    p6spy:
      enable-logging: false
```

- [ ] **Step 2: `src/test/resources/application.yml` 수정**

```yaml
# 테스트 전용 설정: 임베디드 H2, Flyway 비활성
spring:
  datasource:
    url: jdbc:h2:mem:hgpage-test;DB_CLOSE_DELAY=-1
    username: sa
    password:
    driver-class-name: org.h2.Driver

  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100

  flyway:
    enabled: false
```

- [ ] **Step 3: 로컬 테스트 통과 확인**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat test
```

Expected: 전체 테스트 GREEN. Flyway가 H2 테스트에 개입하지 않음.

- [ ] **Step 4: Commit**

```powershell
git add src/main/resources/application.yml src/test/resources/application.yml
git commit -m "feat(flyway): prod 프로파일 Flyway 활성화 + ddl-auto validate, H2 환경 비활성"
```

---

### Task 4: 배포 및 Railway DB 검증

**Files:** 없음 (배포 확인 단계)

**목표:** Railway prod DB에서 Flyway V1이 정상 적용되고 앱이 기동됨을 확인한다.

- [ ] **Step 1: 전체 빌드 확인**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL, 테스트 전체 통과.

- [ ] **Step 2: Git push (Railway 자동 배포 트리거)**

```powershell
git push origin feature/phase3-s1
```

Railway는 push 시 자동으로 Docker 빌드 + 배포를 실행한다.

- [ ] **Step 3: Railway 배포 로그에서 Flyway 실행 확인**

Railway 대시보드 또는 CLI로 로그 확인:

```powershell
railway logs --service jhg-commerce-project
```

정상 기동 시 다음과 같은 로그가 보여야 한다:

```
Flyway Community Edition ... by Redgate
See release notes here: ...
Database: jdbc:postgresql://... (PostgreSQL ...)
Successfully validated 1 migration (execution time ...)
Creating Schema History table "public"."flyway_schema_history" ...
Current version of schema "public": << Empty Schema >>
Migrating schema "public" to version "1 - init schema"
Successfully applied 1 migration to schema "public" (execution time ...)
```

기존 Railway DB에 이미 테이블이 있으므로 `IF NOT EXISTS`에 의해 DDL이 no-op으로 실행되고 `flyway_schema_history` 테이블만 새로 생긴다.

- [ ] **Step 4: 앱 동작 확인**

브라우저에서 `https://jhg-commerce-project-production.up.railway.app` 접속해 메인 화면 정상 로딩 확인.

- [ ] **Step 5: `flyway_schema_history` 확인 (선택)**

Railway 콘솔에서 psql로 접속해 확인:

```sql
SELECT version, description, type, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Expected:
```
 version | description | type | success
---------+-------------+------+---------
 1       | init schema | SQL  | true
```

---

## 이후 스키마 변경 방법

Flyway 도입 후 스키마 변경은 반드시 마이그레이션 파일로 관리한다:

```
src/main/resources/db/migration/
  V1__init_schema.sql          ← 수정 금지 (Flyway checksum 검증)
  V2__add_something.sql        ← 다음 변경분
  V3__alter_column.sql         ← 그 다음
```

**규칙:**
- 배포된 Vx 파일은 절대 수정하지 않는다 (checksum 불일치로 앱 기동 실패)
- 새 변경은 항상 다음 버전 번호로 새 파일 생성
- local 환경에서 스키마 리셋이 필요하면 기존대로 `--spring.profiles.active=local`로 H2 drop-create 사용
