package com.sportsbook.wallet.service;

import com.sportsbook.protocol.event.WalletCreditReason;
import com.sportsbook.protocol.event.WalletDebitFailureReason;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.Account;
import com.sportsbook.wallet.domain.BalanceBucket;
import com.sportsbook.wallet.domain.LedgerEntry;
import com.sportsbook.wallet.domain.LedgerEntry.Pair;
import com.sportsbook.wallet.domain.LedgerEntry.TransferLeg;
import com.sportsbook.wallet.domain.LedgerReason;
import com.sportsbook.wallet.domain.SystemAccountIds;
import com.sportsbook.wallet.domain.error.AccountNotFoundException;
import com.sportsbook.wallet.domain.error.CurrencyMismatchException;
import com.sportsbook.wallet.domain.error.IdempotencyConflictException;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import com.sportsbook.wallet.infrastructure.id.UuidV7;
import com.sportsbook.wallet.integrity.OperationCommitted;
import com.sportsbook.wallet.outbox.OutboxEvent;
import com.sportsbook.wallet.outbox.OutboxEventRepository;
import com.sportsbook.wallet.outbox.WalletEventFactory;
import com.sportsbook.wallet.persistence.AccountRepository;
import com.sportsbook.wallet.persistence.LedgerEntryRepository;
import com.sportsbook.wallet.service.command.CreditCommand;
import com.sportsbook.wallet.service.command.DebitCommand;
import com.sportsbook.wallet.service.command.DepositCommand;
import com.sportsbook.wallet.service.command.OpenAccountCommand;
import com.sportsbook.wallet.service.command.WithdrawCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Core write-side application service. Hosts the idempotent transfer primitive used by every money
 * operation; the public methods translate the V1 vocabulary (deposit / withdraw / debit / credit)
 * into source/destination legs over that primitive.
 *
 * <p>Idempotency contract (ADR-0005):
 *
 * <ol>
 *   <li><b>Fast path</b> — consult the Redis cache; if the key is present, fetch the matched ledger
 *       pair from the DB, validate that the retry payload matches, and return the cached outcome.
 *   <li><b>Write path</b> — open a programmatic transaction, acquire the {@code PESSIMISTIC_WRITE}
 *       lock on the user account, mutate balances, insert the matched (debit, credit) pair, then
 *       commit. After commit, write the operation_group_id into Redis with a 24h TTL.
 *   <li><b>Race-loser path</b> — when the matched-pair {@code UNIQUE(idempotency_key, side)}
 *       constraint fires because a concurrent request with the same key won, the original txn is
 *       rolled back; a follow-up read fetches the winning pair and returns its outcome. The retry's
 *       payload is validated against the original; mismatched payload surfaces as {@link
 *       IdempotencyConflictException} → HTTP 409.
 * </ol>
 *
 * <p>The transfer primitive itself runs inside a {@link TransactionTemplate} block rather than via
 * {@code @Transactional} on the public method because catching {@link
 * DataIntegrityViolationException} on the public boundary lets the follow-up lookup run in a fresh
 * transaction; with method-level {@code @Transactional}, the catch would execute against a
 * rollback-only transaction.
 */
@Service
public class WalletService {

  private final AccountRepository accountRepo;
  private final LedgerEntryRepository ledgerRepo;
  private final OutboxEventRepository outboxRepo;
  private final WalletEventFactory events;
  private final IdempotencyCache cache;
  private final TransactionTemplate writeTx;
  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;

  // Seven dependencies but each owns a distinct concern (DB, outbox, event shape, cache, txn,
  // clock, domain-event bus). Bundling them behind a holder would just push the parameter
  // pressure one layer deeper without easing the call site.
  @SuppressWarnings("checkstyle:ParameterNumber")
  public WalletService(
      AccountRepository accountRepo,
      LedgerEntryRepository ledgerRepo,
      OutboxEventRepository outboxRepo,
      WalletEventFactory events,
      IdempotencyCache cache,
      TransactionTemplate writeTx,
      Clock clock,
      ApplicationEventPublisher eventPublisher) {
    this.accountRepo = accountRepo;
    this.ledgerRepo = ledgerRepo;
    this.outboxRepo = outboxRepo;
    this.events = events;
    this.cache = cache;
    this.writeTx = writeTx;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
  }

  // ---------------------------------------------------------------------------------------------
  // Account lifecycle (no idempotency key — userId itself is the natural key).
  // ---------------------------------------------------------------------------------------------

