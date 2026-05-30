package com.sportsbook.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.Account;
import com.sportsbook.wallet.domain.LedgerEntry;
import com.sportsbook.wallet.domain.LedgerReason;
import com.sportsbook.wallet.domain.LedgerSide;
import com.sportsbook.wallet.domain.error.CurrencyMismatchException;
import com.sportsbook.wallet.domain.error.IdempotencyConflictException;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import com.sportsbook.wallet.persistence.AccountRepository;
import com.sportsbook.wallet.persistence.LedgerEntryRepository;
import com.sportsbook.wallet.service.command.CreditCommand;
import com.sportsbook.wallet.service.command.DebitCommand;
import com.sportsbook.wallet.service.command.DepositCommand;
import com.sportsbook.wallet.service.command.ForfeitCommand;
import com.sportsbook.wallet.service.command.OpenAccountCommand;
import com.sportsbook.wallet.service.command.WithdrawCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration coverage for {@link WalletService} against real PostgreSQL + Redis containers. Kafka
 * autoconfiguration is excluded for this slice — the outbox publisher and its container will land
 * in 작업 4's tests; including Kafka here would add 10+ s of container start without exercising any
 * new path.
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
@Testcontainers
@ActiveProfiles("test")
class WalletServiceIntegrationTest {

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
  @Autowired AccountRepository accountRepo;
  @Autowired LedgerEntryRepository ledgerRepo;

  private UUID userId;

  @BeforeEach
  void seedAccount() {
    userId = UUID.randomUUID();
    walletService.openAccount(new OpenAccountCommand(userId, Currency.KRW));
  }

