package com.sportsbook.wallet.domain;

import java.util.Set;
import java.util.UUID;

/**
 * Fixed UUIDs for the system-side ledger counterparties. These accounts never have a row in {@code
 * account} — they exist purely to satisfy the double-entry invariant (sum of all ledger entries =
 * 0) by giving the external world a stable target to debit or credit against.
 *
 * <p>The IDs use a recognisable all-zero pattern with a valid UUID v7 version nibble (4 bits =
 * {@code 0x7}) and variant ({@code 0b10}) so they parse cleanly anywhere a real UUID v7 would, but
 * remain trivial to spot in logs.
 */
public final class SystemAccountIds {

  /** House pool — receives forfeited stakes, pays out profit on winning bets. */
  public static final UUID HOUSE = UUID.fromString("00000000-0000-7000-8000-000000000001");

  /**
   * Aggregate external payment counterparty for deposits and withdrawals. The real PG is mocked in
   * V1 ({@code wallet-service/CLAUDE.md} — "DO NOT 결제 PG 직접 연동").
   */
  public static final UUID EXTERNAL_PAYMENT =
      UUID.fromString("00000000-0000-7000-8000-000000000002");

  private static final Set<UUID> ALL = Set.of(HOUSE, EXTERNAL_PAYMENT);

  public static boolean isSystemAccount(UUID accountId) {
    return ALL.contains(accountId);
  }

  private SystemAccountIds() {
    // Utility holder.
  }
}
