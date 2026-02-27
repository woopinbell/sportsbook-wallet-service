package com.sportsbook.wallet.service.command;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Forfeit {@code amount} from the user's locked bucket to the house — settlement's LOST path. The
 * stake was staged into {@code locked} at placement (BET_DEBIT); when the bet loses it leaves the
 * user's account entirely as a locked-&gt;HOUSE pair under LedgerReason {@code BET_FORFEIT}, rather
 * than being refunded (push / void) or released and topped up with profit (win).
 */
public record ForfeitCommand(UUID userId, Money amount, IdempotencyKey idempotencyKey) {

  public ForfeitCommand {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }
}