  private static IdempotencyKey randomKey(String prefix) {
    return IdempotencyKey.of(prefix + "-" + UUID.randomUUID());
  }

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }

  private static long signed(LedgerEntry e) {
    return e.side() == LedgerSide.DEBIT ? e.money().amount() : -e.money().amount();
  }

  @Nested
  @DisplayName("happy path — deposit / withdraw / debit / credit")
  class HappyPath {

    @Test
    void deposit_then_debit_then_credit_keeps_ledger_balanced() {
      walletService.deposit(new DepositCommand(userId, krw(100_000), randomKey("dep")));
      walletService.debit(new DebitCommand(userId, krw(30_000), randomKey("bet")));
      walletService.credit(
          new CreditCommand(
              userId, krw(30_000), CreditCommand.Source.USER_LOCKED, randomKey("ref")));

      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.available()).isEqualTo(krw(100_000));
      assertThat(after.locked()).isEqualTo(krw(0));

      long net =
          ledgerRepo.findAll().stream().mapToLong(WalletServiceIntegrationTest::signed).sum();
      assertThat(net).as("ledger DEBIT − CREDIT must net to zero").isZero();
    }

    @Test
    void withdraw_decreases_available_and_writes_pair_against_external_payment() {
      walletService.deposit(new DepositCommand(userId, krw(50_000), randomKey("dep")));
      WalletOperationResult result =
          walletService.withdraw(new WithdrawCommand(userId, krw(20_000), randomKey("wd")));

      assertThat(result.reason()).isEqualTo(LedgerReason.WITHDRAW);
      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.available()).isEqualTo(krw(30_000));
    }

    @Test
    void credit_from_house_pays_out_profit() {
      walletService.deposit(new DepositCommand(userId, krw(5_000), randomKey("dep")));
      WalletOperationResult result =
          walletService.credit(
              new CreditCommand(
                  userId, krw(7_000), CreditCommand.Source.HOUSE_POOL, randomKey("win")));

      assertThat(result.reason()).isEqualTo(LedgerReason.BET_PAYOUT);
      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.available()).isEqualTo(krw(12_000));
      // ledger sum still zero — HOUSE balances out the user-side DEBIT.
      long net =
          ledgerRepo.findAll().stream().mapToLong(WalletServiceIntegrationTest::signed).sum();
      assertThat(net).isZero();
    }

    @Test
    void forfeit_captures_locked_stake_to_house() {
      walletService.deposit(new DepositCommand(userId, krw(100_000), randomKey("dep")));
      walletService.debit(new DebitCommand(userId, krw(30_000), randomKey("bet")));

      WalletOperationResult result =
          walletService.forfeit(new ForfeitCommand(userId, krw(30_000), randomKey("loss")));

      assertThat(result.reason()).isEqualTo(LedgerReason.BET_FORFEIT);
      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.locked()).as("staked amount captured out of locked").isEqualTo(krw(0));
      assertThat(after.available()).as("available untouched by the forfeit").isEqualTo(krw(70_000));
      // ledger sum still zero — the HOUSE DEBIT balances the user LOCKED credit.
      long net =
          ledgerRepo.findAll().stream().mapToLong(WalletServiceIntegrationTest::signed).sum();
      assertThat(net).isZero();
    }
  }

  @Nested
  @DisplayName("rejection paths — domain errors stop before any ledger write")
  class Rejection {

    @Test
    void debit_above_available_rejects_and_writes_no_entry() {
      walletService.deposit(new DepositCommand(userId, krw(100), randomKey("dep")));

      assertThatThrownBy(
              () -> walletService.debit(new DebitCommand(userId, krw(101), randomKey("over"))))
          .isInstanceOf(InsufficientBalanceException.class);

      assertThat(ledgerRepo.findAll().stream().filter(e -> e.accountId().equals(userId)).count())
          .as("only the original deposit pair should exist on this user")
          .isEqualTo(1L);
    }

    @Test
    void forfeit_above_locked_rejects_and_writes_no_entry() {
      walletService.deposit(new DepositCommand(userId, krw(10_000), randomKey("dep")));
      walletService.debit(new DebitCommand(userId, krw(4_000), randomKey("bet")));

      assertThatThrownBy(
              () -> walletService.forfeit(new ForfeitCommand(userId, krw(5_000), randomKey("over"))))
          .isInstanceOf(InsufficientBalanceException.class);

      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.available()).isEqualTo(krw(6_000));
      assertThat(after.locked()).as("locked stake untouched by the rejected forfeit").isEqualTo(krw(4_000));
    }

    @Test
    void currency_mismatch_rejects() {
      assertThatThrownBy(
              () ->
                  walletService.deposit(
                      new DepositCommand(userId, new Money(1, Currency.USD), randomKey("usd"))))
          .isInstanceOf(CurrencyMismatchException.class);
    }
  }

  @Nested
  @DisplayName("idempotency — exactly-once across retries and races")
  class Idempotent {

    @Test
    void same_key_twice_returns_same_outcome_and_writes_one_pair() {
      walletService.deposit(new DepositCommand(userId, krw(10_000), randomKey("dep")));
      IdempotencyKey key = randomKey("bet");

      WalletOperationResult first = walletService.debit(new DebitCommand(userId, krw(2_000), key));
      WalletOperationResult second = walletService.debit(new DebitCommand(userId, krw(2_000), key));

      assertThat(first.operationGroupId()).isEqualTo(second.operationGroupId());

      List<LedgerEntry> pair = ledgerRepo.findByIdempotencyKey(key.value());
      assertThat(pair).hasSize(2);

      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.locked()).isEqualTo(krw(2_000));
      assertThat(after.available()).isEqualTo(krw(8_000));
    }

    @Test
    void same_key_with_different_amount_surfaces_idempotency_conflict() {
      walletService.deposit(new DepositCommand(userId, krw(10_000), randomKey("dep")));
      IdempotencyKey key = randomKey("bet");

      walletService.debit(new DebitCommand(userId, krw(2_000), key));

      assertThatThrownBy(() -> walletService.debit(new DebitCommand(userId, krw(3_000), key)))
          .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void one_hundred_concurrent_debits_with_same_key_produce_a_single_pair() throws Exception {
      walletService.deposit(new DepositCommand(userId, krw(100_000), randomKey("dep")));
      IdempotencyKey raceKey = randomKey("race");

      int parallelism = 100;
      ExecutorService exec = Executors.newFixedThreadPool(20);
      CountDownLatch start = new CountDownLatch(1);
      List<CompletableFuture<WalletOperationResult>> futures = new ArrayList<>();

      for (int i = 0; i < parallelism; i++) {
        futures.add(
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                  }
                  return walletService.debit(new DebitCommand(userId, krw(1_000), raceKey));
                },
                exec));
      }
      start.countDown();

      List<WalletOperationResult> results;
      try {
        results = collect(futures);
      } finally {
        exec.shutdown();
        exec.awaitTermination(5, TimeUnit.SECONDS);
      }

      Set<UUID> groupIds =
          results.stream().map(WalletOperationResult::operationGroupId).collect(Collectors.toSet());
      assertThat(groupIds).as("all 100 retries collapse to the same operation_group").hasSize(1);

      List<LedgerEntry> pair = ledgerRepo.findByIdempotencyKey(raceKey.value());
      assertThat(pair).as("exactly one matched ledger pair under the race key").hasSize(2);

      Account after = accountRepo.findById(userId).orElseThrow();
      assertThat(after.locked()).isEqualTo(krw(1_000));
      assertThat(after.available()).isEqualTo(krw(99_000));

      long net =
          ledgerRepo.findAll().stream().mapToLong(WalletServiceIntegrationTest::signed).sum();
      assertThat(net).as("ledger sum still zero after the race").isZero();
    }
  }

  private static List<WalletOperationResult> collect(
      List<CompletableFuture<WalletOperationResult>> futures)
      throws InterruptedException, ExecutionException, TimeoutException {
    List<WalletOperationResult> out = new ArrayList<>(futures.size());
    for (CompletableFuture<WalletOperationResult> f : futures) {
      out.add(f.get(30, TimeUnit.SECONDS));
    }
    return out;
  }
}
