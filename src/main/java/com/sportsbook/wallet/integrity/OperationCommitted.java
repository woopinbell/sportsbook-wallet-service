package com.sportsbook.wallet.integrity;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain event published from {@link com.sportsbook.wallet.service.WalletService} immediately after
 * a successful operation. {@link InvariantChecker} subscribes via
 * {@code @TransactionalEventListener(AFTER_COMMIT)} so the check fires only when the ledger pair
 * actually committed, not on a rolled-back attempt.
 */
public record OperationCommitted(UUID operationGroupId) {

  public OperationCommitted {
    Objects.requireNonNull(operationGroupId, "operationGroupId");
  }
}
