-- Opening cost: one product_costs row per org / product / variant that has stock,
-- using the manually maintained cost price (variant cost first) as the starting cost.
INSERT INTO product_costs (client_id, org_id, product_id, variant_id, costing_method,
                           current_cost_price, current_qty, cumulated_amt, cumulated_qty, last_purchase_price)
SELECT agg.client_id,
       agg.org_id,
       agg.product_id,
       agg.variant_id,
       'AVERAGE_PO',
       agg.cost,
       agg.qty,
       ROUND(agg.cost * GREATEST(agg.qty, 0), 6),
       GREATEST(agg.qty, 0),
       agg.cost
FROM (
    SELECT MAX(s.client_id::text)::uuid AS client_id,
           w.org_id,
           s.product_id,
           s.variant_id,
           SUM(COALESCE(s.current_quantity, 0)) AS qty,
           COALESCE(
               (SELECT vp.cost_price FROM variant_pricing vp
                 WHERE vp.product_id = s.product_id AND vp.variant_option_id = s.variant_id
                   AND vp.cost_price IS NOT NULL
                 LIMIT 1),
               (SELECT p.cost_price FROM products p WHERE p.id = s.product_id),
               0) AS cost
    FROM stock_snapshots s
    JOIN warehouses w ON w.id = s.warehouse_id
    WHERE w.org_id IS NOT NULL
    GROUP BY w.org_id, s.product_id, s.variant_id
) agg
ON CONFLICT DO NOTHING;
