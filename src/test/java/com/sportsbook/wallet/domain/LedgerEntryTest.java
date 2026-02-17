package com.sportsbook.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.LedgerEntry.Pair;
import com.sportsbook.wallet.domain.LedgerEntry.TransferLeg;
import com.sportsbook.wallet.infrastructure.id.UuidV7;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerEntryTest {

  private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000aaaa");
  private static final IdempotencyKey KEY = IdempotencyKey.of("bet-9f3e2-stake-debit");
  private static final Instant NOW = Instant.parse("2026-05-28T07:00:00Z");

  @Test
  @DisplayName("pair() emits one DEBIT on destination, one CREDIT on source, sharing keys")
  void pair_matched_debit_credit() {
    TransferLeg locked = new TransferLeg(USER, BalanceBucket.LOCKED);
    TransferLeg available = new TransferLeg(USER, BalanceBucket.AVAILABLE);
    Money stake = new Money(3_000, Currency.KRW);
    UUID group = UuidV7.generate();

    Pair pair = LedgerEntry.pair(locked, available, stake, LedgerReason.BET_DEBIT, KEY, group, NOW);

    assertThat(pair.debit().side()).isEqualTo(LedgerSide.DEBIT);
    assertThat(pair.debit().bucket()).isEqualTo(BalanceBucket.LOCKED);
    assertThat(pair.debit().accountId()).isEqualTo(USER);

    assertThat(pair.credit().side()).isEqualTo(LedgerSide.CREDIT);
    assertThat(pair.credit().bucket()).isEqualTo(BalanceBucket.AVAILABLE);
    assertThat(pair.credit().accountId()).isEqualTo(USER);

    // Tying fields that the unique-constraint pair relies on:
    assertThat(pair.debit().idempotencyKey()).isEqualTo(pair.credit().idempotencyKey());
    assertThat(pair.debit().operationGroupId()).isEqualTo(pair.credit().operationGroupId());
    assertThat(pair.debit().operationGroupId()).isEqualTo(group);

    // Entry IDs are independent UUIDs, even though everything else lines up.
    assertThat(pair.debit().entryId()).isNotEqualTo(pair.credit().entryId());

    // Amount + currency identical on both sides — sum debit − sum credit = 0 per group.
    assertThat(pair.debit().money()).isEqualTo(stake);
    assertThat(pair.credit().money()).isEqualTo(stake);
  }

  @Test
  @DisplayName("pair() rejects non-positive amounts")
  void rejects_non_positive_amount() {
    TransferLeg locked = new TransferLeg(USER, BalanceBucket.LOCKED);
    TransferLeg available = new TransferLeg(USER, BalanceBucket.AVAILABLE);
    Money zero = new Money(0, Currency.KRW);
    Money negative = new Money(-1, Currency.KRW);
    UUID group = UuidV7.generate();

    assertThatThrownBy(
            () ->
                LedgerEntry.pair(locked, available, zero, LedgerReason.BET_DEBIT, KEY, group, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                LedgerEntry.pair(
                    locked, available, negative, LedgerReason.BET_DEBIT, KEY, group, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("TransferLeg rejects nulls so caller cannot land a half-built reference")
  void transferLeg_null_guards() {
    assertThatThrownBy(() -> new TransferLeg(null, BalanceBucket.AVAILABLE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferLeg(USER, null)).isInstanceOf(NullPointerException.class);
  }
}
