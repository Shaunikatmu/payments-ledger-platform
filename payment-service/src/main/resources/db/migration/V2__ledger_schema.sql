-- Accounts. account_type = SYSTEM marks internal accounts (currently just
-- the external funding source) that are exempt from the non-negative
-- balance check -- see the EXTERNAL_FUNDING seed row below and
-- docs/DECISIONS.md for why.
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    balance_cents BIGINT NOT NULL DEFAULT 0,
    account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_balance_non_negative CHECK (balance_cents >= 0 OR account_type = 'SYSTEM'),
    CONSTRAINT chk_account_type CHECK (account_type IN ('CUSTOMER', 'SYSTEM'))
);

-- One row per money movement. status starts PENDING; Phase 2 completes it
-- synchronously since fraud-service doesn't exist yet. Phase 3 inserts an
-- async APPROVED step (via a Kafka fraud.decision event) before COMPLETED.
CREATE TABLE transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    from_account_id UUID NOT NULL REFERENCES accounts(id),
    to_account_id UUID NOT NULL REFERENCES accounts(id),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_transfer_status CHECK (status IN ('PENDING', 'APPROVED', 'COMPLETED', 'REJECTED'))
);

-- Double-entry lines. For any transfer, its two lines always sum to zero:
-- one DEBIT (money leaving an account) and one CREDIT (money arriving).
CREATE TABLE journal_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID NOT NULL REFERENCES transfers(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_entries_account_id ON journal_entries(account_id);
CREATE INDEX idx_journal_entries_transfer_id ON journal_entries(transfer_id);

-- Generic HTTP-level idempotency store, shared by every POST endpoint.
-- See IdempotencyService / docs/DECISIONS.md for the claim-then-poll
-- design that makes "50 concurrent identical requests -> exactly one
-- charge" hold up.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_path VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status INT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

-- The external funding source every new account's initial balance is
-- transferred from, so total money in the system is always conserved --
-- even a "free" starting balance has a traceable counterparty.
INSERT INTO accounts (id, owner_name, currency, balance_cents, account_type)
VALUES ('00000000-0000-0000-0000-000000000001', 'EXTERNAL_FUNDING', 'USD', 9223372036854775807, 'SYSTEM');
