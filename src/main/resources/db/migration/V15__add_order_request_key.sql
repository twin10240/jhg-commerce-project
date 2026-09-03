ALTER TABLE orders ADD COLUMN IF NOT EXISTS request_key UUID;
UPDATE orders SET request_key = gen_random_uuid() WHERE request_key IS NULL;
ALTER TABLE orders ALTER COLUMN request_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_request_key ON orders(request_key);
