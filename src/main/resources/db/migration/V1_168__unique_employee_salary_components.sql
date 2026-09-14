-- Clean up duplicate employee salary component records, keeping the most recently updated/created record
WITH ranked_components AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY employee_id, salary_component_id 
               ORDER BY updated_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC
           ) as rnk
    FROM hr_employee_salary_components
)
DELETE FROM hr_employee_salary_components
WHERE id IN (
    SELECT id FROM ranked_components WHERE rnk > 1
);

-- Add Unique Constraint on (employee_id, salary_component_id)
ALTER TABLE hr_employee_salary_components
ADD CONSTRAINT uq_hr_emp_sal_comp UNIQUE (employee_id, salary_component_id);
