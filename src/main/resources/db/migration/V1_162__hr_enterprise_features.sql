-- 1. Employee Salary Components Mapping Table
CREATE TABLE hr_employee_salary_components (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    employee_id UUID NOT NULL REFERENCES hr_employees(id),
    salary_component_id UUID NOT NULL REFERENCES hr_salary_components(id),
    override_amount NUMERIC(19, 2),
    override_percentage NUMERIC(5, 2),
    is_active BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_hr_emp_sal_comp_tenant ON hr_employee_salary_components(client_id, org_id);
CREATE INDEX idx_hr_emp_sal_comp_emp ON hr_employee_salary_components(employee_id);

-- 2. Leave Balances Table
CREATE TABLE hr_leave_balances (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    employee_id UUID NOT NULL REFERENCES hr_employees(id),
    leave_type VARCHAR(50) NOT NULL,
    total_allocated INT NOT NULL,
    total_used INT DEFAULT 0,
    year INT NOT NULL,
    UNIQUE (employee_id, leave_type, year)
);

CREATE INDEX idx_hr_leave_balance_tenant ON hr_leave_balances(client_id, org_id);
CREATE INDEX idx_hr_leave_balance_emp ON hr_leave_balances(employee_id);

-- 3. Add Statutory IDs to Employees
ALTER TABLE hr_employees 
ADD COLUMN tax_id VARCHAR(100),
ADD COLUMN national_id VARCHAR(100);
