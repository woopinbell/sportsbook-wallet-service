package com.sportsbook.wallet.api.dto;

import com.sportsbook.protocol.value.Money;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/wallet/transactions/deposit | withdraw | debit}. The {@code
 * Idempotency-Key} header is required separately.
 */
public record TransactionRequest(@NotNull UUID userId, @NotNull Money amount) {}
