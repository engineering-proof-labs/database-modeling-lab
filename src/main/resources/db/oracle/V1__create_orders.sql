CREATE TABLE customers (
    id VARCHAR2(64 CHAR) PRIMARY KEY,
    name VARCHAR2(120 CHAR) NOT NULL
);

INSERT INTO customers VALUES ('customer-001', 'Avery Stone');
INSERT INTO customers VALUES ('customer-002', 'Morgan Lee');
INSERT INTO customers VALUES ('customer-003', 'Taylor Quinn');

CREATE TABLE orders (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    customer_id VARCHAR2(64 CHAR) NOT NULL REFERENCES customers (id),
    status VARCHAR2(16 CHAR) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED')),
    total_cents NUMBER(19, 0) NOT NULL CHECK (total_cents > 0),
    created_at TIMESTAMP(3) NOT NULL,
    confirmed_at TIMESTAMP(3),
    CONSTRAINT orders_confirmation_matches_status CHECK (
        (status = 'PENDING' AND confirmed_at IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE order_items (
    order_id VARCHAR2(36 CHAR) NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    item_number NUMBER(2, 0) NOT NULL CHECK (item_number BETWEEN 1 AND 20),
    product_name VARCHAR2(120 CHAR) NOT NULL,
    quantity NUMBER(3, 0) NOT NULL CHECK (quantity BETWEEN 1 AND 999),
    unit_price_cents NUMBER(19, 0) NOT NULL CHECK (unit_price_cents BETWEEN 1 AND 100000000),
    line_total_cents NUMBER(19, 0) NOT NULL CHECK (line_total_cents > 0),
    PRIMARY KEY (order_id, item_number)
);
