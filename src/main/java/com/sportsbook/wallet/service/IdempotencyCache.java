package com.sportsbook.wallet.service;

import com.sportsbook.protocol.value.IdempotencyKey;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis fast-path cache for processed idempotency keys (ADR-0005). The DB unique constraint on
 * {@code (idempotency_key, side)} is what actually guarantees "process once"; this cache is a
 * latency shortcut that lets a retry skip the SELECT FOR UPDATE round-trip when it can be answered
 * from Redis.
 *
 * <p>Key format: {@code idempotency:wallet:<caller-key>} (24h TTL). The value stored is the
 * operation_group_id of the original transfer, which lets the caller correlate later if needed.
 */
@Component
public class IdempotencyCache {

  static final Duration TTL = Duration.ofHours(24);
  static final String KEY_PREFIX = "idempotency:wallet:";

  private final StringRedisTemplate redis;

  public IdempotencyCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public Optional<UUID> lookupOperationGroup(IdempotencyKey key) {
    String value = redis.opsForValue().get(redisKey(key));
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      // Tampered value in the cache — fall back to the DB lookup path.
      return Optional.empty();
    }
  }

  public void markProcessed(IdempotencyKey key, UUID operationGroupId) {
    redis.opsForValue().set(redisKey(key), operationGroupId.toString(), TTL);
  }

  private static String redisKey(IdempotencyKey key) {
    return KEY_PREFIX + key.value();
  }
}
