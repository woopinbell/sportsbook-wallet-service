package com.sportsbook.wallet.domain.error;

import com.sportsbook.protocol.value.Currency;

/**
 * Thrown when an operation tries to combine balances or amounts denominated in different currencies
 * (ADR-0003). One {@code Account} = one {@code Currency} in V1, so any transfer to a user must
 * match the account's currency exactly.
 */
public class CurrencyMismatchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Currency expected;
  private final Currency actual;

  public CurrencyMismatchException(Currency expected, Currency actual) {
    super("Currency mismatch: expected " + expected + ", got " + actual);
    this.expected = expected;
    this.actual = actual;
  }

  public Currency expected() {
    return expected;
  }

  public Currency actual() {
    return actual;
  }
}
