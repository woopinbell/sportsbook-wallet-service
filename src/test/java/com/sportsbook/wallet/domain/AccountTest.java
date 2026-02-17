package com.sportsbook.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.error.CurrencyMismatchException;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000aaaa");
  private static final Instant T0 = Instant.parse("2026-05-28T07:00:00Z");
  private static final Instant T1 = Instant.parse("2026-05-28T07:00:01Z");

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }

  @Test
  @DisplayName("opens with zero balances on both buckets and currency pinned")
  void opens_zero_balance() {
    Account a = Account.openFor(USER, Currency.KRW, T0);

    assertThat(a.userId()).isEqualTo(USER);
    assertThat(a.currency()).isEqualTo(Currency.KRW);
    assertThat(a.available()).isEqualTo(krw(0));
    assertThat(a.locked()).isEqualTo(krw(0));
    assertThat(a.total()).isEqualTo(krw(0));
    assertThat(a.createdAt()).isEqualTo(T0);
    assertThat(a.updatedAt()).isEqualTo(T0);
  }

  @Nested
  @DisplayName("moveAvailableToLocked — bet stake staging")
  class StakeStaging {

    @Test
    void moves_funds_and_keeps_total_invariant() {
      Account a = funded(10_000);

      a.moveAvailableToLocked(krw(3_000), T1);

      assertThat(a.available()).isEqualTo(krw(7_000));
      assertThat(a.locked()).isEqualTo(krw(3_000));
      assertThat(a.total()).isEqualTo(krw(10_000));
      assertThat(a.updatedAt()).isEqualTo(T1);
    }

    @Test
    void rejects_when_amount_exceeds_available() {
      Account a = funded(2_000);

      assertThatThrownBy(() -> a.moveAvailableToLocked(krw(2_001), T1))
          .isInstanceOf(InsufficientBalanceException.class)
          .extracting("requested", "available")
          .containsExactly(krw(2_001), krw(2_000));
    }

    @Test
    void rejects_zero_or_negative_amounts() {
      Account a = funded(10);

      assertThatThrownBy(() -> a.moveAvailableToLocked(krw(0), T1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> a.moveAvailableToLocked(krw(-1), T1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_currency_mismatch() {
      Account a = funded(10);
      Money usd = new Money(1, Currency.USD);

      assertThatThrownBy(() -> a.moveAvailableToLocked(usd, T1))
          .isInstanceOf(CurrencyMismatchException.class);
    }
  }

  @Nested
  @DisplayName("moveLockedToAvailable — refund / payout step 1")
  class LockedReturn {

    @Test
    void moves_back_and_preserves_total() {
      Account a = funded(0);
      a.increaseAvailable(krw(5_000), T0);
      a.moveAvailableToLocked(krw(2_000), T0);

      a.moveLockedToAvailable(krw(2_000), T1);

      assertThat(a.available()).isEqualTo(krw(5_000));
      assertThat(a.locked()).isEqualTo(krw(0));
      assertThat(a.total()).isEqualTo(krw(5_000));
    }

    @Test
    void rejects_when_locked_lower_than_amount() {
      Account a = funded(10);
      a.moveAvailableToLocked(krw(5), T0);

      assertThatThrownBy(() -> a.moveLockedToAvailable(krw(6), T1))
          .isInstanceOf(InsufficientBalanceException.class);
    }
  }

  @Nested
  @DisplayName("increase/decrease available — deposit / withdraw")
  class External {

    @Test
    void deposit_grows_available() {
      Account a = Account.openFor(USER, Currency.KRW, T0);
      a.increaseAvailable(krw(123_456), T1);

      assertThat(a.available()).isEqualTo(krw(123_456));
      assertThat(a.locked()).isEqualTo(krw(0));
      assertThat(a.updatedAt()).isEqualTo(T1);
    }

    @Test
    void withdraw_shrinks_available() {
      Account a = funded(1_000);
      a.decreaseAvailable(krw(400), T1);
      assertThat(a.available()).isEqualTo(krw(600));
    }

    @Test
    void withdraw_rejects_overdraft() {
      Account a = funded(100);
      assertThatThrownBy(() -> a.decreaseAvailable(krw(101), T1))
          .isInstanceOf(InsufficientBalanceException.class);
    }
  }

  @Nested
  @DisplayName("forfeitLocked — lost bet")
  class Forfeit {

    @Test
    void shrinks_locked_only_no_change_to_available() {
      Account a = funded(0);
      a.increaseAvailable(krw(5_000), T0);
      a.moveAvailableToLocked(krw(2_000), T0);

      a.forfeitLocked(krw(2_000), T1);

      assertThat(a.available()).isEqualTo(krw(3_000));
      assertThat(a.locked()).isEqualTo(krw(0));
      // total dropped by the forfeit amount — the stake left the user's account toward HOUSE.
      assertThat(a.total()).isEqualTo(krw(3_000));
    }

    @Test
    void rejects_overdraft() {
      Account a = funded(0);
      a.increaseAvailable(krw(100), T0);
      a.moveAvailableToLocked(krw(50), T0);

      assertThatThrownBy(() -> a.forfeitLocked(krw(60), T1))
          .isInstanceOf(InsufficientBalanceException.class);
    }
  }

  private static Account funded(long krwMinor) {
    Account a = Account.openFor(USER, Currency.KRW, T0);
    if (krwMinor > 0L) {
      a.increaseAvailable(krw(krwMinor), T0);
    }
    return a;
  }
}
