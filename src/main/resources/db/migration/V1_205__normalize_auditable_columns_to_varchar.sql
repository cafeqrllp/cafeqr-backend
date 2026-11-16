-- Normalise audit columns across every schema (fresh test servers included).
--
-- AuditableEntity / BaseEntity write created_by / updated_by as VARCHAR (the user id as text).
-- Several early migrations (V1_23 ERP tables, V1_100 product_recipes, ...) created them as UUID.
-- V1_30 fixed only the V1_23 tables, so databases built from scratch (or with out-of-order runs)
-- could still have UUID columns and fail inserts with:
--   column "created_by" is of type uuid but expression is of type character varying
--
-- Exceptions: order_lines and pricelist_versions map these columns as java.util.UUID (see V1_33),
-- so they must stay UUID. This migration is idempotent: on an already-correct database it does nothing.

DO $$
DECLARE
    col record;
BEGIN
    FOR col IN
        SELECT c.table_name, c.column_name
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
         WHERE c.table_schema = current_schema()
           AND c.column_name IN ('created_by', 'updated_by')
           AND c.data_type = 'uuid'
           AND c.table_name NOT IN ('order_lines', 'pricelist_versions')
    LOOP
        RAISE NOTICE 'Converting %.% from UUID to VARCHAR(255)', col.table_name, col.column_name;
        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE VARCHAR(255) USING %I::text',
                       col.table_name, col.column_name, col.column_name);
    END LOOP;
END $$;
