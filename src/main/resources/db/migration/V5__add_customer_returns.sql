CREATE SEQUENCE IF NOT EXISTS customer_return_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS customer_return_item_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE customer_return (
    customer_return_id BIGINT NOT NULL DEFAULT nextval('customer_return_seq'),
    order_id BIGINT NOT NULL,
    request_key UUID NOT NULL,
    rma_id BIGINT,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    failure_reason VARCHAR(100),
    requested_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT pk_customer_return PRIMARY KEY (customer_return_id),
    CONSTRAINT fk_customer_return_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT uq_customer_return_request_key UNIQUE (request_key),
    CONSTRAINT uq_customer_return_rma_id UNIQUE (rma_id)
);

CREATE TABLE customer_return_item (
    customer_return_item_id BIGINT NOT NULL DEFAULT nextval('customer_return_item_seq'),
    customer_return_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    requested_quantity INTEGER NOT NULL,
    accepted_quantity INTEGER,
    disposition VARCHAR(30),
    CONSTRAINT pk_customer_return_item PRIMARY KEY (customer_return_item_id),
    CONSTRAINT fk_customer_return_item_return FOREIGN KEY (customer_return_id)
        REFERENCES customer_return(customer_return_id),
    CONSTRAINT fk_customer_return_item_order_item FOREIGN KEY (order_item_id)
        REFERENCES order_item(order_item_id),
    CONSTRAINT uq_customer_return_order_item UNIQUE (customer_return_id, order_item_id),
    CONSTRAINT ck_customer_return_requested_quantity CHECK (requested_quantity > 0),
    CONSTRAINT ck_customer_return_accepted_quantity CHECK
        (accepted_quantity IS NULL OR accepted_quantity >= 0)
);

UPDATE delivery SET status = 'SHIPPED' WHERE status = 'COMP';
