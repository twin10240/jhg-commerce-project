ALTER TABLE delivery ALTER COLUMN status VARCHAR(20);
UPDATE delivery SET status = 'SHIPPED' WHERE CAST(status AS VARCHAR) = 'COMP';
ALTER TABLE IF EXISTS customer_return ALTER COLUMN status VARCHAR(30);
ALTER TABLE IF EXISTS customer_return DROP CONSTRAINT IF EXISTS uq_customer_return_rma_id;
ALTER TABLE IF EXISTS customer_return DROP CONSTRAINT IF EXISTS constraint_38;

-- OMS·WMS DB가 따로 초기화돼도 숫자 order_id 재사용이 예약 멱등성을 깨지 않게 한다.
-- Hibernate update가 nullable 컬럼을 먼저 만든 뒤 여기서 기존 행을 백필하고 DB 제약을 건다.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS request_key UUID;
UPDATE orders SET request_key = RANDOM_UUID() WHERE request_key IS NULL;
ALTER TABLE orders ALTER COLUMN request_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_request_key ON orders(request_key);
