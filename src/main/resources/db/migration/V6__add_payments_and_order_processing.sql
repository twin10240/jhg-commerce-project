CREATE SEQUENCE IF NOT EXISTS payment_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS payment_attempt_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS refund_request_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE payment (
    payment_id BIGINT NOT NULL DEFAULT nextval('payment_seq'),
    order_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_amount INTEGER NOT NULL,
    paid_amount INTEGER NOT NULL,
    pending_refund_amount INTEGER NOT NULL,
    refunded_amount INTEGER NOT NULL,
    approved_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT,
    CONSTRAINT pk_payment PRIMARY KEY (payment_id),
    CONSTRAINT uq_payment_order UNIQUE (order_id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT ck_payment_order_amount CHECK (order_amount >= 0),
    CONSTRAINT ck_payment_paid_amount CHECK (paid_amount >= 0),
    CONSTRAINT ck_payment_pending_refund_amount CHECK (pending_refund_amount >= 0),
    CONSTRAINT ck_payment_refunded_amount CHECK (refunded_amount >= 0)
);

CREATE TABLE payment_attempt (
    payment_attempt_id BIGINT NOT NULL DEFAULT nextval('payment_attempt_seq'),
    payment_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    gateway_transaction_id VARCHAR(255),
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMP,
    failure_code VARCHAR(100),
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT pk_payment_attempt PRIMARY KEY (payment_attempt_id),
    CONSTRAINT fk_payment_attempt_payment FOREIGN KEY (payment_id) REFERENCES payment(payment_id),
    CONSTRAINT uq_payment_attempt_request_key UNIQUE (request_key),
    CONSTRAINT ck_payment_attempt_count CHECK (attempt_count >= 0)
);

CREATE TABLE refund_request (
    refund_request_id BIGINT NOT NULL DEFAULT nextval('refund_request_seq'),
    payment_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL,
    amount INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMP,
    last_failure_code VARCHAR(100),
    last_failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    version BIGINT,
    CONSTRAINT pk_refund_request PRIMARY KEY (refund_request_id),
    CONSTRAINT fk_refund_request_payment FOREIGN KEY (payment_id) REFERENCES payment(payment_id),
    CONSTRAINT uq_refund_request_key UNIQUE (request_key),
    CONSTRAINT uq_refund_source UNIQUE (source_type, source_id),
    CONSTRAINT ck_refund_request_amount CHECK (amount >= 0),
    CONSTRAINT ck_refund_request_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_payment_attempt_due ON payment_attempt (status, next_attempt_at, payment_attempt_id);
CREATE INDEX idx_refund_request_due ON refund_request (status, next_attempt_at, refund_request_id);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS allocation_attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS next_allocation_attempt_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allocation_failure_code VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allocation_processing_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_release_required BOOLEAN;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_requested_at TIMESTAMP;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_processing_at TIMESTAMP;
