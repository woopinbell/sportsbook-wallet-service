package com.sportsbook.wallet.outbox;

import com.sportsbook.protocol.event.WalletCreditReason;
import com.sportsbook.protocol.event.WalletCredited;
import com.sportsbook.protocol.event.WalletDebitFailed;
import com.sportsbook.protocol.event.WalletDebitFailureReason;
import com.sportsbook.protocol.event.WalletDebited;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.infrastructure.id.UuidV7;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds {@link OutboxEvent} rows from operation context. Keeps the Avro construction and topic/key
 * conventions out of {@link com.sportsbook.wallet.service.WalletService} so the service reads as
 * straight transfer logic.
 *
 * <p>Topic conventions:
 *
 * <ul>
 *   <li>{@code wallet.debited.v1} — bet stake debit success (BET_DEBIT only). Deposit / withdraw do
 *       not publish events in V1.
 *   <li>{@code wallet.credited.v1} — payout from house or refund of stake from locked.
 *   <li>{@code wallet.debit-failed.v1} — bet stake debit rejection (drives the betting saga's
 *       compensator).
 * </ul>
 *
 * <p>Partition key is the user UUID for every wallet event so all events for one user land on the
 * same partition (ADR-0006).
 */
@Component
public class WalletEventFactory {

  static final String TOPIC_DEBITED = "wallet.debited.v1";
  static final String TOPIC_CREDITED = "wallet.credited.v1";
  static final String TOPIC_DEBIT_FAILED = "wallet.debit-failed.v1";

  static final String SCHEMA_DEBITED = "WalletDebited";
  static final String SCHEMA_CREDITED = "WalletCredited";
  static final String SCHEMA_DEBIT_FAILED = "WalletDebitFailed";

  public OutboxEvent debited(
      UUID userId, Money amount, IdempotencyKey key, UUID ledgerTxId, Instant now) {
    WalletDebited record =
        WalletDebited.newBuilder()
            .setUserId(userId.toString())
            .setAmount(toAvroMoney(amount))
            .setIdempotencyKey(key.value())
            .setLedgerTxId(ledgerTxId.toString())
            .setOccurredAt(now)
            .build();
    return wrap(TOPIC_DEBITED, SCHEMA_DEBITED, userId, record, now);
  }

  // Six parameters but each is a distinct value-object passed through to the Avro builder.
  @SuppressWarnings("checkstyle:ParameterNumber")
  public OutboxEvent credited(
      UUID userId,
      Money amount,
      IdempotencyKey key,
      UUID ledgerTxId,
      WalletCreditReason reason,
      Instant now) {
    WalletCredited record =
        WalletCredited.newBuilder()
            .setUserId(userId.toString())
            .setAmount(toAvroMoney(amount))
            .setIdempotencyKey(key.value())
            .setLedgerTxId(ledgerTxId.toString())
            .setReason(reason)
            .setOccurredAt(now)
            .build();
    return wrap(TOPIC_CREDITED, SCHEMA_CREDITED, userId, record, now);
  }

  public OutboxEvent debitFailed(
      UUID userId,
      Money requestedAmount,
      IdempotencyKey key,
      WalletDebitFailureReason reason,
      Instant now) {
    WalletDebitFailed record =
        WalletDebitFailed.newBuilder()
            .setUserId(userId.toString())
            .setRequestedAmount(toAvroMoney(requestedAmount))
            .setIdempotencyKey(key.value())
            .setReason(reason)
            .setOccurredAt(now)
            .build();
    return wrap(TOPIC_DEBIT_FAILED, SCHEMA_DEBIT_FAILED, userId, record, now);
  }

  private static OutboxEvent wrap(
      String topic,
      String schemaName,
      UUID userId,
      org.apache.avro.specific.SpecificRecord record,
      Instant now) {
    byte[] payload = AvroSerializer.serialize(record);
    return OutboxEvent.pending(
        UuidV7.generate(), topic, userId.toString(), schemaName, payload, now);
  }

  static com.sportsbook.protocol.event.Money toAvroMoney(Money money) {
    return com.sportsbook.protocol.event.Money.newBuilder()
        .setAmount(money.amount())
        .setCurrency(currencyIso(money.currency()))
        .build();
  }

  private static String currencyIso(Currency currency) {
    return currency.name();
  }
}
