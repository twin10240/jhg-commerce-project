ALTER TABLE customer_return ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(255);
ALTER TABLE customer_return ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;
ALTER TABLE customer_return ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500);
