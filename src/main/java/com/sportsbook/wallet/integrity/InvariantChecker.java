package com.sportsbook.wallet.integrity;

import com.sportsbook.wallet.domain.LedgerEntry;
import com.sportsbook.wallet.domain.LedgerSide;
import com.sportsbook.wallet.persistence.LedgerEntryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fires after every committed wallet operation and verifies the matched-pair invariant on the
 * operation_group: exactly one {@code DEBIT} and one {@code CREDIT} entry, both with the same
 * currency, and {@code DEBIT − CREDIT = 0} for that group.
 *
 * <p>The check runs on the {@code AFTER_COMMIT} phase, so the transaction that wrote the pair has
 * already landed and the original {@code @Transactional} method has returned. If the check finds a
 * violation we cannot undo it — the listener logs at ERROR, increments {@code
 * wallet.invariant.violations}, and lets the operator follow up. The transaction-time guarantees
 * (DB unique constraints, domain mutators) are the real prevention; this listener exists to make a
 * silent breach impossible.
 */
@Component
public class InvariantChecker {

  private static final Logger log = LoggerFactory.getLogger(InvariantChecker.class);
  private static final int EXPECTED_PAIR_SIZE = 2;

  private final LedgerEntryRepository ledgerRepo;
  private final Counter violations;
  private final Counter checks;

  public InvariantChecker(LedgerEntryRepository ledgerRepo, MeterRegistry meters) {
    this.ledgerRepo = ledgerRepo;
    this.violations = meters.counter("wallet.invariant.violations");
    this.checks = meters.counter("wallet.invariant.checks");
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void verify(OperationCommitted event) {
    checks.increment();
    List<LedgerEntry> entries = ledgerRepo.findByOperationGroupId(event.operationGroupId());

    if (entries.size() != EXPECTED_PAIR_SIZE) {
      violations.increment();
      log.error(
          "LEDGER INVARIANT VIOLATED: operation_group {} has {} entries (expected {})",
          event.operationGroupId(),
          entries.size(),
          EXPECTED_PAIR_SIZE);
      return;
    }

    long net = 0L;
    int debits = 0;
    int credits = 0;
    for (LedgerEntry e : entries) {
      if (e.side() == LedgerSide.DEBIT) {
        net += e.money().amount();
        debits++;
      } else {
        net -= e.money().amount();
        credits++;
      }
    }

    if (debits != 1 || credits != 1) {
      violations.increment();
      log.error(
          "LEDGER INVARIANT VIOLATED: operation_group {} has {} DEBIT and {} CREDIT entries"
              + " (expected exactly 1 of each)",
          event.operationGroupId(),
          debits,
          credits);
      return;
    }

    if (net != 0L) {
      violations.increment();
      log.error(
          "LEDGER INVARIANT VIOLATED: operation_group {} nets to {} (expected 0)",
          event.operationGroupId(),
          net);
      return;
    }

    LedgerEntry first = entries.get(0);
    LedgerEntry second = entries.get(1);
    if (first.money().currency() != second.money().currency()) {
      violations.increment();
      log.error(
          "LEDGER INVARIANT VIOLATED: operation_group {} pair carries mismatched currencies"
              + " ({} vs {})",
          event.operationGroupId(),
          first.money().currency(),
          second.money().currency());
    }
  }
}
