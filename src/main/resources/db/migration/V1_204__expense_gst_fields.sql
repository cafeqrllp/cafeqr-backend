-- India GST details on expenses so they can be exported as an inward-supply GST register
-- (supplier GSTIN, bill no., HSN/SAC, rate and tax amount) alongside purchases.
-- All columns are optional: existing expenses keep working and export with blanks flagged.

ALTER TABLE expenses ADD COLUMN IF NOT EXISTS supplier_name   VARCHAR(255);
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS supplier_gstin  VARCHAR(20);
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS bill_no         VARCHAR(100);
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS hsn_sac         VARCHAR(20);
-- GST rate in percent (e.g. 18.0000). NULL = no GST captured.
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS gst_rate        NUMERIC(7, 4);
-- GST included in `amount` (amount is always the total paid, tax-inclusive).
ALTER TABLE expenses ADD COLUMN IF NOT EXISTS tax_amount      NUMERIC(15, 2);
