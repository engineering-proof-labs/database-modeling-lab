WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET LINESIZE 180
SET PAGESIZE 100

-- Use disposable rows and roll them back after displaying the optimizer plans.
INSERT INTO orders (id, customer_id, status, total_cents, created_at)
SELECT '40000000-0000-4000-8000-' || LPAD(LEVEL, 12, '0'),
       CASE WHEN MOD(LEVEL, 20) = 0 THEN 'customer-003' ELSE 'customer-002' END,
       'PENDING', 1000, TIMESTAMP '2026-11-18 12:00:00' - NUMTODSINTERVAL(LEVEL, 'SECOND')
FROM dual CONNECT BY LEVEL <= 20000;

EXPLAIN PLAN SET STATEMENT_ID = 'ORDERS_FIRST' FOR
SELECT id, customer_id, status, total_cents, created_at
FROM orders
WHERE customer_id = 'customer-003'
ORDER BY created_at DESC, id DESC
FETCH FIRST 11 ROWS ONLY;

SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, 'ORDERS_FIRST', 'TYPICAL +PREDICATE'));

EXPLAIN PLAN SET STATEMENT_ID = 'ORDERS_CURSOR' FOR
SELECT id, customer_id, status, total_cents, created_at
FROM orders
WHERE customer_id = 'customer-003'
  AND (created_at < TIMESTAMP '2026-11-20 12:00:00'
       OR (created_at = TIMESTAMP '2026-11-20 12:00:00'
           AND id < '30000000-0000-4000-8000-000000000002'))
ORDER BY created_at DESC, id DESC
FETCH FIRST 11 ROWS ONLY;

SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, 'ORDERS_CURSOR', 'TYPICAL +PREDICATE'));

ROLLBACK;
EXIT SUCCESS