  public Account openAccount(OpenAccountCommand cmd) {
    Optional<Account> existing = accountRepo.findById(cmd.userId());
    if (existing.isPresent()) {
      Account a = existing.get();
      if (a.currency() != cmd.currency()) {
        throw new CurrencyMismatchException(a.currency(), cmd.currency());
      }
      return a;
    }
    Account fresh = Account.openFor(cmd.userId(), cmd.currency(), clock.instant());
    try {
      return writeTx.execute(status -> accountRepo.save(fresh));
    } catch (DataIntegrityViolationException raceLost) {
      Account winner =
          accountRepo
              .findById(cmd.userId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Race on openAccount left no row for " + cmd.userId(), raceLost));
      if (winner.currency() != cmd.currency()) {
        throw new CurrencyMismatchException(winner.currency(), cmd.currency());
      }
      return winner;
    }
  }

  public Account requireAccount(UUID userId) {
    return accountRepo.findById(userId).orElseThrow(() -> new AccountNotFoundException(userId));
  }

  // ---------------------------------------------------------------------------------------------
  // Public V1 operations — each composes the transfer primitive with the right legs.
  // ---------------------------------------------------------------------------------------------

  public WalletOperationResult deposit(DepositCommand cmd) {
    return runIdempotent(
        cmd.idempotencyKey(),
        cmd.userId(),
        cmd.amount(),
        LedgerReason.DEPOSIT,
        () -> {
          Account account = lockAccount(cmd.userId(), cmd.amount());
          Instant now = clock.instant();
          account.increaseAvailable(cmd.amount(), now);
          return writePair(
              new TransferLeg(cmd.userId(), BalanceBucket.AVAILABLE),
              new TransferLeg(SystemAccountIds.EXTERNAL_PAYMENT, BalanceBucket.AVAILABLE),
              cmd.amount(),
              LedgerReason.DEPOSIT,
              cmd.idempotencyKey(),
              cmd.userId(),
              now);
        });
  }

  public WalletOperationResult withdraw(WithdrawCommand cmd) {
    return runIdempotent(
        cmd.idempotencyKey(),
        cmd.userId(),
        cmd.amount(),
        LedgerReason.WITHDRAW,
        () -> {
          Account account = lockAccount(cmd.userId(), cmd.amount());
          Instant now = clock.instant();
          account.decreaseAvailable(cmd.amount(), now);
          return writePair(
              new TransferLeg(SystemAccountIds.EXTERNAL_PAYMENT, BalanceBucket.AVAILABLE),
              new TransferLeg(cmd.userId(), BalanceBucket.AVAILABLE),
              cmd.amount(),
              LedgerReason.WITHDRAW,
              cmd.idempotencyKey(),
              cmd.userId(),
              now);
        });
  }

  public WalletOperationResult debit(DebitCommand cmd) {
    try {
      return runIdempotent(
          cmd.idempotencyKey(),
          cmd.userId(),
          cmd.amount(),
          LedgerReason.BET_DEBIT,
          () -> {
            Account account = lockAccount(cmd.userId(), cmd.amount());
            Instant now = clock.instant();
            account.moveAvailableToLocked(cmd.amount(), now);
            WalletOperationResult result =
                writePair(
                    new TransferLeg(cmd.userId(), BalanceBucket.LOCKED),
                    new TransferLeg(cmd.userId(), BalanceBucket.AVAILABLE),
                    cmd.amount(),
                    LedgerReason.BET_DEBIT,
                    cmd.idempotencyKey(),
                    cmd.userId(),
                    now);
            outboxRepo.save(
                events.debited(
                    cmd.userId(),
                    cmd.amount(),
                    cmd.idempotencyKey(),
                    result.operationGroupId(),
                    now));
            return result;
          });
    } catch (InsufficientBalanceException e) {
      publishDebitFailure(cmd, WalletDebitFailureReason.INSUFFICIENT_BALANCE);
      throw e;
    } catch (AccountNotFoundException e) {
      publishDebitFailure(cmd, WalletDebitFailureReason.ACCOUNT_NOT_FOUND);
      throw e;
    } catch (CurrencyMismatchException e) {
      publishDebitFailure(cmd, WalletDebitFailureReason.CURRENCY_MISMATCH);
      throw e;
    }
  }

  public WalletOperationResult credit(CreditCommand cmd) {
    LedgerReason reason =
        cmd.source() == CreditCommand.Source.USER_LOCKED
            ? LedgerReason.BET_REFUND
            : LedgerReason.BET_PAYOUT;
    WalletCreditReason avroReason =
        cmd.source() == CreditCommand.Source.USER_LOCKED
            ? WalletCreditReason.REFUND
            : WalletCreditReason.PAYOUT;
    return runIdempotent(
        cmd.idempotencyKey(),
        cmd.userId(),
        cmd.amount(),
        reason,
        () -> {
          Account account = lockAccount(cmd.userId(), cmd.amount());
          Instant now = clock.instant();
          TransferLeg source;
          if (cmd.source() == CreditCommand.Source.USER_LOCKED) {
            account.moveLockedToAvailable(cmd.amount(), now);
            source = new TransferLeg(cmd.userId(), BalanceBucket.LOCKED);
          } else {
            account.increaseAvailable(cmd.amount(), now);
            source = new TransferLeg(SystemAccountIds.HOUSE, BalanceBucket.AVAILABLE);
          }
          WalletOperationResult result =
              writePair(
                  new TransferLeg(cmd.userId(), BalanceBucket.AVAILABLE),
                  source,
                  cmd.amount(),
                  reason,
                  cmd.idempotencyKey(),
                  cmd.userId(),
                  now);
          outboxRepo.save(
              events.credited(
                  cmd.userId(),
                  cmd.amount(),
                  cmd.idempotencyKey(),
                  result.operationGroupId(),
                  avroReason,
                  now));
          return result;
        });
  }

  /**
   * Writes the debit-failure event in a fresh transaction so the original rolled-back attempt does
   * not erase it. The exception that triggered this is re-raised by the caller.
   */
  private void publishDebitFailure(DebitCommand cmd, WalletDebitFailureReason reason) {
    Instant now = clock.instant();
    OutboxEvent event =
        events.debitFailed(cmd.userId(), cmd.amount(), cmd.idempotencyKey(), reason, now);
    writeTx.execute(
        status -> {
          outboxRepo.save(event);
          return null;
        });
  }

  // ---------------------------------------------------------------------------------------------
  // Shared idempotent execution skeleton.
  // ---------------------------------------------------------------------------------------------

  // Mutable amount + reason participate in the retry payload check, so a single helper covers all
  // four operations without losing the per-operation validation.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private WalletOperationResult runIdempotent(
      IdempotencyKey key,
      UUID userId,
      Money amount,
      LedgerReason expectedReason,
      Supplier<WalletOperationResult> action) {

    // Fast path: cache says we already processed this key.
    Optional<UUID> cachedGroup = cache.lookupOperationGroup(key);
    if (cachedGroup.isPresent()) {
      Optional<WalletOperationResult> existing = loadExisting(key);
      if (existing.isPresent()) {
        validateRetryMatches(existing.get(), userId, amount, expectedReason, key);
        return existing.get();
      }
      // Cache says yes but DB says no — TTL race or test fixture. Fall through to write path; the
      // DB unique constraint will resolve anything actually in flight.
    }

    try {
      WalletOperationResult result = writeTx.execute(status -> action.get());
      cache.markProcessed(key, result.operationGroupId());
      return result;
    } catch (DataIntegrityViolationException raceLost) {
      Optional<WalletOperationResult> winning = loadExisting(key);
      if (winning.isEmpty()) {
        throw raceLost;
      }
      validateRetryMatches(winning.get(), userId, amount, expectedReason, key);
      return winning.get();
    }
  }

