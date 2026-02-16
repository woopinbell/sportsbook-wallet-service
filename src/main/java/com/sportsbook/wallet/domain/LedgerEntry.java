package com.sportsbook.wallet.domain;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.infrastructure.id.UuidV7;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only journal row for the double-entry ledger. Every wallet operation writes a matched pair
 * of entries that share an {@code operationGroupId} and an {@code idempotencyKey}: one {@link
 * LedgerSide#DEBIT} on the destination (account, bucket), one {@link LedgerSide#CREDIT} on the
 * source.
 *
 * <p>Two unique constraints carry the idempotency + structural guarantees (ADR-0005):
 *
 * <ul>
 *   <li>{@code (idempotency_key, side)} — at most one DEBIT and one CREDIT entry per
 *       caller-supplied key. A retry under the same key cannot create extra rows.
 *   <li>{@code (operation_group_id, side)} — same shape on the internal group ID; a defensive
 *       second layer in case the application reuses a group across keys by mistake.
 * </ul>
 *
 * <p>The unique constraint on {@code idempotency_key} alone would not work because both entries of
 * a pair share that key — adding {@code side} to the tuple is what lets the matched-pair pattern
 * coexist with strong DB-level dedup.
 *
 * <p>Once written, a row is never updated or deleted. All fields are marked {@code updatable =
 * false} and the type exposes no setters.
 */
@Entity
@Table(
    name = "ledger_entry",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_ledger_entry_idem_side",
          columnNames = {"idempotency_key", "side"}),
      @UniqueConstraint(
          name = "uk_ledger_entry_group_side",
          columnNames = {"operation_group_id", "side"})
    },
    indexes = {
      @Index(name = "ix_ledger_entry_account_created", columnList = "account_id, created_at"),
      @Index(name = "ix_ledger_entry_idem_key", columnList = "idempotency_key")
    })
public class LedgerEntry {

  @Id
  @Column(name = "entry_id", nullable = false, updatable = false)
  private UUID entryId;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Enumerated(EnumType.STRING)
  @Column(name = "bucket", nullable = false, length = 16, updatable = false)
  private BalanceBucket bucket;

  @Enumerated(EnumType.STRING)
  @Column(name = "side", nullable = false, length = 6, updatable = false)
  private LedgerSide side;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amount",
        column = @Column(name = "amount", nullable = false, updatable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "currency", nullable = false, length = 3, updatable = false))
  })
  private EmbeddedMoney money;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 16, updatable = false)
  private LedgerReason reason;

  @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
  private String idempotencyKey;

  @Column(name = "operation_group_id", nullable = false, updatable = false)
  private UUID operationGroupId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LedgerEntry() {
    // Required by JPA.
  }

  // Suppressed because the matched-pair factories below already pin every value-object parameter;
  // a builder would obscure the call site without changing the actual fan-in.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private LedgerEntry(
      UUID entryId,
      UUID accountId,
      BalanceBucket bucket,
      LedgerSide side,
      Money money,
      LedgerReason reason,
      IdempotencyKey idempotencyKey,
      UUID operationGroupId,
      Instant createdAt) {
    if (money.amount() <= 0L) {
      throw new IllegalArgumentException(
          "Ledger amount must be strictly positive (got " + money.amount() + ")");
    }
    this.entryId = Objects.requireNonNull(entryId, "entryId");
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
    this.side = Objects.requireNonNull(side, "side");
    this.money = EmbeddedMoney.of(money);
    this.reason = Objects.requireNonNull(reason, "reason");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").value();
    this.operationGroupId = Objects.requireNonNull(operationGroupId, "operationGroupId");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /**
   * Builds the matched (debit, credit) pair that records a transfer from one (account, bucket) to
   * another. Returned in the order ({@code debitEntry}, {@code creditEntry}) so callers can rely on
   * positional destructuring.
   */
  public static Pair pair(
      TransferLeg destination,
      TransferLeg source,
      Money amount,
      LedgerReason reason,
      IdempotencyKey idempotencyKey,
      UUID operationGroupId,
      Instant now) {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(source, "source");
    LedgerEntry debit =
        new LedgerEntry(
            UuidV7.generate(),
            destination.accountId(),
            destination.bucket(),
            LedgerSide.DEBIT,
            amount,
            reason,
            idempotencyKey,
            operationGroupId,
            now);
    LedgerEntry credit =
        new LedgerEntry(
            UuidV7.generate(),
            source.accountId(),
            source.bucket(),
            LedgerSide.CREDIT,
            amount,
            reason,
            idempotencyKey,
            operationGroupId,
            now);
    return new Pair(debit, credit);
  }

  public UUID entryId() {
    return entryId;
  }

  public UUID accountId() {
    return accountId;
  }

  public BalanceBucket bucket() {
    return bucket;
  }

  public LedgerSide side() {
    return side;
  }

  public Money money() {
    return money.toMoney();
  }

  public LedgerReason reason() {
    return reason;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public UUID operationGroupId() {
    return operationGroupId;
  }

  public Instant createdAt() {
    return createdAt;
  }

  /** Reference to a specific bucket inside an account, used as a transfer endpoint. */
  public record TransferLeg(UUID accountId, BalanceBucket bucket) {
    public TransferLeg {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(bucket, "bucket");
    }
  }

  /** Matched (debit, credit) pair returned by {@link #pair}. */
  public record Pair(LedgerEntry debit, LedgerEntry credit) {
    public Pair {
      Objects.requireNonNull(debit, "debit");
      Objects.requireNonNull(credit, "credit");
    }
  }
}
