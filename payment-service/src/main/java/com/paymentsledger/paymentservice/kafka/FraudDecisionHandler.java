package com.paymentsledger.paymentservice.kafka;

import com.paymentsledger.paymentservice.domain.ProcessedFraudDecision;
import com.paymentsledger.paymentservice.kafka.dto.FraudDecisionEvent;
import com.paymentsledger.paymentservice.repository.ProcessedFraudDecisionRepository;
import com.paymentsledger.paymentservice.service.TransferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The actual business logic behind the fraud.decision @KafkaListener,
 * pulled into a plain Spring bean so it can be unit-tested directly without
 * an embedded Kafka broker (see FraudDecisionHandlerTest).
 *
 * Idempotency: Kafka delivers at-least-once, so the same event can arrive
 * more than once (consumer restart before offset commit, rebalance, etc).
 * Recording the eventId in processed_fraud_decisions inside the SAME
 * transaction as applying the decision means a duplicate delivery either
 * short-circuits at the existsById check, or -- in the narrow race where
 * two deliveries are mid-flight at once -- fails on the table's primary
 * key and rolls back the whole transaction, so the decision is never
 * applied twice either way.
 */
@Service
public class FraudDecisionHandler {

    private final ProcessedFraudDecisionRepository processedFraudDecisionRepository;
    private final TransferService transferService;

    public FraudDecisionHandler(ProcessedFraudDecisionRepository processedFraudDecisionRepository, TransferService transferService) {
        this.processedFraudDecisionRepository = processedFraudDecisionRepository;
        this.transferService = transferService;
    }

    @Transactional
    public void handle(FraudDecisionEvent event) {
        if (processedFraudDecisionRepository.existsById(event.eventId())) {
            return;
        }

        transferService.applyFraudDecision(event);
        processedFraudDecisionRepository.save(new ProcessedFraudDecision(event.eventId(), Instant.now()));
    }
}
