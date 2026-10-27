-- V1.194: AuditableEntity stores the acting user as text (a user id or 'SYSTEM'),
-- but V1.53 created created_by / updated_by on the waste tables as UUID, so every
-- insert failed with "column created_by is of type uuid but expression is of type character varying".
-- Match the VARCHAR(255) used by the other modules.

ALTER TABLE waste_logs
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text,
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;

ALTER TABLE waste_categories
    ALTER COLUMN created_by TYPE VARCHAR(255) USING created_by::text,
    ALTER COLUMN updated_by TYPE VARCHAR(255) USING updated_by::text;
