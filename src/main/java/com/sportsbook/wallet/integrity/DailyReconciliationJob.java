package com.sportsbook.wallet.integrity;

import com.sportsbook.wallet.domain.Account;
import com.sportsbook.wallet.domain.BalanceBucket;
import com.sportsbook.wallet.persistence.AccountRepository;
import com.sportsbook.wallet.persistence.LedgerEntryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Once-a-day deep reconciliation that double-checks two invariants the per-operation listener
 * cannot see in isolation:
 *
 * <ul>
 *   <li>System-wide DEBIT − CREDIT across all ledger entries equals zero. The per-operation
 *       listener verifies each pair locally; this job catches drift that would only show up across
 *       operations (a missing entry, a hand-rolled SQL fix).
 *   <li>Each {@link Account}'s snapshot balances match the signed sum of ledger entries on the
 *       corresponding (account, bucket).
 * </ul>
 *
 * <p>Runs at {@code 03:00:00} server time by default (configurable via {@code
 * wallet.reconciliation.cron}). The application timezone is UTC (ADR-0003), so this is 03:00 UTC.
 *
 * <p>Failures do not roll back — by reconciliation time the breach has already landed. The job logs
 * at ERROR, records the breach via {@code wallet.reconciliation.violations} Micrometer counter, and
 * publishes the system-wide net as the {@code wallet.reconciliation.net} gauge so Grafana can plot
 * the drift over time.
 */
@Component
public class DailyReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(DailyReconciliationJob.class);

  private final AccountRepository accountRepo;
  private final LedgerEntryRepository ledgerRepo;
  private final Counter runs;
  private final Counter violations;
  private final AtomicLong systemNet = new AtomicLong();

  public DailyReconciliationJob(
      AccountRepository accountRepo, LedgerEntryRepository ledgerRepo, MeterRegistry meters) {
    this.accountRepo = accountRepo;
    this.ledgerRepo = ledgerRepo;
    this.runs = meters.counter("wallet.reconciliation.runs");
    this.violations = meters.counter("wallet.reconciliation.violations");
    meters.gauge("wallet.reconciliation.net", systemNet);
  }

  @Scheduled(cron = "${wallet.reconciliation.cron:0 0 3 * * *}")
  @Transactional(readOnly = true)
  public void reconcile() {
    runs.increment();
    long net = ledgerRepo.netSumAll();
    systemNet.set(net);
    if (net != 0L) {
      violations.increment();
      log.error("RECONCILIATION DRIFT: system-wide ledger DEBIT − CREDIT = {} (expected 0)", net);
    }

    for (Account account : accountRepo.findAll()) {
      verifyBucket(account, BalanceBucket.AVAILABLE, account.available().amount());
      verifyBucket(account, BalanceBucket.LOCKED, account.locked().amount());
    }
  }

  private void verifyBucket(Account account, BalanceBucket bucket, long snapshotAmount) {
    long ledgerNet = ledgerRepo.netByAccountAndBucket(account.userId(), bucket);
    if (ledgerNet != snapshotAmount) {
      violations.increment();
      log.error(
          "RECONCILIATION DRIFT: account {} bucket {} snapshot = {} but ledger sum = {}",
          account.userId(),
          bucket,
          snapshotAmount,
          ledgerNet);
    }
  }
}
