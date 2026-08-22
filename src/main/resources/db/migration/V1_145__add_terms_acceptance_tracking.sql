-- V1_145: Add Terms & Conditions version acceptance tracking and audit log
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted_version VARCHAR(20) DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP DEFAULT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS terms_accepted_ip VARCHAR(45) DEFAULT NULL;

CREATE TABLE IF NOT EXISTS terms_acceptance_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    client_id UUID,
    terms_version VARCHAR(20) NOT NULL,
    ip_address VARCHAR(45),
    user_agent TEXT,
    accepted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_terms_audit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_terms_audit_user_id ON terms_acceptance_audit(user_id);
CREATE INDEX IF NOT EXISTS idx_terms_audit_version ON terms_acceptance_audit(terms_version);
