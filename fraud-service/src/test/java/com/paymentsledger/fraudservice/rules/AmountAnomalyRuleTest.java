package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmountAnomalyRuleTest {

    private final AmountAnomalyRule rule = new AmountAnomalyRule(3, 3.0, 40);

    @Test
    void noFindingWithoutEnoughPriorHistory() {
        // Only 2 prior transfers, below the configured minimum of 3 -- a
        // brand-new account's first few transfers should never be flagged
        // just for having no baseline yet.
        RuleResult result = rule.evaluate(event(10_000L), new FraudContext(0, 2, 1_000L, false));

        assertThat(result.score()).isZero();
    }

    @Test
    void noFindingWhenAmountIsWithinNormalRange() {
        RuleResult result = rule.evaluate(event(2_500L), new FraudContext(0, 10, 1_000L, false));

        assertThat(result.score()).isZero();
    }

    @Test
    void flagsAnAmountFarAboveTheRollingAverage() {
        RuleResult result = rule.evaluate(event(10_000L), new FraudContext(0, 10, 1_000L, false));

        assertThat(result.score()).isGreaterThan(0);
        assertThat(result.ruleName()).isEqualTo("AMOUNT_ANOMALY");
        assertThat(result.explanation()).contains("10.0x");
    }

    private PaymentCreatedEvent event(long amountCents) {
        return new PaymentCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amountCents, Instant.now());
    }
}
