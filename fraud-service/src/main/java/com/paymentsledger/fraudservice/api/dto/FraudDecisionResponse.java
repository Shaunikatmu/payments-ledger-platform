package com.paymentsledger.fraudservice.api.dto;

import com.paymentsledger.fraudservice.domain.FraudDecision;
import com.paymentsledger.fraudservice.domain.FraudDecisionReason;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FraudDecisionResponse(
        UUID id,
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        String decision,
        int riskScore,
        Instant createdAt,
        Instant reviewedAt,
        String reviewedOutcome,
        String reviewerNote,
        List<FraudReasonResponse> reasons
) {
    public static FraudDecisionResponse from(FraudDecision decision, List<FraudDecisionReason> reasons) {
        return new FraudDecisionResponse(
                decision.getId(),
                decision.getTransferId(),
                decision.getFromAccountId(),
                decision.getToAccountId(),
                decision.getAmountCents(),
                decision.getDecision().name(),
                decision.getRiskScore(),
                decision.getCreatedAt(),
                decision.getReviewedAt(),
                decision.getReviewedOutcome() == null ? null : decision.getReviewedOutcome().name(),
                decision.getReviewerNote(),
                reasons.stream().map(FraudReasonResponse::from).toList()
        );
    }
}
