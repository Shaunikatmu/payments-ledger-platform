-- Baseline migration. Enables gen_random_uuid() for primary keys used by
-- the fraud_decisions tables added in Phase 3.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
