-- V2: inventory.product_id NOT NULL + UNIQUE (2026-06-30)
-- 코드는 productId당 재고 1행을 전제(findByProductId 단건, toMap 중복키 불허).
-- product_id IS NULL 인 고아 행은 제약 추가 전 삭제한다.

DELETE FROM inventory WHERE product_id IS NULL;

ALTER TABLE inventory ALTER COLUMN product_id SET NOT NULL;
ALTER TABLE inventory ADD CONSTRAINT uq_inventory_product_id UNIQUE (product_id);
