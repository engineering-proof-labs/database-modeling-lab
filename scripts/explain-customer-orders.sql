BEGIN;

INSERT INTO orders (id, customer_id, status, total_cents, created_at, confirmed_at)
SELECT
    ('40000000-0000-4000-8000-' || lpad(series.value::text, 12, '0'))::uuid,
    'customer-003',
    CASE WHEN series.value % 2 = 0 THEN 'CONFIRMED' ELSE 'PENDING' END,
    1000,
    TIMESTAMPTZ '2025-01-01T00:00:00Z' + series.value * INTERVAL '1 second',
    CASE WHEN series.value % 2 = 0
        THEN TIMESTAMPTZ '2025-01-01T00:00:00Z' + series.value * INTERVAL '1 second'
        ELSE NULL
    END
FROM generate_series(1, 20000) AS series(value);

ANALYZE orders;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_id, status, total_cents, created_at
FROM orders
WHERE customer_id = 'customer-003'
ORDER BY created_at DESC, id DESC
LIMIT 11;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_id, status, total_cents, created_at
FROM orders
WHERE customer_id = 'customer-003'
  AND (created_at, id) < (
      TIMESTAMPTZ '2025-01-01T00:00:00Z' + 10000 * INTERVAL '1 second',
      '40000000-0000-4000-8000-000000010000'::uuid
  )
ORDER BY created_at DESC, id DESC
LIMIT 11;

ROLLBACK;
ANALYZE orders;
