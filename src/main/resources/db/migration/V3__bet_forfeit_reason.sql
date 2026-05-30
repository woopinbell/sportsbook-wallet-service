-- V3: allow the BET_FORFEIT ledger reason.
--
-- A losing bet's stake is captured from the user's locked bucket into the HOUSE
-- system account (settlement's LOST path). The V1 CHECK constraint enumerated only
-- deposit / withdraw / debit / payout / refund, so the new locked->house pair would
-- be rejected. Drop and re-add the constraint with the extra reason. Existing rows
-- all use the original five reasons, so the re-validation is a no-op.

ALTER TABLE ledger_entry DROP CONSTRAINT ledger_entry_reason_valid;

ALTER TABLE ledger_entry
    ADD CONSTRAINT ledger_entry_reason_valid CHECK (reason IN (
        'DEPOSIT', 'WITHDRAW', 'BET_DEBIT', 'BET_PAYOUT', 'BET_REFUND', 'BET_FORFEIT'
    ));
