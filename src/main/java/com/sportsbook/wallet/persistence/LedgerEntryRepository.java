package com.sportsbook.wallet.persistence;

import com.sportsbook.wallet.domain.BalanceBucket;
import com.sportsbook.wallet.domain.LedgerEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link LedgerEntry}. The table is append-only by design; this
 * repository never updates or deletes rows.
 *
 * <p>The two key reads, {@link #findByIdempotencyKey(String)} and {@link
 * #existsByIdempotencyKey(String)}, support the idempotency fast path described in ADR-0005: a
 * retry under the same caller key looks up the original matched pair and returns the existing
 * outcome rather than re-executing the side effect. {@code findByAccountIdOrderByCreatedAtAsc} is
 * the per-account time-ordered scan the invariant checker and the daily reconciliation job use to
 * verify that bucket-level entries reconcile against the current snapshot.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

  /** Returns both entries of a matched pair, or empty if the key has not been processed. */
  List<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

  /** Cheap existence probe — used by the Redis-miss fast path before falling through to insert. */
  boolean existsByIdempotencyKey(String idempotencyKey);

  /** All entries for a single account, oldest first. */
  List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

  /** Both entries of a matched pair under one operation_group_id. */
  List<LedgerEntry> findByOperationGroupId(UUID operationGroupId);

  /**
   * Net of {@code DEBIT − CREDIT} amounts for one (account, bucket). Should reconcile against the
   * matching {@link com.sportsbook.wallet.domain.Account} bucket balance — used by the daily
   * reconciliation job.
   */
  @Query(
      "select coalesce(sum(case when l.side = com.sportsbook.wallet.domain.LedgerSide.DEBIT "
          + "then l.money.amount else -l.money.amount end), 0) "
          + "from LedgerEntry l "
          + "where l.accountId = :accountId and l.bucket = :bucket")
  long netByAccountAndBucket(
      @Param("accountId") UUID accountId, @Param("bucket") BalanceBucket bucket);

  /** System-wide {@code DEBIT − CREDIT} sum; should always be zero (ADR-0005). */
  @Query(
      "select coalesce(sum(case when l.side = com.sportsbook.wallet.domain.LedgerSide.DEBIT "
          + "then l.money.amount else -l.money.amount end), 0) "
          + "from LedgerEntry l")
  long netSumAll();
}
