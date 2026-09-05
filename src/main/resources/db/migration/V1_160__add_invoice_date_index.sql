-- Speeds up findMaxDailyBillNo in InvoiceRepository which is called during synchronous bill generation
CREATE INDEX IF NOT EXISTS idx_invoices_client_org_date ON invoices(client_id, org_id, invoice_date DESC);
