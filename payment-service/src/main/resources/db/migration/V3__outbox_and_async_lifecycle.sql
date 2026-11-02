-- Phase 3: transfers now complete asynchronously, pending a fraud.decision
-- event instead of completing in the same request. UNDER_REVIEW is a new
-- non-terminal state entered when fraud-service can't auto-decide and
-- queues the transfer for manual review.
ALTER TABLE transfers DROP CONSTRAINT chk_transfer_status;
ALTER TABLE transfers ADD CONSTRAINT chk_transfer_status
    CHECK (status IN ('PENDING', 'APPROVED', 'UNDER_REVIEW', 'COMPLETED', 'REJECTED'));

-- Transactional outbox: payment-service writes a row here in the SAME
-- transaction as the transfer/debit it describes, so the event can never
-- be lost even if the process crashes right after committing. A separate
-- relay (OutboxRelay) polls this table and publishes to Kafka, marking
-- published_at once sent. See docs/DECISIONS.md.
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- Dedup guard for the fraud.decision Kafka consumer. Kafka delivers
-- at-least-once, so the same decision event can arrive more than once;
-- this makes applying it idempotent.
CREATE TABLE processed_fraud_decisions (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
