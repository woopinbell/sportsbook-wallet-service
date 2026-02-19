package com.sportsbook.wallet.domain.error;

import com.sportsbook.protocol.value.IdempotencyKey;

/**
 * Thrown when a retry under an already-processed {@link IdempotencyKey} carries a payload that
 * disagrees with the original — different userId, amount, or operation kind. The original outcome
 * is preserved; the controller layer maps this to RFC 7807 {@code 409 Conflict} so the caller can
 * surface the divergence and stop retrying.
 */
public class IdempotencyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String idempotencyKey;
  private final String reason;

  public IdempotencyConflictException(IdempotencyKey idempotencyKey, String reason) {
    super("Idempotency conflict for key '" + idempotencyKey.value() + "': " + reason);
    this.idempotencyKey = idempotencyKey.value();
    this.reason = reason;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public String reason() {
    return reason;
  }
}
