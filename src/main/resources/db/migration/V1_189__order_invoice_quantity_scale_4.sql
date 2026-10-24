-- V1.189: Store order and invoice line quantities with 4 decimals, matching the maximum
-- UOM precision (4). Existing values are unchanged; widening the scale only adds zero digits.

ALTER TABLE order_lines   ALTER COLUMN quantity TYPE NUMERIC(15, 4);
ALTER TABLE invoice_lines ALTER COLUMN quantity TYPE NUMERIC(15, 4);
