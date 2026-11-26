ALTER TABLE categories ADD COLUMN sequence_no INT NULL;
ALTER TABLE system_configurations ADD COLUMN category_sort_mode VARCHAR(20) NOT NULL DEFAULT 'NAME_ASC';
