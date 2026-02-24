package com.sportsbook.wallet.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportsbook.protocol.value.Currency;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.domain.Account;
import com.sportsbook.wallet.domain.LedgerReason;
import com.sportsbook.wallet.domain.error.AccountNotFoundException;
import com.sportsbook.wallet.domain.error.CurrencyMismatchException;
import com.sportsbook.wallet.domain.error.IdempotencyConflictException;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import com.sportsbook.wallet.service.WalletOperationResult;
import com.sportsbook.wallet.service.WalletService;
import com.sportsbook.wallet.service.command.CreditCommand;
import com.sportsbook.wallet.service.command.DebitCommand;
import com.sportsbook.wallet.service.command.DepositCommand;
import com.sportsbook.wallet.service.command.OpenAccountCommand;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WalletController.class)
@Import(GlobalExceptionHandler.class)
class WalletControllerTest {

  private static final UUID USER = UUID.fromString("00000000-0000-7000-8000-00000000aaaa");
  private static final Instant AT = Instant.parse("2026-05-28T10:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper om;
  @MockBean WalletService walletService;

  private static Money krw(long minor) {
    return new Money(minor, Currency.KRW);
  }

  // -----------------------------------------------------------------------------------------------
  // openAccount
  // -----------------------------------------------------------------------------------------------

  @Test
  @DisplayName("POST /accounts — 200 returns the account snapshot")
  void openAccount_ok() throws Exception {
    Account stub = Account.openFor(USER, Currency.KRW, AT);
    given(walletService.openAccount(any(OpenAccountCommand.class))).willReturn(stub);

    mvc.perform(
            post("/internal/v1/wallet/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + USER + "\",\"currency\":\"KRW\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(USER.toString()))
        .andExpect(jsonPath("$.currency").value("KRW"))
        .andExpect(jsonPath("$.available.amount").value(0))
        .andExpect(jsonPath("$.locked.amount").value(0));
  }

  @Test
  @DisplayName("POST /accounts — 400 on bean-validation violation (missing userId)")
  void openAccount_validation_failure() throws Exception {
    mvc.perform(
            post("/internal/v1/wallet/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"KRW\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("WALLET_VALIDATION_FAILED"));
  }

  // -----------------------------------------------------------------------------------------------
  // getBalance
  // -----------------------------------------------------------------------------------------------

  @Test
  @DisplayName("GET /accounts/{userId}/balance — 200 with available + locked + total")
  void getBalance_ok() throws Exception {
    Account stub = Account.openFor(USER, Currency.KRW, AT);
    stub.increaseAvailable(krw(5_000), AT);
    stub.moveAvailableToLocked(krw(2_000), AT);
    given(walletService.requireAccount(USER)).willReturn(stub);

    mvc.perform(get("/internal/v1/wallet/accounts/{userId}/balance", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available.amount").value(3_000))
        .andExpect(jsonPath("$.locked.amount").value(2_000))
        .andExpect(jsonPath("$.total.amount").value(5_000));
  }

  @Test
  @DisplayName("GET /accounts/{userId}/balance — 404 ProblemDetail when account missing")
  void getBalance_not_found() throws Exception {
    given(walletService.requireAccount(USER)).willThrow(new AccountNotFoundException(USER));

    mvc.perform(get("/internal/v1/wallet/accounts/{userId}/balance", USER))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("WALLET_ACCOUNT_NOT_FOUND"))
        .andExpect(jsonPath("$.userId").value(USER.toString()));
  }

  // -----------------------------------------------------------------------------------------------
  // deposit
  // -----------------------------------------------------------------------------------------------

  @Test
  @DisplayName("POST /transactions/deposit — 200 with operation_group on the response")
  void deposit_ok() throws Exception {
    UUID groupId = UUID.fromString("00000000-0000-7000-8000-00000000abcd");
    given(walletService.deposit(any(DepositCommand.class)))
        .willReturn(new WalletOperationResult(groupId, USER, krw(1_000), LedgerReason.DEPOSIT, AT));

    mvc.perform(
            post("/internal/v1/wallet/transactions/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dep-1")
                .content(
                    "{\"userId\":\""
                        + USER
                        + "\",\"amount\":{\"amount\":1000,\"currency\":\"KRW\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operationGroupId").value(groupId.toString()))
        .andExpect(jsonPath("$.reason").value("DEPOSIT"));
  }

  @Test
  @DisplayName("POST /transactions/deposit — 400 when Idempotency-Key header is missing")
  void deposit_missing_header() throws Exception {
    mvc.perform(
            post("/internal/v1/wallet/transactions/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\""
                        + USER
                        + "\",\"amount\":{\"amount\":1000,\"currency\":\"KRW\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("WALLET_BAD_REQUEST"))
        .andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));
  }

  // -----------------------------------------------------------------------------------------------
  // debit — error paths flow through the service into ProblemDetail responses
  // -----------------------------------------------------------------------------------------------

  @Test
  @DisplayName("POST /transactions/debit — 422 ProblemDetail with requested + available")
  void debit_insufficient_balance() throws Exception {
    given(walletService.debit(any(DebitCommand.class)))
        .willThrow(new InsufficientBalanceException(USER, krw(2_000), krw(500)));

    mvc.perform(
            post("/internal/v1/wallet/transactions/debit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "bet-1")
                .content(
                    "{\"userId\":\""
                        + USER
                        + "\",\"amount\":{\"amount\":2000,\"currency\":\"KRW\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("WALLET_INSUFFICIENT_BALANCE"))
        .andExpect(jsonPath("$.requested.amount").value(2_000))
        .andExpect(jsonPath("$.available.amount").value(500));
  }

  @Test
  @DisplayName("POST /transactions/debit — 422 on currency mismatch")
  void debit_currency_mismatch() throws Exception {
    given(walletService.debit(any(DebitCommand.class)))
        .willThrow(new CurrencyMismatchException(Currency.KRW, Currency.USD));

    mvc.perform(
            post("/internal/v1/wallet/transactions/debit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "bet-2")
                .content(
                    "{\"userId\":\"" + USER + "\",\"amount\":{\"amount\":1,\"currency\":\"USD\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("WALLET_CURRENCY_MISMATCH"))
        .andExpect(jsonPath("$.expected").value("KRW"))
        .andExpect(jsonPath("$.actual").value("USD"));
  }

  @Test
  @DisplayName("POST /transactions/debit — 409 on idempotency conflict")
  void debit_idempotency_conflict() throws Exception {
    given(walletService.debit(any(DebitCommand.class)))
        .willThrow(new IdempotencyConflictException(IdempotencyKey.of("bet-3"), "amount mismatch"));

    mvc.perform(
            post("/internal/v1/wallet/transactions/debit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "bet-3")
                .content(
                    "{\"userId\":\""
                        + USER
                        + "\",\"amount\":{\"amount\":1000,\"currency\":\"KRW\"}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("WALLET_IDEMPOTENCY_CONFLICT"))
        .andExpect(jsonPath("$.idempotencyKey").value("bet-3"));
  }

  // -----------------------------------------------------------------------------------------------
  // credit — covers the extra `source` field
  // -----------------------------------------------------------------------------------------------

  @Test
  @DisplayName("POST /transactions/credit — 200 with source=HOUSE_POOL")
  void credit_house_payout() throws Exception {
    UUID groupId = UUID.fromString("00000000-0000-7000-8000-00000000c0de");
    given(walletService.credit(any(CreditCommand.class)))
        .willReturn(
            new WalletOperationResult(groupId, USER, krw(3_000), LedgerReason.BET_PAYOUT, AT));

    mvc.perform(
            post("/internal/v1/wallet/transactions/credit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "win-1")
                .content(
                    "{\"userId\":\""
                        + USER
                        + "\",\"amount\":{\"amount\":3000,\"currency\":\"KRW\"},"
                        + "\"source\":\"HOUSE_POOL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reason").value("BET_PAYOUT"));
  }

  // -----------------------------------------------------------------------------------------------
  // Malformed input — Spring's framework exceptions hit the badRequest handler
  // -----------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Malformed JSON body — 400 WALLET_BAD_REQUEST")
  void malformed_json() throws Exception {
    mvc.perform(
            post("/internal/v1/wallet/transactions/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "dep-x")
                .content("not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WALLET_BAD_REQUEST"));
  }

  @Test
  @DisplayName("Invalid IdempotencyKey shape — 400 WALLET_BAD_REQUEST (length cap)")
  void bad_idempotency_key_shape() throws Exception {
    String tooLong = "x".repeat(IdempotencyKey.MAX_LENGTH + 1);
    mvc.perform(
            post("/internal/v1/wallet/transactions/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", tooLong)
                .content(
                    "{\"userId\":\""
                        + USER
                        + "\",\"amount\":{\"amount\":1000,\"currency\":\"KRW\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WALLET_BAD_REQUEST"));
  }
}
