package com.paymentsledger.paymentservice.kafka.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FraudDecisionEvent(
        UUID eventId,
        UUID transferId,
        String decision,
        int riskScore,
        List<FraudReason> reasons,
        Instant occurredAt,
        String correlationId
) {
    /** See PaymentCreatedEvent's matching overload for why this exists. */
    public FraudDecisionEvent(UUID eventId, UUID transferId, String decision, int riskScore,
                               List<FraudReason> reasons, Instant occurredAt) {
        this(eventId, transferId, decision, riskScore, reasons, occurredAt, null);
    }
}
