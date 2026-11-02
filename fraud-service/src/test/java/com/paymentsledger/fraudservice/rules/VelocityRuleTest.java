package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityRuleTest {

    private final VelocityRule rule = new VelocityRule(5, 40);

    @Test
    void noFindingWhenUnderTheLimit() {
        RuleResult result = rule.evaluate(event(), context(5));

        assertThat(result.score()).isZero();
    }

    @Test
    void scoresProportionallyToHowFarOverTheLimit() {
        RuleResult result = rule.evaluate(event(), context(8));

        assertThat(result.score()).isEqualTo(30); // 3 over the limit of 5, at 10 points each
        assertThat(result.ruleName()).isEqualTo("VELOCITY");
        assertThat(result.explanation()).contains("8 transfers");
    }

    @Test
    void scoreIsCappedAtMaxScore() {
        RuleResult result = rule.evaluate(event(), context(50));

        assertThat(result.score()).isEqualTo(40);
    }

    private PaymentCreatedEvent event() {
        return new PaymentCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1_000L, Instant.now());
    }

    private FraudContext context(int recentTransferCount) {
        return new FraudContext(recentTransferCount, 0, 0, false);
    }
}
