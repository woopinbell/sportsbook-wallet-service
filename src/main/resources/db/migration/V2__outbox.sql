-- V2: transactional outbox table (ADR-0006 Saga + Outbox).
--
-- Every wallet operation that should produce a Kafka event writes a row here in
-- the same DB transaction that mutates account / ledger_entry. A scheduled
-- publisher polls for unpublished rows, sends them to Kafka, and stamps
-- published_at on success. The atomic commit guarantees the event cannot vanish
-- (rolled-back) or duplicate (committed but lost) relative to its underlying
-- ledger change.

CREATE TABLE outbox_event (
    event_id      UUID                     PRIMARY KEY,
    topic         VARCHAR(64)              NOT NULL,
    partition_key VARCHAR(64)              NOT NULL,
    schema_name   VARCHAR(64)              NOT NULL,
    payload       BYTEA                    NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at  TIMESTAMP WITH TIME ZONE
);

COMMENT ON TABLE  outbox_event              IS 'Transactional outbox — rows here are published to Kafka by OutboxPublisher.';
COMMENT ON COLUMN outbox_event.partition_key IS 'Kafka partition key (userId.toString() in V1).';
COMMENT ON COLUMN outbox_event.schema_name   IS 'Avro record name (WalletDebited / WalletCredited / WalletDebitFailed) for diagnostics.';
COMMENT ON COLUMN outbox_event.payload       IS 'Avro binary-encoded record bytes (no Schema Registry in V1, ADR-0014).';
COMMENT ON COLUMN outbox_event.published_at  IS 'Set when the publisher receives an ack from Kafka; NULL means still pending.';

-- Hot read path: "unpublished rows oldest first". Partial index on the rare
-- NULL state keeps the scan small as the table grows.
CREATE INDEX ix_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;
