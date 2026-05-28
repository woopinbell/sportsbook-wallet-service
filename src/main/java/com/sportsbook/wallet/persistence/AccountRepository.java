package com.sportsbook.wallet.persistence;

import com.sportsbook.wallet.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link Account}. The PK is {@code userId}, so {@link
 * #findById(Object)} from the parent gives an unlocked read by user.
 *
 * <p>{@link #findByUserIdForUpdate(UUID)} is the critical-path entry point for every balance
 * mutation: it translates to {@code SELECT ... FOR UPDATE} (ADR-0005 — pessimistic row lock on the
 * critical path). The explicit {@code @Query} pairs with {@code @Lock} so the method name describes
 * the intent rather than the Spring Data keyword.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.userId = :userId")
  Optional<Account> findByUserIdForUpdate(@Param("userId") UUID userId);
}
