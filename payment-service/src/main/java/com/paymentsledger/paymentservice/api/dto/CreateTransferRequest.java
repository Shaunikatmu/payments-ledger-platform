package com.paymentsledger.paymentservice.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateTransferRequest(
        @NotNull(message = "must not be null")
        UUID fromAccountId,

        @NotNull(message = "must not be null")
        UUID toAccountId,

        @Positive(message = "must be greater than zero")
        long amountCents
) {
}
