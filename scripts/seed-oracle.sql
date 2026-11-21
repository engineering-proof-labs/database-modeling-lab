WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF

MERGE INTO orders target USING (
    SELECT '30000000-0000-4000-8000-000000000001' id, 'PENDING' status, 1000 total_cents,
           TIMESTAMP '2026-11-20 12:00:00' created_at, CAST(NULL AS TIMESTAMP) confirmed_at FROM dual
    UNION ALL
    SELECT '30000000-0000-4000-8000-000000000002', 'PENDING', 2000, TIMESTAMP '2026-11-20 12:00:00', NULL FROM dual
    UNION ALL
    SELECT '30000000-0000-4000-8000-000000000003', 'CONFIRMED', 3000,
           TIMESTAMP '2026-11-19 12:00:00', TIMESTAMP '2026-11-19 12:05:00' FROM dual
    UNION ALL
    SELECT '30000000-0000-4000-8000-000000000004', 'CONFIRMED', 4000,
           TIMESTAMP '2026-11-19 12:00:00', TIMESTAMP '2026-11-19 12:06:00' FROM dual
) fixture ON (target.id = fixture.id)
WHEN NOT MATCHED THEN INSERT (id, customer_id, status, total_cents, created_at, confirmed_at)
    VALUES (fixture.id, 'customer-003', fixture.status, fixture.total_cents, fixture.created_at, fixture.confirmed_at);

MERGE INTO order_items target USING (
    SELECT '30000000-0000-4000-8000-00000000000' || LEVEL order_id,
           'Fixture item ' || LEVEL product_name, 1000 * LEVEL cents
    FROM dual CONNECT BY LEVEL <= 4
) fixture ON (target.order_id = fixture.order_id AND target.item_number = 1)
WHEN NOT MATCHED THEN INSERT (order_id, item_number, product_name, quantity, unit_price_cents, line_total_cents)
    VALUES (fixture.order_id, 1, fixture.product_name, 1, fixture.cents, fixture.cents);

COMMIT;
EXIT SUCCESS
