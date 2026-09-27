-- Add optional variant_option_id to product_recipes for variant-aware ingredient recipes
ALTER TABLE product_recipes ADD COLUMN IF NOT EXISTS variant_option_id UUID REFERENCES variant_options(id) ON DELETE CASCADE;

-- Drop old unique constraint (product_id, ingredient_id) if exists
ALTER TABLE product_recipes DROP CONSTRAINT IF EXISTS uq_product_ingredient;
ALTER TABLE product_recipes DROP CONSTRAINT IF EXISTS uk_product_recipes_product_ingredient;

-- Create new unique index supporting NULL variant_option_id
CREATE UNIQUE INDEX IF NOT EXISTS uk_product_recipes_prod_ing_variant
ON product_recipes (product_id, ingredient_id, COALESCE(variant_option_id, '00000000-0000-0000-0000-000000000000'::uuid));
