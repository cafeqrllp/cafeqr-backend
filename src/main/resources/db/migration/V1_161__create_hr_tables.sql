-- 1. Department
CREATE TABLE hr_departments (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_hr_department_tenant ON hr_departments(client_id, org_id);

-- 2. Designation
CREATE TABLE hr_designations (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_hr_designation_tenant ON hr_designations(client_id, org_id);

-- 3. Employee
CREATE TABLE hr_employees (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255),
    email VARCHAR(255),
    phone_number VARCHAR(50),
    date_of_joining DATE,
    date_of_birth DATE,
    gender VARCHAR(50),
    user_id UUID,
    department_id UUID REFERENCES hr_departments(id),
    designation_id UUID REFERENCES hr_designations(id),
    base_salary NUMERIC(19, 2),
    hourly_rate NUMERIC(19, 2),
    employment_type VARCHAR(50),
    bank_name VARCHAR(255),
    bank_account_number VARCHAR(255),
    bank_routing_number VARCHAR(255),
    is_active BOOLEAN DEFAULT TRUE
);

CREATE INDEX idx_hr_employee_tenant ON hr_employees(client_id, org_id);

-- 4. Attendance
CREATE TABLE hr_attendance (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    employee_id UUID NOT NULL REFERENCES hr_employees(id),
    attendance_date DATE NOT NULL,
    clock_in_time TIMESTAMP NOT NULL,
    clock_out_time TIMESTAMP,
    total_hours_worked NUMERIC(10, 2) DEFAULT 0,
    overtime_hours NUMERIC(10, 2) DEFAULT 0,
    status VARCHAR(50),
    punch_method VARCHAR(50)
);

CREATE INDEX idx_hr_attendance_employee ON hr_attendance(employee_id);
CREATE INDEX idx_hr_attendance_tenant ON hr_attendance(client_id, org_id);

-- 5. Leave Request
CREATE TABLE hr_leave_requests (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    employee_id UUID NOT NULL REFERENCES hr_employees(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    total_days INT NOT NULL,
    leave_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    reason TEXT,
    approved_by UUID
);

CREATE INDEX idx_hr_leave_request_employee ON hr_leave_requests(employee_id);

-- 6. Salary Advance
CREATE TABLE hr_salary_advances (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    employee_id UUID NOT NULL REFERENCES hr_employees(id),
    total_amount NUMERIC(19, 2) NOT NULL,
    monthly_installment_amount NUMERIC(19, 2) NOT NULL,
    remaining_balance NUMERIC(19, 2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    advance_date DATE NOT NULL,
    reason TEXT
);

CREATE INDEX idx_hr_salary_advance_employee ON hr_salary_advances(employee_id);

-- 7. Salary Component
CREATE TABLE hr_salary_components (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    amount_type VARCHAR(50) NOT NULL,
    default_amount NUMERIC(19, 2),
    percentage NUMERIC(5, 2),
    percentage_of_component VARCHAR(255),
    is_tax_applicable BOOLEAN DEFAULT FALSE,
    depends_on_attendance BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE
);

-- 8. Payroll Run
CREATE TABLE hr_payroll_runs (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL
);

CREATE INDEX idx_hr_payroll_run_tenant ON hr_payroll_runs(client_id, org_id);

-- 9. Salary Slip
CREATE TABLE hr_salary_slips (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    org_id UUID NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    payroll_run_id UUID NOT NULL REFERENCES hr_payroll_runs(id),
    employee_id UUID NOT NULL REFERENCES hr_employees(id),
    total_worked_hours NUMERIC(10, 2) DEFAULT 0,
    total_unpaid_leave_days INT DEFAULT 0,
    gross_pay NUMERIC(19, 2) DEFAULT 0,
    total_deductions NUMERIC(19, 2) DEFAULT 0,
    net_pay NUMERIC(19, 2) DEFAULT 0,
    status VARCHAR(50)
);

CREATE INDEX idx_hr_salary_slip_run ON hr_salary_slips(payroll_run_id);
CREATE INDEX idx_hr_salary_slip_employee ON hr_salary_slips(employee_id);
