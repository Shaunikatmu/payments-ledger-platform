package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NewRecipientRuleTest {

    private final NewRecipientRule rule = new NewRecipientRule(50_000L, 30);

    @Test
    void noFindingForAnExistingRecipient() {
        RuleResult result = rule.evaluate(event(100_000L), new FraudContext(0, 0, 0, false));

        assertThat(result.score()).isZero();
    }

    @Test
    void noFindingForASmallFirstTimeTransfer() {
        // New recipient, but a small routine amount -- shouldn't be flagged
        // on its own, otherwise every first-ever small transfer would fire.
        RuleResult result = rule.evaluate(event(5_000L), new FraudContext(0, 0, 0, true));

        assertThat(result.score()).isZero();
    }

    @Test
    void flagsALargeTransferToANewRecipient() {
        RuleResult result = rule.evaluate(event(100_000L), new FraudContext(0, 0, 0, true));

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.ruleName()).isEqualTo("NEW_RECIPIENT");
    }

    private PaymentCreatedEvent event(long amountCents) {
        return new PaymentCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amountCents, Instant.now());
    }
}
