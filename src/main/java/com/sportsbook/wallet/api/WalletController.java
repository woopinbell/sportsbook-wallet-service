package com.sportsbook.wallet.api;

import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.wallet.api.dto.AccountResponse;
import com.sportsbook.wallet.api.dto.BalanceResponse;
import com.sportsbook.wallet.api.dto.CreditRequest;
import com.sportsbook.wallet.api.dto.OpenAccountRequest;
import com.sportsbook.wallet.api.dto.TransactionRequest;
import com.sportsbook.wallet.api.dto.WalletOperationResponse;
import com.sportsbook.wallet.domain.Account;
import com.sportsbook.wallet.service.WalletService;
import com.sportsbook.wallet.service.command.CreditCommand;
import com.sportsbook.wallet.service.command.DebitCommand;
import com.sportsbook.wallet.service.command.DepositCommand;
import com.sportsbook.wallet.service.command.OpenAccountCommand;
import com.sportsbook.wallet.service.command.WithdrawCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal-only REST surface (ADR-0004 — {@code /internal/v1/...} prefix). All mutating endpoints
 * require an {@code Idempotency-Key} header that the service uses as the natural retry key
 * (ADR-0005).
 *
 * <p>Domain exceptions are translated into RFC 7807 ProblemDetail responses by {@link
 * com.sportsbook.wallet.api.GlobalExceptionHandler}, so the controller body stays free of error
 * mapping.
 */
@RestController
@RequestMapping("/internal/v1/wallet")
public class WalletController {

  private final WalletService walletService;

  public WalletController(WalletService walletService) {
    this.walletService = walletService;
  }

  @PostMapping("/accounts")
  public AccountResponse openAccount(@Valid @RequestBody OpenAccountRequest req) {
    Account a = walletService.openAccount(new OpenAccountCommand(req.userId(), req.currency()));
    return AccountResponse.from(a);
  }

  @GetMapping("/accounts/{userId}/balance")
  public BalanceResponse getBalance(@PathVariable UUID userId) {
    return BalanceResponse.from(walletService.requireAccount(userId));
  }

  @PostMapping("/transactions/deposit")
  public WalletOperationResponse deposit(
      @Valid @RequestBody TransactionRequest req,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return WalletOperationResponse.from(
        walletService.deposit(
            new DepositCommand(req.userId(), req.amount(), IdempotencyKey.of(idempotencyKey))));
  }

  @PostMapping("/transactions/withdraw")
  public WalletOperationResponse withdraw(
      @Valid @RequestBody TransactionRequest req,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return WalletOperationResponse.from(
        walletService.withdraw(
            new WithdrawCommand(req.userId(), req.amount(), IdempotencyKey.of(idempotencyKey))));
  }

  @PostMapping("/transactions/debit")
  public WalletOperationResponse debit(
      @Valid @RequestBody TransactionRequest req,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return WalletOperationResponse.from(
        walletService.debit(
            new DebitCommand(req.userId(), req.amount(), IdempotencyKey.of(idempotencyKey))));
  }

  @PostMapping("/transactions/credit")
  public WalletOperationResponse credit(
      @Valid @RequestBody CreditRequest req,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return WalletOperationResponse.from(
        walletService.credit(
            new CreditCommand(
                req.userId(), req.amount(), req.source(), IdempotencyKey.of(idempotencyKey))));
  }
}
