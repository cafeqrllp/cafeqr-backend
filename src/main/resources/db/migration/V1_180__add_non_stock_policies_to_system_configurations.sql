ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS non_stock_sales_policy VARCHAR(20) DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS non_stock_transfer_policy VARCHAR(20) DEFAULT 'NONE';

UPDATE system_configurations
SET non_stock_sales_policy = 'NONE'
WHERE non_stock_sales_policy IS NULL;

UPDATE system_configurations
SET non_stock_transfer_policy = 'NONE'
WHERE non_stock_transfer_policy IS NULL;
