package com.sportsbook.wallet.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.BalanceBucket;
import com.sportsbook.wallet.domain.LedgerEntry;
import com.sportsbook.wallet.domain.LedgerEntry.Pair;
import com.sportsbook.wallet.domain.LedgerEntry.TransferLeg;
import com.sportsbook.wallet.domain.LedgerReason;
import com.sportsbook.wallet.infrastructure.id.UuidV7;
import com.sportsbook.wallet.persistence.LedgerEntryRepository;
import com.sportsbook.wallet.service.WalletService;
import com.sportsbook.wallet.service.command.DebitCommand;
import com.sportsbook.wallet.service.command.DepositCommand;
import com.sportsbook.wallet.service.command.OpenAccountCommand;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests both invariant guards by injecting a deliberately-bad pair and watching the Micrometer
 * counters tick. Kafka auto-config is excluded — neither the listener nor the reconciliation job
 * touches Kafka, and dropping it shaves a few seconds off the test.
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
@Testcontainers
@ActiveProfiles("test")
class IntegrityIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired WalletService walletService;
  @Autowired LedgerEntryRepository ledgerRepo;
  @Autowired InvariantChecker invariantChecker;
  @Autowired DailyReconciliationJob reconciliationJob;
  @Autowired MeterRegistry meters;
  @Autowired Clock clock;

  private UUID userId;

  @BeforeEach
  void seed() {
    userId = UUID.randomUUID();
    walletService.openAccount(new OpenAccountCommand(userId, Currency.KRW));
    walletService.deposit(new DepositCommand(userId, krw(10_000), key("dep")));
  }

  @Test
  @DisplayName("normal debit increments wallet.invariant.checks but not violations")
  void normal_debit_does_not_violate() {
    double checksBefore = meters.counter("wallet.invariant.checks").count();
    double violationsBefore = meters.counter("wallet.invariant.violations").count();

    walletService.debit(new DebitCommand(userId, krw(2_000), key("bet")));

    assertThat(meters.counter("wallet.invariant.checks").count() - checksBefore)
        .as("at least one check fired across deposit + debit")
        .isGreaterThanOrEqualTo(1.0);
    assertThat(meters.counter("wallet.invariant.violations").count()).isEqualTo(violationsBefore);
  }

  @Test
  @DisplayName("orphan DEBIT entry trips InvariantChecker when verify() runs on its group")
  void orphan_pair_increments_violations() {
    double violationsBefore = meters.counter("wallet.invariant.violations").count();
    UUID syntheticGroup = injectOrphanDebit();

    // The @TransactionalEventListener(AFTER_COMMIT) wiring is exercised by the happy-path test
    // above (debit success); here we drive the verify logic directly so the synthetic group
    // does not need an enclosing transaction to dispatch.
    invariantChecker.verify(new OperationCommitted(syntheticGroup));

    assertThat(meters.counter("wallet.invariant.violations").count() - violationsBefore)
        .as("listener flagged the one-entry operation_group")
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("reconciliation flags an injected drift via wallet.reconciliation.violations")
  void reconciliation_detects_drift() {
    double violationsBefore = meters.counter("wallet.reconciliation.violations").count();
    injectOrphanDebit();

    reconciliationJob.reconcile();

    assertThat(meters.counter("wallet.reconciliation.violations").count() - violationsBefore)
        .as("system-wide net != 0 ⇒ at least one violation counted")
        .isGreaterThanOrEqualTo(1.0);
  }

  /**
   * Writes a single DEBIT row (no matched CREDIT) so the operation_group fails both the size and
   * the side-balance check.
   */
  @Transactional
  UUID injectOrphanDebit() {
    UUID syntheticGroup = UuidV7.generate();
    IdempotencyKey syntheticKey = key("orphan");
    Pair pair =
        LedgerEntry.pair(
            new TransferLeg(userId, BalanceBucket.LOCKED),
            new TransferLeg(userId, BalanceBucket.AVAILABLE),
            krw(1_000),
            LedgerReason.BET_DEBIT,
            syntheticKey,
            syntheticGroup,
            clock.instant());
    ledgerRepo.save(pair.debit()); // intentionally drop the credit half
    return syntheticGroup;
  }

  private static IdempotencyKey key(String prefix) {
    return IdempotencyKey.of(prefix + "-" + UUID.randomUUID());
  }

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }
}
