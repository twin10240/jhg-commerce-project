-- V2: prod DB schema 정리 + inventory.product_id NOT NULL + UNIQUE (2026-06-30)
--
-- [배경] V1 baseline: prod DB가 구형 JPA ddl-auto schema 보유.
--   - product.inventory_id (구 @OneToOne 양방향 그래프 잔재) 컬럼 존재
--   - inventory.product_id NULL 허용 (신규 컬럼, 기존 행에 미채움)
-- [목표] inventory.product_id를 역산해 채운 뒤 구형 컬럼 제거 + 제약조건 추가.
--
-- 이 마이그레이션은 PostgreSQL(prod) 전용이다.
-- H2 FlywayMigrationTest는 V1까지만 검증한다(FlywayMigrationTest 주석 참고).

-- Step 1: 구 FK 제약조건 제거 (DELETE 시 FK 위반 방지)
ALTER TABLE product DROP CONSTRAINT IF EXISTS fk470fbed1o05ss4aqy8b8i8r8b;

-- Step 2: 구 product.inventory_id로부터 inventory.product_id 역산
UPDATE inventory i
SET product_id = p.product_id
FROM product p
WHERE p.inventory_id = i.inventory_id
  AND i.product_id IS NULL;

-- Step 3: 구 product.inventory_id 컬럼 제거
ALTER TABLE product DROP COLUMN IF EXISTS inventory_id;

-- Step 4: 역산 불가 고아 행 삭제
DELETE FROM inventory WHERE product_id IS NULL;

-- Step 5: 제약조건 추가
ALTER TABLE inventory ALTER COLUMN product_id SET NOT NULL;
ALTER TABLE inventory ADD CONSTRAINT uq_inventory_product_id UNIQUE (product_id);
