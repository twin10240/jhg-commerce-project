ALTER TABLE delivery ADD COLUMN delivery_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE delivery ADD COLUMN delivery_status_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery ADD COLUMN delivery_failed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery ADD COLUMN delivery_failure_code VARCHAR(30);
ALTER TABLE delivery DROP CONSTRAINT IF EXISTS delivery_status_check;
ALTER TABLE delivery ADD CONSTRAINT delivery_status_check
    CHECK (status IN ('READY', 'SHIPPED', 'DELIVERED', 'DELIVERY_FAILED'));
ALTER TABLE delivery ADD CONSTRAINT ck_delivery_version CHECK (delivery_version >= 0);
ALTER TABLE delivery ADD CONSTRAINT ck_delivery_failure_code
    CHECK (delivery_failure_code IS NULL OR delivery_failure_code IN ('RECIPIENT_UNAVAILABLE', 'ADDRESS_ISSUE'));
