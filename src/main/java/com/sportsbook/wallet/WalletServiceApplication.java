package com.sportsbook.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wallet service entry point.
 *
 * <p>Owns the double-entry ledger and the {@code Account} aggregate (available + locked balances).
 * Scheduling is enabled application-wide so the outbox publisher and the daily reconciliation job
 * can declare {@code @Scheduled} hooks without per-feature plumbing.
 */
// @SpringBootApplication is meta-annotated with @Configuration, so Spring instantiates this class
// as a bean; a private constructor would break that. Suppress the utility-class rule explicitly.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@SpringBootApplication
@EnableScheduling
public class WalletServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(WalletServiceApplication.class, args);
  }
}
