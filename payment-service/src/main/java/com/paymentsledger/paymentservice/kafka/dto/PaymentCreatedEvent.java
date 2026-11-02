package com.paymentsledger.paymentservice.kafka.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by payment-service's outbox relay, consumed by fraud-service.
 * Deliberately duplicated (not shared via a library module) between the two
 * services -- see docs/DECISIONS.md on why that trade-off is fine at this
 * scale.
 */
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
