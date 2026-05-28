package com.sportsbook.wallet.service.command;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import java.util.Objects;
import java.util.UUID;

/** Move {@code amount} from the user's available bucket out to {@code EXTERNAL_PAYMENT}. */
public record WithdrawCommand(UUID userId, Money amount, IdempotencyKey idempotencyKey) {

  public WithdrawCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }
}
