package com.sportsbook.wallet.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sportsbook.protocol.event.WalletCreditReason;
import com.sportsbook.protocol.event.WalletCredited;
import com.sportsbook.protocol.event.WalletDebitFailed;
import com.sportsbook.protocol.event.WalletDebitFailureReason;
import com.sportsbook.protocol.event.WalletDebited;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import com.sportsbook.wallet.service.WalletOperationResult;
import com.sportsbook.wallet.service.WalletService;
import com.sportsbook.wallet.service.command.CreditCommand;
import com.sportsbook.wallet.service.command.DebitCommand;
import com.sportsbook.wallet.service.command.DepositCommand;
import com.sportsbook.wallet.service.command.OpenAccountCommand;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Atomic-outbox coverage. Each test triggers a wallet operation, inspects the {@code outbox_event}
 * row that landed in the same transaction as the ledger pair, deserialises the Avro payload, and
 * exercises the publisher draining it out to an embedded Kafka broker. The auto-poll
 * {@code @Scheduled} is parked at 24 h in application-test.yml so each test controls when
 * publishing happens.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
    partitions = 1,
    topics = {
      WalletEventFactory.TOPIC_DEBITED,
      WalletEventFactory.TOPIC_CREDITED,
      WalletEventFactory.TOPIC_DEBIT_FAILED
    },
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class OutboxIntegrationTest {

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
  @Autowired OutboxEventRepository outboxRepo;
  @Autowired OutboxPublisher outboxPublisher;
  @Autowired EmbeddedKafkaBroker kafkaBroker;

  private UUID userId;

  @BeforeEach
  void seedFundedAccount() {
    userId = UUID.randomUUID();
    walletService.openAccount(new OpenAccountCommand(userId, Currency.KRW));
    walletService.deposit(new DepositCommand(userId, krw(100_000), key("seed")));
  }

  @Test
  @DisplayName("BET_DEBIT writes a WalletDebited outbox row in the same transaction")
  void debit_success_lands_walletDebited_payload() {
    DebitCommand cmd = new DebitCommand(userId, krw(5_000), key("bet"));

    WalletOperationResult result = walletService.debit(cmd);

    OutboxEvent row =
        findUnpublishedFor(cmd.idempotencyKey().value(), WalletEventFactory.SCHEMA_DEBITED);
    assertThat(row.topic()).isEqualTo(WalletEventFactory.TOPIC_DEBITED);
    assertThat(row.partitionKey()).isEqualTo(userId.toString());
    assertThat(row.publishedAt()).isNull();

    WalletDebited decoded = AvroSerializer.deserialize(row.payload(), WalletDebited.class);
    assertThat(decoded.getUserId()).isEqualTo(userId.toString());
    assertThat(decoded.getAmount().getAmount()).isEqualTo(5_000L);
    assertThat(decoded.getAmount().getCurrency()).isEqualTo("KRW");
    assertThat(decoded.getIdempotencyKey()).isEqualTo(cmd.idempotencyKey().value());
    assertThat(decoded.getLedgerTxId()).isEqualTo(result.operationGroupId().toString());
  }

  @Test
  @DisplayName("credit from HOUSE writes a WalletCredited with reason=PAYOUT")
  void credit_from_house_lands_payout_payload() {
    CreditCommand cmd =
        new CreditCommand(userId, krw(7_000), CreditCommand.Source.HOUSE_POOL, key("win"));

    walletService.credit(cmd);

    OutboxEvent row =
        findUnpublishedFor(cmd.idempotencyKey().value(), WalletEventFactory.SCHEMA_CREDITED);
    WalletCredited decoded = AvroSerializer.deserialize(row.payload(), WalletCredited.class);
    assertThat(decoded.getReason()).isEqualTo(WalletCreditReason.PAYOUT);
    assertThat(decoded.getAmount().getAmount()).isEqualTo(7_000L);
  }

  @Test
  @DisplayName("credit from USER_LOCKED writes a WalletCredited with reason=REFUND")
  void credit_from_locked_lands_refund_payload() {
    walletService.debit(new DebitCommand(userId, krw(3_000), key("stake")));
    CreditCommand cmd =
        new CreditCommand(userId, krw(3_000), CreditCommand.Source.USER_LOCKED, key("void"));

    walletService.credit(cmd);

    OutboxEvent row =
        findUnpublishedFor(cmd.idempotencyKey().value(), WalletEventFactory.SCHEMA_CREDITED);
    WalletCredited decoded = AvroSerializer.deserialize(row.payload(), WalletCredited.class);
    assertThat(decoded.getReason()).isEqualTo(WalletCreditReason.REFUND);
  }

  @Test
  @DisplayName("InsufficientBalance writes WalletDebitFailed in a fresh transaction")
  void debit_failure_lands_walletDebitFailed_after_rollback() {
    DebitCommand cmd = new DebitCommand(userId, krw(200_000), key("overdraft"));

    assertThatThrownBy(() -> walletService.debit(cmd))
        .isInstanceOf(InsufficientBalanceException.class);

    OutboxEvent row =
        findUnpublishedFor(cmd.idempotencyKey().value(), WalletEventFactory.SCHEMA_DEBIT_FAILED);
    assertThat(row.topic()).isEqualTo(WalletEventFactory.TOPIC_DEBIT_FAILED);
    WalletDebitFailed decoded = AvroSerializer.deserialize(row.payload(), WalletDebitFailed.class);
    assertThat(decoded.getReason()).isEqualTo(WalletDebitFailureReason.INSUFFICIENT_BALANCE);
    assertThat(decoded.getRequestedAmount().getAmount()).isEqualTo(200_000L);
  }

  @Test
  @DisplayName("publisher drains pending rows to Kafka and marks them published")
  void publisher_drains_pending_and_kafka_receives_the_record() {
    DebitCommand cmd = new DebitCommand(userId, krw(2_000), key("drain"));
    walletService.debit(cmd);

    outboxPublisher.publishPending();

    OutboxEvent row = findFor(cmd.idempotencyKey().value(), WalletEventFactory.SCHEMA_DEBITED);
    assertThat(row.publishedAt()).as("publisher stamped published_at after Kafka ack").isNotNull();

    try (Consumer<String, byte[]> consumer = newConsumer()) {
      consumer.subscribe(List.of(WalletEventFactory.TOPIC_DEBITED));
      ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofSeconds(10));
      ConsumerRecord<String, byte[]> match =
          findRecordForKey(polled, userId.toString())
              .orElseThrow(() -> new AssertionError("Kafka did not receive the published record"));
      WalletDebited decoded = AvroSerializer.deserialize(match.value(), WalletDebited.class);
      assertThat(decoded.getIdempotencyKey()).isEqualTo(cmd.idempotencyKey().value());
    }
  }

  private OutboxEvent findUnpublishedFor(String idempotencyKey, String schemaName) {
    return outboxRepo.findAll().stream()
        .filter(e -> matchesByPayload(e, idempotencyKey, schemaName) && e.publishedAt() == null)
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "No unpublished outbox row for " + schemaName + " / key=" + idempotencyKey));
  }

  private OutboxEvent findFor(String idempotencyKey, String schemaName) {
    return outboxRepo.findAll().stream()
        .filter(e -> matchesByPayload(e, idempotencyKey, schemaName))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError("No outbox row for " + schemaName + " / key=" + idempotencyKey));
  }

  private static boolean matchesByPayload(
      OutboxEvent event, String idempotencyKey, String schemaName) {
    if (!schemaName.equals(event.schemaName())) {
      return false;
    }
    String embeddedKey = extractIdempotencyKey(event, schemaName);
    return idempotencyKey.equals(embeddedKey);
  }

  private static String extractIdempotencyKey(OutboxEvent event, String schemaName) {
    return switch (schemaName) {
      case WalletEventFactory.SCHEMA_DEBITED ->
          AvroSerializer.deserialize(event.payload(), WalletDebited.class).getIdempotencyKey();
      case WalletEventFactory.SCHEMA_CREDITED ->
          AvroSerializer.deserialize(event.payload(), WalletCredited.class).getIdempotencyKey();
      case WalletEventFactory.SCHEMA_DEBIT_FAILED ->
          AvroSerializer.deserialize(event.payload(), WalletDebitFailed.class).getIdempotencyKey();
      default -> throw new IllegalStateException("Unknown schema " + schemaName);
    };
  }

  private static java.util.Optional<ConsumerRecord<String, byte[]>> findRecordForKey(
      ConsumerRecords<String, byte[]> records, String key) {
    for (ConsumerRecord<String, byte[]> record : records) {
      if (key.equals(record.key())) {
        return java.util.Optional.of(record);
      }
    }
    return java.util.Optional.empty();
  }

  private Consumer<String, byte[]> newConsumer() {
    Map<String, Object> props =
        new HashMap<>(KafkaTestUtils.consumerProps("outbox-test", "true", kafkaBroker));
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(props);
  }

  private static IdempotencyKey key(String prefix) {
    return IdempotencyKey.of(prefix + "-" + UUID.randomUUID());
  }

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }
}
