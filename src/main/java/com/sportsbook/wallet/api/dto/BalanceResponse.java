package com.sportsbook.wallet.api.dto;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.Account;
import java.util.UUID;

/**
 * Wire shape returned by {@code GET /internal/v1/wallet/accounts/{userId}/balance}. Mirrors {@link
 * Account} but trims to the read-only balance triple the caller actually needs.
 */
public record BalanceResponse(UUID userId, Money available, Money locked, Money total) {

  public static BalanceResponse from(Account a) {
    return new BalanceResponse(a.userId(), a.available(), a.locked(), a.total());
  }
}
