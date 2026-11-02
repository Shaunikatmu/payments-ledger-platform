package com.paymentsledger.fraudservice.kafka;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import com.paymentsledger.fraudservice.repository.FraudDecisionReasonRepository;
import com.paymentsledger.fraudservice.repository.FraudDecisionRepository;
import com.paymentsledger.fraudservice.repository.ProcessedPaymentEventRepository;
import com.paymentsledger.fraudservice.rules.RuleResult;
import com.paymentsledger.fraudservice.rules.ScoringResult;
import com.paymentsledger.fraudservice.service.AccountStatsService;
import com.paymentsledger.fraudservice.service.FraudScoringService;
import com.paymentsledger.fraudservice.service.VelocityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the idempotent-consumption logic directly on the handler bean --
 * no embedded Kafka broker needed (see its Javadoc for why it's split out
 * from the @KafkaListener).
 */
@ExtendWith(MockitoExtension.class)
class PaymentCreatedHandlerTest {

    @Mock
    private ProcessedPaymentEventRepository processedPaymentEventRepository;
    @Mock
    private VelocityTracker velocityTracker;
    @Mock
    private AccountStatsService accountStatsService;
    @Mock
    private FraudScoringService scoringService;
    @Mock
    private FraudDecisionRepository decisionRepository;
    @Mock
    private FraudDecisionReasonRepository reasonRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private PaymentCreatedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentCreatedHandler(processedPaymentEventRepository, velocityTracker, accountStatsService,
                scoringService, decisionRepository, reasonRepository, kafkaTemplate, new SimpleMeterRegistry());
    }

    @Test
    void aPreviouslyProcessedEventIsSkippedEntirely() {
        PaymentCreatedEvent event = event();
        when(processedPaymentEventRepository.existsById(event.eventId())).thenReturn(true);

        handler.handle(event);

        verifyNoInteractions(velocityTracker, accountStatsService, scoringService, decisionRepository, reasonRepository, kafkaTemplate);
    }

    @Test
    void aNewEventIsScoredPersistedAndPublishedExactlyOnce() {
        PaymentCreatedEvent event = event();
        when(processedPaymentEventRepository.existsById(event.eventId())).thenReturn(false);
        when(velocityTracker.recordAndCountRecentTransfers(any(), any(), any())).thenReturn(1);
        when(accountStatsService.recordAndGetPriorStats(any(), anyLong())).thenReturn(new AccountStatsService.PriorStats(5, 1_000L));
        when(accountStatsService.recordAndCheckIsNewRecipient(any(), any())).thenReturn(false);
        when(scoringService.score(any(), any())).thenReturn(
                new ScoringResult("ALLOW", 10, List.of(new RuleResult("VELOCITY", 10, "within normal range"))));
        stubSuccessfulKafkaSend();

        handler.handle(event);

        verify(decisionRepository, times(1)).save(any());
        verify(reasonRepository, times(1)).save(any());
        verify(processedPaymentEventRepository, times(1)).save(any());
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.FRAUD_DECISION), eq(event.transferId().toString()), any());
    }

    @Test
    void aKafkaPublishFailureRollsBackBeforeMarkingTheEventProcessed() {
        PaymentCreatedEvent event = event();
        when(processedPaymentEventRepository.existsById(event.eventId())).thenReturn(false);
        when(velocityTracker.recordAndCountRecentTransfers(any(), any(), any())).thenReturn(1);
        when(accountStatsService.recordAndGetPriorStats(any(), anyLong())).thenReturn(new AccountStatsService.PriorStats(0, 0));
        when(accountStatsService.recordAndCheckIsNewRecipient(any(), any())).thenReturn(false);
        when(scoringService.score(any(), any())).thenReturn(new ScoringResult("ALLOW", 0, List.of()));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unreachable")));

        try {
            handler.handle(event);
        } catch (IllegalStateException expected) {
            // The @Transactional method is expected to throw so Spring rolls
            // back the decision/reason rows -- the "processed" marker must
            // never be written in this path.
        }

        verify(processedPaymentEventRepository, never()).save(any());
    }

    private void stubSuccessfulKafkaSend() {
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private PaymentCreatedEvent event() {
        return new PaymentCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1_000L, Instant.now());
    }
}
