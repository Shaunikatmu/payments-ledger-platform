package com.paymentsledger.fraudservice.kafka;

import com.paymentsledger.fraudservice.domain.Decision;
import com.paymentsledger.fraudservice.domain.FraudDecision;
import com.paymentsledger.fraudservice.domain.FraudDecisionReason;
import com.paymentsledger.fraudservice.domain.ProcessedPaymentEvent;
import com.paymentsledger.fraudservice.kafka.dto.FraudDecisionEvent;
import com.paymentsledger.fraudservice.kafka.dto.FraudReason;
import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import com.paymentsledger.fraudservice.repository.FraudDecisionReasonRepository;
import com.paymentsledger.fraudservice.repository.FraudDecisionRepository;
import com.paymentsledger.fraudservice.repository.ProcessedPaymentEventRepository;
import com.paymentsledger.fraudservice.rules.FraudContext;
import com.paymentsledger.fraudservice.rules.RuleResult;
import com.paymentsledger.fraudservice.rules.ScoringResult;
import com.paymentsledger.fraudservice.service.AccountStatsService;
import com.paymentsledger.fraudservice.service.FraudScoringService;
import com.paymentsledger.fraudservice.service.VelocityTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The business logic behind the payment.created @KafkaListener, pulled into
 * a plain Spring bean so it's directly unit-testable without an embedded
 * Kafka broker (see PaymentCreatedHandlerTest).
 *
 * Unlike payment-service's fraud.decision handling, this does NOT use the
 * outbox pattern -- the Kafka publish happens inside the same
 * @Transactional method as persisting the decision. See docs/DECISIONS.md
 * for why that asymmetry is a deliberate, scoped trade-off: losing a
 * fraud.decision here just means a transfer sits in PENDING a little
 * longer until it's naturally reprocessed, never a lost or double-applied
 * ledger entry.
 */
@Service
public class PaymentCreatedHandler {

    private final ProcessedPaymentEventRepository processedPaymentEventRepository;
    private final VelocityTracker velocityTracker;
    private final AccountStatsService accountStatsService;
    private final FraudScoringService scoringService;
    private final FraudDecisionRepository decisionRepository;
    private final FraudDecisionReasonRepository reasonRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    public PaymentCreatedHandler(ProcessedPaymentEventRepository processedPaymentEventRepository,
                                  VelocityTracker velocityTracker,
                                  AccountStatsService accountStatsService,
                                  FraudScoringService scoringService,
                                  FraudDecisionRepository decisionRepository,
                                  FraudDecisionReasonRepository reasonRepository,
                                  KafkaTemplate<String, Object> kafkaTemplate,
                                  MeterRegistry meterRegistry) {
        this.processedPaymentEventRepository = processedPaymentEventRepository;
        this.velocityTracker = velocityTracker;
        this.accountStatsService = accountStatsService;
        this.scoringService = scoringService;
        this.decisionRepository = decisionRepository;
        this.reasonRepository = reasonRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void handle(PaymentCreatedEvent event) {
        if (processedPaymentEventRepository.existsById(event.eventId())) {
            return;
        }

        int recentCount = velocityTracker.recordAndCountRecentTransfers(event.fromAccountId(), event.eventId(), event.occurredAt());
        AccountStatsService.PriorStats priorStats = accountStatsService.recordAndGetPriorStats(event.fromAccountId(), event.amountCents());
        boolean isNewRecipient = accountStatsService.recordAndCheckIsNewRecipient(event.fromAccountId(), event.toAccountId());

        FraudContext context = new FraudContext(recentCount, priorStats.transactionCount(), priorStats.averageAmountCents(), isNewRecipient);
        ScoringResult result = Timer.builder("fraud.scoring.duration")
                .register(meterRegistry)
                .record(() -> scoringService.score(event, context));
        Counter.builder("fraud.decisions").tag("decision", result.decision()).register(meterRegistry).increment();

        FraudDecision decision = new FraudDecision(
                UUID.randomUUID(), event.transferId(), event.fromAccountId(), event.toAccountId(), event.amountCents(),
                Decision.valueOf(result.decision()), result.riskScore(), Instant.now());
        decisionRepository.save(decision);

        for (RuleResult reason : result.reasons()) {
            reasonRepository.save(new FraudDecisionReason(UUID.randomUUID(), decision.getId(), reason.ruleName(), reason.score(), reason.explanation()));
        }

        publishDecision(event, decision, result);

        processedPaymentEventRepository.save(new ProcessedPaymentEvent(event.eventId(), Instant.now()));
    }

    private void publishDecision(PaymentCreatedEvent event, FraudDecision decision, ScoringResult result) {
        List<FraudReason> reasons = result.reasons().stream()
                .map(r -> new FraudReason(r.ruleName(), r.score(), r.explanation()))
                .toList();
        FraudDecisionEvent decisionEvent = new FraudDecisionEvent(
                UUID.randomUUID(), event.transferId(), decision.getDecision().name(), decision.getRiskScore(), reasons,
                Instant.now(), event.correlationId());

        try {
            kafkaTemplate.send(KafkaTopics.FRAUD_DECISION, event.transferId().toString(), decisionEvent).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // Thrown from inside the @Transactional method -- rolls back the
            // decision/reason/processed-event rows too, so the next
            // redelivery of this same payment.created event retries cleanly
            // from scratch rather than leaving a decision nothing ever heard
            // about.
            throw new IllegalStateException("Failed to publish fraud.decision for transfer " + event.transferId(), ex);
        }
    }
}
