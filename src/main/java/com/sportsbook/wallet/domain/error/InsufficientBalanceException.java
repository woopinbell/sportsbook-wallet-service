package com.sportsbook.wallet.domain.error;

import com.sportsbook.protocol.value.Money;
import java.util.UUID;

/**
 * Thrown when a debit or withdrawal would drive the available balance below zero (ADR-0005). The
 * controller layer maps this to RFC 7807 {@code 422 Unprocessable Entity} with the type slug {@code
 * WALLET_INSUFFICIENT_BALANCE}.
 */
public class InsufficientBalanceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID userId;
  private final Money requested;
  private final Money available;

  public InsufficientBalanceException(UUID userId, Money requested, Money available) {
    super(
        "Insufficient available balance for user "
            + userId
            + ": requested "
            + requested
            + ", available "
            + available);
    this.userId = userId;
    this.requested = requested;
    this.available = available;
  }

  public UUID userId() {
    return userId;
  }

  public Money requested() {
    return requested;
  }

  public Money available() {
    return available;
  }
}
