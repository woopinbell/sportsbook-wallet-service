package com.sportsbook.wallet.domain.error;

import java.util.UUID;

/**
 * Thrown when a wallet operation references a user account that does not exist yet (no row in
 * {@code account} for the given userId). The controller layer maps this to RFC 7807 {@code 404 Not
 * Found} with the type slug {@code WALLET_ACCOUNT_NOT_FOUND}.
 */
public class AccountNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final UUID userId;

  public AccountNotFoundException(UUID userId) {
    super("No wallet account for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
