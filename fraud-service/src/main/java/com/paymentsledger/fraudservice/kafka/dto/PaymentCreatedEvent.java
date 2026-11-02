package com.paymentsledger.fraudservice.kafka.dto;

import java.time.Instant;
import java.util.UUID;

/** Mirrors payment-service's PaymentCreatedEvent -- see docs/DECISIONS.md on the duplication trade-off. */
public record PaymentCreatedEvent(
        UUID eventId,
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        Instant occurredAt,
        String correlationId
) {
    /**
     * Pre-correlation-ID call sites (and existing tests) still compile
     * unchanged; correlationId is simply null for them, which the
     * logging/MDC code on the consuming side already treats as "no
     * correlation available" rather than a bug.
     */
    public PaymentCreatedEvent(UUID eventId, UUID transferId, UUID fromAccountId, UUID toAccountId,
                                long amountCents, Instant occurredAt) {
        this(eventId, transferId, fromAccountId, toAccountId, amountCents, occurredAt, null);
    }
}
