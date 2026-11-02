-- One row per transfer's fraud assessment. A REVIEW decision stays mutable:
-- when a human resolves it, reviewed_at/reviewed_outcome/reviewer_note are
-- filled in on this SAME row rather than inserting a new one, so there's
-- always exactly one current assessment per transfer.
CREATE TABLE fraud_decisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID NOT NULL UNIQUE,
    from_account_id UUID NOT NULL,
    to_account_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL,
    decision VARCHAR(10) NOT NULL CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    risk_score INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    reviewed_outcome VARCHAR(20) CHECK (reviewed_outcome IN ('CONFIRMED_FRAUD', 'FALSE_POSITIVE')),
    reviewer_note VARCHAR(500)
);
CREATE INDEX idx_fraud_decisions_open_review ON fraud_decisions (created_at) WHERE decision = 'REVIEW' AND reviewed_at IS NULL;

-- Explainability: which rules fired (score > 0) for a given decision, and why.
CREATE TABLE fraud_decision_reasons (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    fraud_decision_id UUID NOT NULL REFERENCES fraud_decisions(id),
    rule_name VARCHAR(100) NOT NULL,
    rule_score INT NOT NULL,
    explanation VARCHAR(500) NOT NULL
);
CREATE INDEX idx_fraud_decision_reasons_decision_id ON fraud_decision_reasons (fraud_decision_id);

-- fraud-service's own local read model, built incrementally from
-- payment.created events -- it never queries payment-service's database
-- directly (see Phase 1 docs/DECISIONS.md #3). Backs the amount-anomaly rule.
CREATE TABLE account_amount_stats (
    account_id UUID PRIMARY KEY,
    transaction_count BIGINT NOT NULL DEFAULT 0,
    total_amount_cents BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backs the new-recipient rule: has this sender ever paid this recipient before?
CREATE TABLE account_recipient_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_account_id UUID NOT NULL,
    to_account_id UUID NOT NULL,
    transfer_count BIGINT NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_recipient_pair UNIQUE (from_account_id, to_account_id)
);

-- Dedup guard for the payment.created Kafka consumer (at-least-once delivery).
CREATE TABLE processed_payment_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
