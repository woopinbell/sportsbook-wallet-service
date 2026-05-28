package com.sportsbook.wallet.domain;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.error.CurrencyMismatchException;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User account aggregate — the only entity in the system whose balance fields are mutated under a
 * pessimistic row lock (ADR-0005). The dual-balance model (available + locked) lets the betting
 * flow stage stake out of the user's spendable funds without leaving the account, which keeps the
 * money traceable for admin actions (force-refund of an open exposure, etc.) without an inter-table
 * transfer.
 *
 * <p>Both buckets are denominated in the same {@link Currency} — one account = one currency in V1.
 * The DB enforces this with a check constraint; the domain layer enforces it on every mutation via
 * {@link #requireSameCurrency(Money)}.
 *
 * <p>All public mutators advance {@code updatedAt} and rely on JPA optimistic locking through
 * {@link Version} for the non-balance race (e.g. concurrent metadata edits). Balance arithmetic
 * uses {@link Math#addExact} / {@link Math#subtractExact} so silent overflow is impossible.
 */
@Entity
@Table(name = "account")
public class Account {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amount",
        column = @Column(name = "available_amount", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "available_currency", nullable = false, length = 3))
  })
  private EmbeddedMoney available;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "amount", column = @Column(name = "locked_amount", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "locked_currency", nullable = false, length = 3))
  })
  private EmbeddedMoney locked;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Account() {
    // Required by JPA.
  }

  private Account(UUID userId, Currency currency, Instant now) {
    this.userId = Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(now, "now");
    this.available = new EmbeddedMoney(0L, currency);
    this.locked = new EmbeddedMoney(0L, currency);
    this.createdAt = now;
    this.updatedAt = now;
  }

  /** Opens a fresh account for {@code userId} with both buckets at zero. */
  public static Account openFor(UUID userId, Currency currency, Instant now) {
    return new Account(userId, currency, now);
  }

  public UUID userId() {
    return userId;
  }

  public Currency currency() {
    return available.currency();
  }

  public Money available() {
    return available.toMoney();
  }

  public Money locked() {
    return locked.toMoney();
  }

  public Money total() {
    return new Money(Math.addExact(available.amount(), locked.amount()), currency());
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  /**
   * Stages {@code amount} from the available bucket into the locked bucket. Used when a bet slip is
   * accepted: the stake is held against the open exposure until settlement decides where it goes.
   *
   * @throws InsufficientBalanceException if {@code amount > available}
   * @throws CurrencyMismatchException if {@code amount.currency() != account.currency()}
   */
  public void moveAvailableToLocked(Money amount, Instant now) {
    requirePositive(amount);
    requireSameCurrency(amount);
    if (available.amount() < amount.amount()) {
      throw new InsufficientBalanceException(userId, amount, available.toMoney());
    }
    available =
        new EmbeddedMoney(Math.subtractExact(available.amount(), amount.amount()), currency());
    locked = new EmbeddedMoney(Math.addExact(locked.amount(), amount.amount()), currency());
    updatedAt = now;
  }

  /**
   * Releases {@code amount} from the locked bucket back to available. Used for void settlements
   * (stake refund) or as the first step of a winning payout (stake returns before profit is added).
   */
  public void moveLockedToAvailable(Money amount, Instant now) {
    requirePositive(amount);
    requireSameCurrency(amount);
    if (locked.amount() < amount.amount()) {
      throw new InsufficientBalanceException(userId, amount, locked.toMoney());
    }
    locked = new EmbeddedMoney(Math.subtractExact(locked.amount(), amount.amount()), currency());
    available = new EmbeddedMoney(Math.addExact(available.amount(), amount.amount()), currency());
    updatedAt = now;
  }

  /** Adds external funds to the available bucket (deposit, profit payout from house). */
  public void increaseAvailable(Money amount, Instant now) {
    requirePositive(amount);
    requireSameCurrency(amount);
    available = new EmbeddedMoney(Math.addExact(available.amount(), amount.amount()), currency());
    updatedAt = now;
  }

  /** Removes funds from the available bucket (withdrawal). */
  public void decreaseAvailable(Money amount, Instant now) {
    requirePositive(amount);
    requireSameCurrency(amount);
    if (available.amount() < amount.amount()) {
      throw new InsufficientBalanceException(userId, amount, available.toMoney());
    }
    available =
        new EmbeddedMoney(Math.subtractExact(available.amount(), amount.amount()), currency());
    updatedAt = now;
  }

  /**
   * Forfeits {@code amount} from the locked bucket without returning it to available — used when a
   * lost bet's stake is paid to the house.
   */
  public void forfeitLocked(Money amount, Instant now) {
    requirePositive(amount);
    requireSameCurrency(amount);
    if (locked.amount() < amount.amount()) {
      throw new InsufficientBalanceException(userId, amount, locked.toMoney());
    }
    locked = new EmbeddedMoney(Math.subtractExact(locked.amount(), amount.amount()), currency());
    updatedAt = now;
  }

  private void requireSameCurrency(Money amount) {
    if (amount.currency() != currency()) {
      throw new CurrencyMismatchException(currency(), amount.currency());
    }
  }

  private static void requirePositive(Money amount) {
    if (amount.amount() <= 0L) {
      throw new IllegalArgumentException(
          "Amount must be strictly positive (got " + amount.amount() + ")");
    }
  }
}
