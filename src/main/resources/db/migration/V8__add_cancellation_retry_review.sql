ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_next_attempt_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_failure_code VARCHAR(100);

UPDATE orders
SET cancellation_next_attempt_at = COALESCE(cancellation_requested_at, CURRENT_TIMESTAMP)
WHERE status = 'CANCEL_REQUESTED'
  AND cancellation_release_required IS NOT NULL
  AND cancellation_processing_at IS NULL
  AND cancellation_next_attempt_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_orders_cancellation_due
    ON orders (status, cancellation_next_attempt_at, order_id);
