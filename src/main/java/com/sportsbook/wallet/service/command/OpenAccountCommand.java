package com.sportsbook.wallet.service.command;

import com.sportsbook.protocol.value.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Open a fresh wallet account for a user with both buckets at zero. Idempotent on {@code userId}.
 */
public record OpenAccountCommand(UUID userId, Currency currency) {

  public OpenAccountCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(currency, "currency");
  }
}
