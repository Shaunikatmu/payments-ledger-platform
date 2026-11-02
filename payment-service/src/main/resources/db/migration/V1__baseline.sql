-- Baseline migration. Enables gen_random_uuid() for primary keys used by
-- the ledger/account tables added in Phase 2.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
