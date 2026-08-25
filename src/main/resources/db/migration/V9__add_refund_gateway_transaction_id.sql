ALTER TABLE refund_request ADD COLUMN IF NOT EXISTS gateway_transaction_id VARCHAR(255);
