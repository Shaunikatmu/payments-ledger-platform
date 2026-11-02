package com.paymentsledger.fraudservice.service;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import com.paymentsledger.fraudservice.rules.FraudContext;
import com.paymentsledger.fraudservice.rules.FraudRule;
import com.paymentsledger.fraudservice.rules.RuleResult;
import com.paymentsledger.fraudservice.rules.ScoringResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScoringServiceTest {

    @Test
    void allowsWhenNoRuleFires() {
        FraudScoringService service = new FraudScoringService(List.of(fakeRule(0), fakeRule(0)), 30, 70);

        ScoringResult result = service.score(event(), context());

        assertThat(result.decision()).isEqualTo("ALLOW");
        assertThat(result.riskScore()).isZero();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void reviewsAtTheReviewThresholdButBelowBlock() {
        FraudScoringService service = new FraudScoringService(List.of(fakeRule(20), fakeRule(15)), 30, 70);

        ScoringResult result = service.score(event(), context());

        assertThat(result.riskScore()).isEqualTo(35);
        assertThat(result.decision()).isEqualTo("REVIEW");
        assertThat(result.reasons()).hasSize(2);
    }

    @Test
    void blocksAtOrAboveTheBlockThreshold() {
        FraudScoringService service = new FraudScoringService(List.of(fakeRule(50), fakeRule(30)), 30, 70);

        ScoringResult result = service.score(event(), context());

        assertThat(result.riskScore()).isEqualTo(80);
        assertThat(result.decision()).isEqualTo("BLOCK");
    }

    @Test
    void totalScoreIsCappedAtOneHundred() {
        FraudScoringService service = new FraudScoringService(List.of(fakeRule(70), fakeRule(60)), 30, 70);

        ScoringResult result = service.score(event(), context());

        assertThat(result.riskScore()).isEqualTo(100);
    }

    @Test
    void rulesThatDidNotFireAreExcludedFromTheReasonList() {
        FraudScoringService service = new FraudScoringService(List.of(fakeRule(0), fakeRule(25)), 30, 70);

        ScoringResult result = service.score(event(), context());

        assertThat(result.reasons()).hasSize(1);
    }

    private FraudRule fakeRule(int score) {
        return (event, context) -> score == 0 ? RuleResult.noFinding("FAKE") : new RuleResult("FAKE", score, "fake reason");
    }

    private PaymentCreatedEvent event() {
        return new PaymentCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1_000L, Instant.now());
    }

    private FraudContext context() {
        return new FraudContext(0, 0, 0, false);
    }
}
