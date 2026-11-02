package com.paymentsledger.fraudservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_decisions")
public class FraudDecision {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false, unique = true)
    private UUID transferId;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reviewed_outcome")
    private ReviewOutcome reviewedOutcome;

    @Column(name = "reviewer_note")
    private String reviewerNote;

    protected FraudDecision() {
        // JPA
    }

    public FraudDecision(UUID id, UUID transferId, UUID fromAccountId, UUID toAccountId, long amountCents,
                          Decision decision, int riskScore, Instant createdAt) {
        this.id = id;
        this.transferId = transferId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amountCents = amountCents;
        this.decision = decision;
        this.riskScore = riskScore;
        this.createdAt = createdAt;
    }

    public boolean isOpenForReview() {
        return decision == Decision.REVIEW && reviewedAt == null;
    }

    public void applyReview(ReviewOutcome outcome, String note, Instant now) {
        this.reviewedOutcome = outcome;
        this.reviewerNote = note;
        this.reviewedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public Decision getDecision() {
        return decision;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public ReviewOutcome getReviewedOutcome() {
        return reviewedOutcome;
    }

    public String getReviewerNote() {
        return reviewerNote;
    }
}
