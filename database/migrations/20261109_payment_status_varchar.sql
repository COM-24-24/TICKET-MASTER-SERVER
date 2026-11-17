-- Hibernate cannot widen an existing MariaDB ENUM when the Java enum gains a value.
-- Back up the database before applying this migration.
ALTER TABLE payments
    MODIFY COLUMN status VARCHAR(20) NOT NULL;