  private Account lockAccount(UUID userId, Money amount) {
    Account account =
        accountRepo
            .findByUserIdForUpdate(userId)
            .orElseThrow(() -> new AccountNotFoundException(userId));
    if (account.currency() != amount.currency()) {
      throw new CurrencyMismatchException(account.currency(), amount.currency());
    }
    return account;
  }

  // Six parameters but each value-object — collapsing into a builder would obscure the call site.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private WalletOperationResult writePair(
      TransferLeg destination,
      TransferLeg source,
      Money amount,
      LedgerReason reason,
      IdempotencyKey key,
      UUID userId,
      Instant now) {
    UUID groupId = UuidV7.generate();
    Pair pair = LedgerEntry.pair(destination, source, amount, reason, key, groupId, now);
    ledgerRepo.save(pair.debit());
    ledgerRepo.save(pair.credit());
    eventPublisher.publishEvent(new OperationCommitted(groupId));
    return new WalletOperationResult(groupId, userId, amount, reason, now);
  }

  private Optional<WalletOperationResult> loadExisting(IdempotencyKey key) {
    List<LedgerEntry> entries = ledgerRepo.findByIdempotencyKey(key.value());
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(WalletOperationResult.fromExisting(entries));
  }

  // Five params — symmetric with runIdempotent, splitting would only push the duplication around.
  @SuppressWarnings("checkstyle:ParameterNumber")
  private static void validateRetryMatches(
      WalletOperationResult existing,
      UUID userId,
      Money amount,
      LedgerReason expectedReason,
      IdempotencyKey key) {
    if (!existing.userId().equals(userId)) {
      throw new IdempotencyConflictException(
          key, "userId mismatch (original=" + existing.userId() + ", retry=" + userId + ")");
    }
    if (!existing.amount().equals(amount)) {
      throw new IdempotencyConflictException(
          key, "amount mismatch (original=" + existing.amount() + ", retry=" + amount + ")");
    }
    if (existing.reason() != expectedReason) {
      throw new IdempotencyConflictException(
          key,
          "operation kind mismatch (original="
              + existing.reason()
              + ", retry="
              + expectedReason
              + ")");
    }
  }
}
