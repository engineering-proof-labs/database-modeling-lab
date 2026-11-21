BEGIN;

INSERT INTO orders (id, customer_id, status, total_cents, created_at, confirmed_at)
SELECT ('70000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid, 'customer-002',
       CASE WHEN n % 20 = 0 THEN 'PENDING' ELSE 'CONFIRMED' END, 100,
       timestamptz '2040-01-15T12:00:00Z' - n * interval '1 second',
       CASE WHEN n % 20 <> 0 THEN timestamptz '2040-01-15T12:00:00Z' END
FROM generate_series(1, 20000) AS n;
ANALYZE orders;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, customer_id, status, total_cents, created_at
FROM orders
WHERE status = 'PENDING'
  AND created_at >= timestamptz '2040-01-15T11:30:00Z'
  AND created_at < timestamptz '2040-01-15T12:00:00Z'
ORDER BY created_at DESC, id DESC
LIMIT 11;

ROLLBACK;
