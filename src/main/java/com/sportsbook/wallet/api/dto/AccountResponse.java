package com.sportsbook.wallet.api.dto;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.Account;
import java.time.Instant;
import java.util.UUID;

/** Wire shape for a single account row. */
public record AccountResponse(
    UUID userId,
    Currency currency,
    Money available,
    Money locked,
    long version,
    Instant createdAt,
    Instant updatedAt) {

  public static AccountResponse from(Account a) {
    return new AccountResponse(
        a.userId(),
        a.currency(),
        a.available(),
        a.locked(),
        a.version(),
        a.createdAt(),
        a.updatedAt());
  }
}
