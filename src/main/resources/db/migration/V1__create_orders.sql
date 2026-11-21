CREATE TABLE customers (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL
);

INSERT INTO customers (id, name) VALUES
    ('customer-001', 'Avery Stone'),
    ('customer-002', 'Morgan Lee'),
    ('customer-003', 'Taylor Quinn');

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED')),
    total_cents BIGINT NOT NULL CHECK (total_cents > 0),
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT orders_confirmation_matches_status CHECK (
        (status = 'PENDING' AND confirmed_at IS NULL)
        OR (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
    )
);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    item_number SMALLINT NOT NULL CHECK (item_number > 0 AND item_number <= 20),
    product_name VARCHAR(120) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0 AND quantity <= 999),
    unit_price_cents BIGINT NOT NULL CHECK (unit_price_cents > 0 AND unit_price_cents <= 100000000),
    line_total_cents BIGINT NOT NULL CHECK (line_total_cents > 0),
    PRIMARY KEY (order_id, item_number)
);
