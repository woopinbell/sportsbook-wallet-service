package com.sportsbook.wallet.domain;

/**
 * Bucket inside an account that a ledger entry hits (ADR-0005). The split lets the betting flow
 * stage stake against open exposure without moving money out of the user's account.
 */
public enum BalanceBucket {
  /** Spendable funds. Hits this bucket on deposit, withdraw, payout, refund. */
  AVAILABLE,
  /** Held against open bet exposure. Filled on stake, drained on settlement. */
  LOCKED
}
