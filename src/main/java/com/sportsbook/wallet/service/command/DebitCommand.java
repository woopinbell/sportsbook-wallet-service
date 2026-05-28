package com.sportsbook.wallet.service.command;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Stage {@code amount} from the user's available bucket into their locked bucket. Used by the
 * betting flow when a slip is accepted — the stake is held against the open exposure until
 * settlement decides where it goes.
 */
public record DebitCommand(UUID userId, Money amount, IdempotencyKey idempotencyKey) {

  public DebitCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }
}
