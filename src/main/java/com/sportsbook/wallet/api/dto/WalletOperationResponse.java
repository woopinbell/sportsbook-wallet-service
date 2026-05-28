package com.sportsbook.wallet.api.dto;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.LedgerReason;
import com.sportsbook.wallet.service.WalletOperationResult;
import java.time.Instant;
import java.util.UUID;

/** Wire shape returned by every transaction endpoint. Mirrors {@link WalletOperationResult}. */
public record WalletOperationResponse(
    UUID operationGroupId, UUID userId, Money amount, LedgerReason reason, Instant at) {

  public static WalletOperationResponse from(WalletOperationResult r) {
    return new WalletOperationResponse(
        r.operationGroupId(), r.userId(), r.amount(), r.reason(), r.at());
  }
}
