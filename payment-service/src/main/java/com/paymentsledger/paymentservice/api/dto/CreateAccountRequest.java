package com.paymentsledger.paymentservice.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotBlank(message = "must not be blank")
        String ownerName,

        @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code, e.g. USD")
        String currency,

        @Min(value = 0, message = "must not be negative")
        Long initialBalanceCents
) {
    public CreateAccountRequest {
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        if (initialBalanceCents == null) {
            initialBalanceCents = 0L;
        }
    }
}
