-- V1: account aggregate + double-entry ledger journal (ADR-0005, ADR-0003).
--
-- Two tables, no FK from ledger_entry.account_id back to account.user_id:
-- the ledger also targets the system counterparties HOUSE and EXTERNAL_PAYMENT
-- (com.sportsbook.wallet.domain.SystemAccountIds), which never have rows in
-- account. The service layer enforces that account_id is either a real user
-- account or one of those fixed IDs.

CREATE TABLE account (
    user_id            UUID                     PRIMARY KEY,
    available_amount   BIGINT                   NOT NULL,
    available_currency VARCHAR(3)               NOT NULL,
    locked_amount      BIGINT                   NOT NULL,
    locked_currency    VARCHAR(3)               NOT NULL,
    version            BIGINT                   NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Defence in depth: the domain layer guarantees both buckets share the
    -- same currency on every mutation, but a misconfigured Hibernate mapping
    -- or a stray UPDATE would otherwise be able to drift them apart.
    CONSTRAINT account_currency_match  CHECK (available_currency = locked_currency),
    CONSTRAINT account_available_nonneg CHECK (available_amount >= 0),
    CONSTRAINT account_locked_nonneg    CHECK (locked_amount    >= 0)
);

COMMENT ON TABLE  account                      IS 'Per-user balance aggregate. Mutated under SELECT FOR UPDATE.';
COMMENT ON COLUMN account.available_amount     IS 'Spendable funds, denominated in available_currency.';
COMMENT ON COLUMN account.locked_amount        IS 'Funds held against open bet exposure.';
COMMENT ON COLUMN account.version              IS 'JPA @Version — guards non-balance metadata updates.';

CREATE TABLE ledger_entry (
    entry_id           UUID                     PRIMARY KEY,
    account_id         UUID                     NOT NULL,
    bucket             VARCHAR(16)              NOT NULL,
    side               VARCHAR(6)               NOT NULL,
    amount             BIGINT                   NOT NULL,
    currency           VARCHAR(3)               NOT NULL,
    reason             VARCHAR(16)              NOT NULL,
    idempotency_key    VARCHAR(128)             NOT NULL,
    operation_group_id UUID                     NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ledger_entry_amount_positive CHECK (amount > 0),
    CONSTRAINT ledger_entry_bucket_valid    CHECK (bucket IN ('AVAILABLE', 'LOCKED')),
    CONSTRAINT ledger_entry_side_valid      CHECK (side   IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entry_reason_valid    CHECK (reason IN (
        'DEPOSIT', 'WITHDRAW', 'BET_DEBIT', 'BET_PAYOUT', 'BET_REFUND'
    )),
    -- Idempotency invariant: at most one DEBIT row and one CREDIT row per
    -- caller-supplied key (ADR-0005). The pair-shape would break a plain
    -- UNIQUE(idempotency_key) because both entries of a transfer share the
    -- key — adding `side` to the tuple is what makes the two compatible.
    CONSTRAINT uk_ledger_entry_idem_side  UNIQUE (idempotency_key, side),
    -- Defensive layer on the internal grouping ID, in case the service ever
    -- reuses an operation_group across keys.
    CONSTRAINT uk_ledger_entry_group_side UNIQUE (operation_group_id, side)
);

COMMENT ON TABLE  ledger_entry                      IS 'Append-only journal of debit/credit rows.';
COMMENT ON COLUMN ledger_entry.account_id           IS 'User account UUID v7, or a SystemAccountIds UUID for HOUSE / EXTERNAL_PAYMENT.';
COMMENT ON COLUMN ledger_entry.operation_group_id   IS 'UUID v7 — ties matched debit/credit rows of one logical transfer together.';

-- "All entries for this account in time order" — used by the after-commit
-- invariant listener and the daily reconciliation job.
CREATE INDEX ix_ledger_entry_account_created
    ON ledger_entry (account_id, created_at);

-- "Did we already process this caller key" — used by the Redis-miss fast path
-- and by the InsufficientBalance retry handler that looks up the original
-- response. Selectivity is high (almost unique per row), so a plain B-tree
-- index is appropriate.
CREATE INDEX ix_ledger_entry_idem_key
    ON ledger_entry (idempotency_key);
