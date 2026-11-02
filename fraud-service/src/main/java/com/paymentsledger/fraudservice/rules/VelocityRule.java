package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Flags an account sending an unusually high number of transfers in a short window -- a classic card-testing / account-takeover signal. */
@Component
public class VelocityRule implements FraudRule {

    private final int maxTransfersPerWindow;
    private final int maxScore;

    public VelocityRule(@Value("${fraud.rules.velocity.max-transfers-per-window:5}") int maxTransfersPerWindow,
                         @Value("${fraud.rules.velocity.max-score:40}") int maxScore) {
        this.maxTransfersPerWindow = maxTransfersPerWindow;
        this.maxScore = maxScore;
    }

    @Override
    public RuleResult evaluate(PaymentCreatedEvent event, FraudContext context) {
        int over = context.recentTransferCount() - maxTransfersPerWindow;
        if (over <= 0) {
            return RuleResult.noFinding("VELOCITY");
        }

        int score = Math.min(maxScore, over * 10);
        String explanation = context.recentTransferCount() + " transfers from this account in the current window (limit "
                + maxTransfersPerWindow + ")";
        return new RuleResult("VELOCITY", score, explanation);
    }
}
