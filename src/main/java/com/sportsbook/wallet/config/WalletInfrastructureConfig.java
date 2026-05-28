package com.sportsbook.wallet.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for service-layer infrastructure that does not warrant its own component:
 *
 * <ul>
 *   <li>{@link Clock} — UTC system clock, injected wherever the service needs to stamp {@code
 *       Instant.now()} so tests can substitute a fixed clock.
 *   <li>{@link TransactionTemplate} — programmatic transaction boundary the {@code WalletService}
 *       uses to scope each write so that {@code DataIntegrityViolationException} on the idempotency
 *       constraint can be caught and resolved without dragging the surrounding read into a
 *       rolled-back transaction.
 * </ul>
 */
@Configuration
public class WalletInfrastructureConfig {

  @Bean
  public Clock systemClock() {
    return Clock.systemUTC();
  }

  @Bean
  public TransactionTemplate writeTransactionTemplate(PlatformTransactionManager txManager) {
    return new TransactionTemplate(txManager);
  }
}
