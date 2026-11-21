BEGIN;

INSERT INTO orders (id, customer_id, status, total_cents, created_at, confirmed_at) VALUES
    ('30000000-0000-4000-8000-000000000001', 'customer-003', 'PENDING',   1000, '2026-11-20T12:00:00Z', NULL),
    ('30000000-0000-4000-8000-000000000002', 'customer-003', 'PENDING',   2000, '2026-11-20T12:00:00Z', NULL),
    ('30000000-0000-4000-8000-000000000003', 'customer-003', 'CONFIRMED', 3000, '2026-11-19T12:00:00Z', '2026-11-19T12:05:00Z'),
    ('30000000-0000-4000-8000-000000000004', 'customer-003', 'CONFIRMED', 4000, '2026-11-19T12:00:00Z', '2026-11-19T12:06:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO order_items (order_id, item_number, product_name, quantity, unit_price_cents, line_total_cents) VALUES
    ('30000000-0000-4000-8000-000000000001', 1, 'Fixture item 1', 1, 1000, 1000),
    ('30000000-0000-4000-8000-000000000002', 1, 'Fixture item 2', 1, 2000, 2000),
    ('30000000-0000-4000-8000-000000000003', 1, 'Fixture item 3', 1, 3000, 3000),
    ('30000000-0000-4000-8000-000000000004', 1, 'Fixture item 4', 1, 4000, 4000)
ON CONFLICT (order_id, item_number) DO NOTHING;

COMMIT;
