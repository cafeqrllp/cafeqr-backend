-- V1_99: Drop redundant 'document_kind' column from orders
ALTER TABLE orders DROP COLUMN IF EXISTS document_kind;
