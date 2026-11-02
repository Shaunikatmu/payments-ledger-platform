package com.paymentsledger.paymentservice.exception;

import java.time.Instant;

/** Consistent error body shape for every 4xx/5xx response across the API. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
