package com.sportsbook.wallet.domain;

/**
 * Double-entry side of a ledger row. We follow the asset-side convention: {@link #DEBIT} marks a
 * bucket that the operation increases, {@link #CREDIT} marks a bucket that the operation decreases.
 * Across the whole system the sum of debit amounts minus the sum of credit amounts is always zero,
 * which is the invariant checked after every transaction and by the daily reconciliation job.
 */
public enum LedgerSide {
  /** Balance on the target bucket goes up. */
  DEBIT,
  /** Balance on the target bucket goes down. */
  CREDIT
}
