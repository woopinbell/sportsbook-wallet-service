package com.sportsbook.wallet.service;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.LedgerEntry;
import com.sportsbook.wallet.domain.LedgerReason;
import com.sportsbook.wallet.domain.SystemAccountIds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire-shape outcome of any wallet write operation. Returned both on the original execution and on
 * idempotent retries — same fields, same values — so the caller cannot distinguish "we just did
 * this" from "we did this earlier, here is the same answer". The operation_group_id ties the
 * outcome back to the matched ledger pair if the caller needs deeper traceability.
 *
 * @param operationGroupId UUID v7 that groups the matched (debit, credit) pair in {@code
 *     ledger_entry}.
 * @param userId the user-facing account the operation affected (always the non-system account in
 *     the pair).
 * @param amount transfer amount, copied from the ledger row.
 * @param reason the {@link LedgerReason} that drove the transfer.
 * @param at timestamp the original entry was written.
 */
public record WalletOperationResult(
    UUID operationGroupId, UUID userId, Money amount, LedgerReason reason, Instant at) {

  /**
   * Rebuilds the original outcome from the matched pair already in the ledger — used by the retry /
   * race-loser path when the operation was processed earlier.
   */
  public static WalletOperationResult fromExisting(List<LedgerEntry> pair) {
    if (pair.isEmpty()) {
      throw new IllegalArgumentException("Cannot rebuild result from an empty pair");
    }
    LedgerEntry userSide =
        pair.stream()
            .filter(e -> !SystemAccountIds.isSystemAccount(e.accountId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Ledger pair lacks a user-side entry — unexpected for any V1 operation"));
    return new WalletOperationResult(
        userSide.operationGroupId(),
        userSide.accountId(),
        userSide.money(),
        userSide.reason(),
        userSide.createdAt());
  }
}
