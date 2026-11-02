package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flags a transfer that's unusually large compared to the sender's own
 * history. Requires a minimum amount of history first -- without that
 * guard, every account's very first transfer would look anomalous against
 * a zero/undefined baseline.
 */
@Component
public class AmountAnomalyRule implements FraudRule {

    private final long minHistory;
    private final double multiplier;
    private final int maxScore;

    public AmountAnomalyRule(@Value("${fraud.rules.amount-anomaly.min-history:3}") long minHistory,
                              @Value("${fraud.rules.amount-anomaly.multiplier:3.0}") double multiplier,
                              @Value("${fraud.rules.amount-anomaly.max-score:40}") int maxScore) {
        this.minHistory = minHistory;
        this.multiplier = multiplier;
        this.maxScore = maxScore;
    }

    @Override
    public RuleResult evaluate(PaymentCreatedEvent event, FraudContext context) {
        if (context.priorTransactionCount() < minHistory || context.priorAverageAmountCents() <= 0) {
            return RuleResult.noFinding("AMOUNT_ANOMALY");
        }

        double ratio = (double) event.amountCents() / (double) context.priorAverageAmountCents();
        if (ratio < multiplier) {
            return RuleResult.noFinding("AMOUNT_ANOMALY");
        }

        int score = (int) Math.min(maxScore, Math.round((ratio - multiplier + 1) * 10));
        String explanation = String.format(
                "Amount is %.1fx this account's average transfer (based on %d prior transfers)",
                ratio, context.priorTransactionCount());
        return new RuleResult("AMOUNT_ANOMALY", score, explanation);
    }
}
