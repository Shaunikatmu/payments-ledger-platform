package com.paymentsledger.fraudservice.api.dto;

import com.paymentsledger.fraudservice.domain.ReviewOutcome;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @NotNull(message = "must not be null")
        ReviewOutcome outcome,

        @Size(max = 500, message = "must be at most 500 characters")
        String note
) {
}
