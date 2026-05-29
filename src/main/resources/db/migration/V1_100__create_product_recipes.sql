-- V1_100__create_product_recipes.sql
CREATE TABLE IF NOT EXISTS product_recipes (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    ingredient_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity DECIMAL(15, 3) NOT NULL DEFAULT 1.000,
    isactive VARCHAR(1) DEFAULT 'Y',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by UUID,
    updated_by UUID,
    CONSTRAINT uq_product_ingredient UNIQUE (product_id, ingredient_id)
);
