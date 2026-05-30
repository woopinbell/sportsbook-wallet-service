package com.sportsbook.wallet.domain;

/**
 * Business reason a ledger entry was written. Coarse enough to fit the V1 flows (deposit, withdraw,
 * bet stake, bet payout, bet refund) without leaking the calling service's internal vocabulary.
 */
public enum LedgerReason {
  /** External money flowing into the user's account. */
  DEPOSIT,
  /** External money leaving the user's account. */
  WITHDRAW,
  /** Stake moving from the user's available bucket to their locked bucket on bet acceptance. */
  BET_DEBIT,
  /** Profit paid out by the house when a bet wins. */
  BET_PAYOUT,
  /** Stake returned from locked back to available (void / push / cancelled event). */
  BET_REFUND,
  /** Stake forfeited from the user's locked bucket to the house when a bet loses. */
  BET_FORFEIT
}
