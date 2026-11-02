package com.paymentsledger.fraudservice.api.dto;

import com.paymentsledger.fraudservice.domain.FraudDecisionReason;

public record FraudReasonResponse(String ruleName, int score, String explanation) {

    public static FraudReasonResponse from(FraudDecisionReason reason) {
        return new FraudReasonResponse(reason.getRuleName(), reason.getRuleScore(), reason.getExplanation());
    }
}
