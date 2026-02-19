package com.sportsbook.wallet.service.command;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Move {@code amount} from {@code EXTERNAL_PAYMENT} into the user's available bucket. The PG is
 * mocked in V1, so the external counterparty is a fixed system account.
 */
public record DepositCommand(UUID userId, Money amount, IdempotencyKey idempotencyKey) {

  public DepositCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }
}
