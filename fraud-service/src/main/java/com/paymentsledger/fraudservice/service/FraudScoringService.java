package com.paymentsledger.fraudservice.service;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import com.paymentsledger.fraudservice.rules.FraudRule;
import com.paymentsledger.fraudservice.rules.FraudContext;
import com.paymentsledger.fraudservice.rules.RuleResult;
import com.paymentsledger.fraudservice.rules.ScoringResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs every registered FraudRule (Spring injects all FraudRule beans --
 * adding a new rule later is just adding a new @Component, no change here),
 * sums the scores of whichever rules actually fired, and maps the total to
 * a decision via two configurable thresholds. See docs/DECISIONS.md for
 * the starting threshold values and why they're deliberately conservative.
 */
@Service
public class FraudScoringService {

    private final List<FraudRule> rules;
    private final int reviewThreshold;
    private final int blockThreshold;

    public FraudScoringService(List<FraudRule> rules,
                                @Value("${fraud.thresholds.review:30}") int reviewThreshold,
                                @Value("${fraud.thresholds.block:70}") int blockThreshold) {
        this.rules = rules;
        this.reviewThreshold = reviewThreshold;
        this.blockThreshold = blockThreshold;
    }

    public ScoringResult score(PaymentCreatedEvent event, FraudContext context) {
        List<RuleResult> fired = rules.stream()
                .map(rule -> rule.evaluate(event, context))
                .filter(result -> result.score() > 0)
                .toList();

        int totalScore = Math.min(100, fired.stream().mapToInt(RuleResult::score).sum());

        String decision;
        if (totalScore >= blockThreshold) {
            decision = "BLOCK";
        } else if (totalScore >= reviewThreshold) {
            decision = "REVIEW";
        } else {
            decision = "ALLOW";
        }

        return new ScoringResult(decision, totalScore, fired);
    }
}
