package com.sportsbook.wallet.api.dto;

import com.sportsbook.protocol.value.Currency;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body of {@code POST /internal/v1/wallet/accounts}. */
public record OpenAccountRequest(@NotNull UUID userId, @NotNull Currency currency) {}
