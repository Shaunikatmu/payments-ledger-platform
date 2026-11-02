package com.paymentsledger.fraudservice.service;

import com.paymentsledger.fraudservice.domain.FraudDecision;
import com.paymentsledger.fraudservice.domain.ReviewOutcome;
import com.paymentsledger.fraudservice.exception.DecisionAlreadyResolvedException;
import com.paymentsledger.fraudservice.exception.FraudDecisionNotFoundException;
import com.paymentsledger.fraudservice.kafka.KafkaTopics;
import com.paymentsledger.fraudservice.kafka.dto.FraudDecisionEvent;
import com.paymentsledger.fraudservice.kafka.dto.FraudReason;
import com.paymentsledger.fraudservice.repository.FraudDecisionRepository;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a REVIEW decision once a human has looked at it, and republishes
 * a final fraud.decision event (ALLOW or BLOCK) so payment-service's
 * FraudDecisionHandler can finally settle the transfer it's been holding.
 * This is what closes the loop started when PaymentCreatedHandler couldn't
 * auto-decide: the same event type, same topic, same idempotent consumer
 * on the other end -- payment-service doesn't need to know a human was
 * involved at all.
 */
@Service
public class FraudReviewService {

    private final FraudDecisionRepository decisionRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public FraudReviewService(FraudDecisionRepository decisionRepository, KafkaTemplate<String, Object> kafkaTemplate) {
        this.decisionRepository = decisionRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public FraudDecision review(UUID decisionId, ReviewOutcome outcome, String note) {
        FraudDecision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new FraudDecisionNotFoundException(decisionId));

        if (!decision.isOpenForReview()) {
            throw new DecisionAlreadyResolvedException(decisionId);
        }

        decision.applyReview(outcome, note, Instant.now());
        decisionRepository.save(decision);

        publishFinalDecision(decision, outcome);

        return decision;
    }

    private void publishFinalDecision(FraudDecision decision, ReviewOutcome outcome) {
        String finalDecision = outcome == ReviewOutcome.CONFIRMED_FRAUD ? "BLOCK" : "ALLOW";
        List<FraudReason> reasons = List.of(new FraudReason("MANUAL_REVIEW", 0, "Resolved by manual review: " + outcome));
        // This correlation ID is the REVIEW request's own (set by
        // CorrelationIdFilter on this REST call), not the original
        // transfer-creation request's -- a human resolving a flagged
        // transfer is a distinct, later operation with its own trace, and
        // that's the more useful thing to see in payment-service's logs
        // when this event lands and finally settles the transfer.
        FraudDecisionEvent event = new FraudDecisionEvent(
                UUID.randomUUID(), decision.getTransferId(), finalDecision, decision.getRiskScore(), reasons,
                Instant.now(), MDC.get("correlationId"));

        try {
            kafkaTemplate.send(KafkaTopics.FRAUD_DECISION, decision.getTransferId().toString(), event).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to publish reviewed fraud.decision for transfer " + decision.getTransferId(), ex);
        }
    }
}
