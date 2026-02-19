package com.sportsbook.wallet.service.command;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Move {@code amount} into the user's available bucket. The source bucket determines the operation
 * intent:
 *
 * <ul>
 *   <li>{@link Source#USER_LOCKED} — refund the stake held in {@code locked} (void / push, or the
 *       stake-return leg of a winning settlement). LedgerReason is {@code BET_REFUND}.
 *   <li>{@link Source#HOUSE_POOL} — pay out profit from the {@code HOUSE} system account.
 *       LedgerReason is {@code BET_PAYOUT}.
 * </ul>
 */
public record CreditCommand(
    UUID userId, Money amount, Source source, IdempotencyKey idempotencyKey) {

  public CreditCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  public enum Source {
    USER_LOCKED,
    HOUSE_POOL
  }
}
