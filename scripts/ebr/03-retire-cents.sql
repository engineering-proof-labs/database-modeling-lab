WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
ALTER SESSION SET EDITION = orders_v2;

DECLARE
    inconsistent_rows NUMBER;
BEGIN
    IF SYS_CONTEXT('USERENV', 'SESSION_USER') <> 'ORDERS_EBR' THEN
        RAISE_APPLICATION_ERROR(-20002, 'This script only runs as ORDERS_EBR');
    END IF;
    SELECT COUNT(*) INTO inconsistent_rows FROM purchase_orders
    WHERE total_amount IS NULL OR total_amount * 100 <> total_cents;
    IF inconsistent_rows > 0 THEN
        RAISE_APPLICATION_ERROR(-20003, 'Order representations disagree');
    END IF;
END;
/

-- Oracle DDL commits each step. Retire the old API before this migration.
ALTER TABLE purchase_orders MODIFY (total_amount NOT NULL);
DROP TRIGGER orders_cents_to_amount;
DROP TRIGGER orders_amount_to_cents;
ALTER TABLE purchase_orders DROP COLUMN total_cents;
EXIT SUCCESS
