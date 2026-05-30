-- Idempotency guard for SIS. Replaces DynamoDB smartretail-idempotency-keys table.
-- INSERT ON CONFLICT DO NOTHING is atomic on the PK constraint — handles concurrent ingestion safely.
-- Post-prototype: add a scheduled job to prune rows WHERE processed_at < NOW() - INTERVAL '30 days'.
CREATE TABLE IF NOT EXISTS sales.processed_transactions (
    transaction_id UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT processed_transactions_pk PRIMARY KEY (transaction_id)
);
