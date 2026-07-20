-- V1: 초기 스키마 (2026-06-29)
-- IF NOT EXISTS 사용: 기존 Railway DB(이미 스키마 있음)에서도 멱등하게 실행됨.
-- Hibernate 6 기본 시퀀스 allocationSize=50 → INCREMENT BY 50.

-- ───────────────────────────────────────────
-- Sequences (Hibernate 6 AUTO → SEQUENCE)
-- ───────────────────────────────────────────
CREATE SEQUENCE IF NOT EXISTS account_seq         START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS member_seq          START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS cart_seq            START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS cart_item_seq       START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS product_seq         START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS delivery_seq        START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS orders_seq          START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS order_item_seq      START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS inventory_seq       START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS purchase_order_seq      START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS purchase_order_item_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE IF NOT EXISTS reservation_seq     START WITH 1 INCREMENT BY 50;

-- ───────────────────────────────────────────
-- Tables
-- ───────────────────────────────────────────

CREATE TABLE IF NOT EXISTS member (
    member_id BIGINT      NOT NULL DEFAULT nextval('member_seq'),
    name      VARCHAR(50) NOT NULL,
    phone     VARCHAR(20),
    city      VARCHAR(255),
    street    VARCHAR(255),
    zipcode   VARCHAR(255),
    CONSTRAINT pk_member PRIMARY KEY (member_id)
);

CREATE TABLE IF NOT EXISTS account (
    id        BIGINT       NOT NULL DEFAULT nextval('account_seq'),
    email     VARCHAR(190) NOT NULL,
    password  VARCHAR(100) NOT NULL,
    role      VARCHAR(20)  NOT NULL,
    member_id BIGINT,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT ux_account_email UNIQUE (email),
    CONSTRAINT uq_account_member UNIQUE (member_id),
    CONSTRAINT fk_account_member FOREIGN KEY (member_id) REFERENCES member(member_id)
);

CREATE TABLE IF NOT EXISTS cart (
    cart_id   BIGINT NOT NULL DEFAULT nextval('cart_seq'),
    member_id BIGINT NOT NULL,
    CONSTRAINT pk_cart PRIMARY KEY (cart_id),
    CONSTRAINT uq_cart_member UNIQUE (member_id),
    CONSTRAINT fk_cart_member FOREIGN KEY (member_id) REFERENCES member(member_id)
);

CREATE TABLE IF NOT EXISTS product (
    product_id BIGINT       NOT NULL DEFAULT nextval('product_seq'),
    inventory_id BIGINT,
    name       VARCHAR(255),
    price      INTEGER      NOT NULL,
    CONSTRAINT pk_product PRIMARY KEY (product_id)
);

CREATE TABLE IF NOT EXISTS cart_item (
    cart_item_id  BIGINT  NOT NULL DEFAULT nextval('cart_item_seq'),
    cart_id       BIGINT,
    product_id    BIGINT,
    product_price INTEGER NOT NULL,
    quantity      INTEGER NOT NULL,
    CONSTRAINT pk_cart_item PRIMARY KEY (cart_item_id),
    CONSTRAINT fk_cart_item_cart    FOREIGN KEY (cart_id)    REFERENCES cart(cart_id),
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

CREATE TABLE IF NOT EXISTS delivery (
    delivery_id BIGINT      NOT NULL DEFAULT nextval('delivery_seq'),
    city        VARCHAR(255),
    street      VARCHAR(255),
    zipcode     VARCHAR(255),
    status      VARCHAR(20),
    CONSTRAINT pk_delivery PRIMARY KEY (delivery_id)
);

CREATE TABLE IF NOT EXISTS orders (
    order_id    BIGINT      NOT NULL DEFAULT nextval('orders_seq'),
    member_id   BIGINT,
    delivery_id BIGINT,
    order_date  TIMESTAMP,
    status      VARCHAR(20),
    CONSTRAINT pk_orders    PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_member   FOREIGN KEY (member_id)   REFERENCES member(member_id),
    CONSTRAINT fk_orders_delivery FOREIGN KEY (delivery_id) REFERENCES delivery(delivery_id)
);

CREATE TABLE IF NOT EXISTS order_item (
    order_item_id BIGINT  NOT NULL DEFAULT nextval('order_item_seq'),
    order_id      BIGINT,
    product_id    BIGINT,
    order_price   INTEGER NOT NULL,
    count         INTEGER NOT NULL,
    CONSTRAINT pk_order_item PRIMARY KEY (order_item_id),
    CONSTRAINT fk_order_item_order   FOREIGN KEY (order_id)   REFERENCES orders(order_id),
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

CREATE TABLE IF NOT EXISTS inventory (
    inventory_id  BIGINT  NOT NULL DEFAULT nextval('inventory_seq'),
    version       BIGINT,
    on_hand_qty   INTEGER NOT NULL,
    reserved_qty  INTEGER NOT NULL,
    product_id    BIGINT,
    CONSTRAINT pk_inventory PRIMARY KEY (inventory_id)
);

CREATE TABLE IF NOT EXISTS purchase_order (
    purchase_order_id BIGINT       NOT NULL DEFAULT nextval('purchase_order_seq'),
    status            VARCHAR(20),
    memo              VARCHAR(255),
    created_at        TIMESTAMP,
    received_at       TIMESTAMP,
    CONSTRAINT pk_purchase_order PRIMARY KEY (purchase_order_id)
);

CREATE TABLE IF NOT EXISTS purchase_order_item (
    purchase_order_item_id BIGINT  NOT NULL DEFAULT nextval('purchase_order_item_seq'),
    purchase_order_id      BIGINT,
    product_id             BIGINT,
    quantity               INTEGER NOT NULL,
    CONSTRAINT pk_purchase_order_item PRIMARY KEY (purchase_order_item_id),
    CONSTRAINT fk_poi_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(purchase_order_id)
);

CREATE TABLE IF NOT EXISTS reservation (
    reservation_id BIGINT      NOT NULL DEFAULT nextval('reservation_seq'),
    order_id       BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL,
    CONSTRAINT pk_reservation  PRIMARY KEY (reservation_id),
    CONSTRAINT uq_reservation_order UNIQUE (order_id)
);
