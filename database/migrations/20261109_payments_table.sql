-- Align the legacy payments table with the current Payment entity.
-- Back up the database before applying this migration.
ALTER TABLE payments
    DROP INDEX order_id,
    CHANGE COLUMN method provider VARCHAR(20) NOT NULL,
    MODIFY COLUMN status VARCHAR(20) NOT NULL,
    CHANGE COLUMN reference tx_ref VARCHAR(150) NOT NULL,
    ADD COLUMN order_status_updated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN order_status_update_error VARCHAR(500) NULL,
    ADD INDEX idx_payment_order_created (order_id, created_at);

UPDATE payments SET status = 'PAID' WHERE status = 'SUCCESS';
UPDATE payments SET provider = 'DEMO' WHERE provider = 'MOCK';
