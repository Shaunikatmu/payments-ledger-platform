package com.paymentsledger.paymentservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per successfully-applied fraud.decision event, keyed by its eventId, to make consumption idempotent. */
@Entity
@Table(name = "processed_fraud_decisions")
public class ProcessedFraudDecision {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedFraudDecision() {
        // JPA
    }

    public ProcessedFraudDecision(UUID eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    public UUID getEventId() {
        return eventId;
    }
}
