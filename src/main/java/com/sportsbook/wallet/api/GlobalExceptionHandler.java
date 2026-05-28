package com.sportsbook.wallet.api;

import com.sportsbook.wallet.domain.error.AccountNotFoundException;
import com.sportsbook.wallet.domain.error.CurrencyMismatchException;
import com.sportsbook.wallet.domain.error.IdempotencyConflictException;
import com.sportsbook.wallet.domain.error.InsufficientBalanceException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates domain and framework exceptions into RFC 7807 ProblemDetail responses (ADR-0004).
 * Spring sets the {@code application/problem+json} content type automatically when a controller
 * returns {@link ProblemDetail}.
 *
 * <p>Status / slug table:
 *
 * <ul>
 *   <li>{@code 400 WALLET_BAD_REQUEST} — malformed JSON, bad header, bad UUID, bad IdempotencyKey
 *       shape, generic IllegalArgumentException from the domain.
 *   <li>{@code 400 WALLET_VALIDATION_FAILED} — bean-validation violations on the request body
 *       (field-level errors attached).
 *   <li>{@code 404 WALLET_ACCOUNT_NOT_FOUND} — referenced user has no account.
 *   <li>{@code 409 WALLET_IDEMPOTENCY_CONFLICT} — retry payload disagrees with the original.
 *   <li>{@code 422 WALLET_INSUFFICIENT_BALANCE} — debit / withdraw would drive a bucket below zero.
 *   <li>{@code 422 WALLET_CURRENCY_MISMATCH} — transfer currency does not match the account's
 *       currency.
 *   <li>{@code 500 WALLET_INTERNAL_ERROR} — catch-all for unforeseen failures; logged at ERROR.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(InsufficientBalanceException.class)
  public ProblemDetail insufficientBalance(InsufficientBalanceException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    problem.setTitle("Insufficient balance");
    problem.setProperty("code", "WALLET_INSUFFICIENT_BALANCE");
    problem.setProperty("userId", e.userId());
    problem.setProperty("requested", e.requested());
    problem.setProperty("available", e.available());
    return problem;
  }

  @ExceptionHandler(CurrencyMismatchException.class)
  public ProblemDetail currencyMismatch(CurrencyMismatchException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    problem.setTitle("Currency mismatch");
    problem.setProperty("code", "WALLET_CURRENCY_MISMATCH");
    problem.setProperty("expected", e.expected());
    problem.setProperty("actual", e.actual());
    return problem;
  }

  @ExceptionHandler(AccountNotFoundException.class)
  public ProblemDetail accountNotFound(AccountNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    problem.setTitle("Account not found");
    problem.setProperty("code", "WALLET_ACCOUNT_NOT_FOUND");
    problem.setProperty("userId", e.userId());
    return problem;
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    problem.setTitle("Idempotency conflict");
    problem.setProperty("code", "WALLET_IDEMPOTENCY_CONFLICT");
    problem.setProperty("idempotencyKey", e.idempotencyKey());
    problem.setProperty("conflict", e.reason());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail validationFailed(MethodArgumentNotValidException e) {
    String fields =
        e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, fields.isEmpty() ? "Request body failed validation" : fields);
    problem.setTitle("Validation failed");
    problem.setProperty("code", "WALLET_VALIDATION_FAILED");
    return problem;
  }

  @ExceptionHandler({
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ProblemDetail badRequest(Exception e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    problem.setTitle("Bad request");
    problem.setProperty("code", "WALLET_BAD_REQUEST");
    return problem;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail internalError(Exception e) {
    log.error("Unhandled exception bubbled up to GlobalExceptionHandler", e);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error — see server logs for trace");
    problem.setTitle("Internal server error");
    problem.setProperty("code", "WALLET_INTERNAL_ERROR");
    return problem;
  }
}
