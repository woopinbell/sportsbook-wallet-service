package com.sportsbook.wallet.api.dto;

import com.sportsbook.protocol.value.Money;
import com.sportsbook.wallet.service.command.CreditCommand;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/wallet/transactions/credit}. Same as a transfer request plus an
 * explicit {@code source} that distinguishes "refund stake from LOCKED" from "payout profit from
 * HOUSE".
 */
public record CreditRequest(
    @NotNull UUID userId, @NotNull Money amount, @NotNull CreditCommand.Source source) {}
