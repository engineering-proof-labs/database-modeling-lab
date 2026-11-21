WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE EDITION orders_v2 AS CHILD OF ORA$BASE;
GRANT USE ON EDITION orders_v2 TO orders_ebr;

CONNECT orders_ebr/OrdersEbrLocal42@//localhost:1521/FREEPDB1
ALTER SESSION SET EDITION = orders_v2;

ALTER TABLE purchase_orders ADD (total_amount NUMBER(10,2) CHECK (total_amount >= 0));

CREATE OR REPLACE EDITIONING VIEW purchase_order_api AS
    SELECT id, total_amount AS total_value FROM purchase_orders;

CREATE OR REPLACE TRIGGER orders_cents_to_amount
    BEFORE INSERT OR UPDATE OF total_cents ON purchase_orders
    FOR EACH ROW FORWARD CROSSEDITION DISABLE
BEGIN
    :NEW.total_amount := :NEW.total_cents / 100;
END;
/

CREATE OR REPLACE TRIGGER orders_amount_to_cents
    BEFORE INSERT OR UPDATE OF total_amount ON purchase_orders
    FOR EACH ROW REVERSE CROSSEDITION DISABLE
BEGIN
    :NEW.total_cents := :NEW.total_amount * 100;
END;
/

DECLARE
    errors_found NUMBER;
BEGIN
    SELECT COUNT(*) INTO errors_found FROM user_errors
    WHERE type = 'TRIGGER' AND attribute = 'ERROR'
      AND name IN ('ORDERS_CENTS_TO_AMOUNT', 'ORDERS_AMOUNT_TO_CENTS');
    IF errors_found > 0 THEN
        RAISE_APPLICATION_ERROR(-20001, 'Crossedition triggers did not compile');
    END IF;
END;
/

ALTER TRIGGER orders_cents_to_amount ENABLE;

-- Drain earlier writes and serialize this small lab backfill with writers.
LOCK TABLE purchase_orders IN EXCLUSIVE MODE WAIT 10;
UPDATE purchase_orders SET total_amount = total_cents / 100 WHERE total_amount IS NULL;
COMMIT;

ALTER TRIGGER orders_amount_to_cents ENABLE;
EXIT SUCCESS
