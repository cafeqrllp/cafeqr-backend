-- V1.190: UOM precision (decimal places) is limited to 0-4 in the database.
-- Existing out-of-range values are clamped to the nearest allowed value first.

UPDATE uoms SET uom_precision = 4 WHERE uom_precision > 4;
UPDATE uoms SET uom_precision = 0 WHERE uom_precision < 0;

ALTER TABLE uoms ADD CONSTRAINT chk_uoms_precision CHECK (uom_precision BETWEEN 0 AND 4);
