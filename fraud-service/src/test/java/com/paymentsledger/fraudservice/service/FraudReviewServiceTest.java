package com.paymentsledger.fraudservice.service;

import com.paymentsledger.fraudservice.domain.Decision;
import com.paymentsledger.fraudservice.domain.FraudDecision;
import com.paymentsledger.fraudservice.domain.ReviewOutcome;
import com.paymentsledger.fraudservice.exception.DecisionAlreadyResolvedException;
import com.paymentsledger.fraudservice.exception.FraudDecisionNotFoundException;
import com.paymentsledger.fraudservice.kafka.KafkaTopics;
import com.paymentsledger.fraudservice.kafka.dto.FraudDecisionEvent;
import com.paymentsledger.fraudservice.repository.FraudDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudReviewServiceTest {

    @Mock
    private FraudDecisionRepository decisionRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private FraudReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new FraudReviewService(decisionRepository, kafkaTemplate);
    }

    @Test
    void throwsWhenTheDecisionDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(decisionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.review(id, ReviewOutcome.FALSE_POSITIVE, "note"))
                .isInstanceOf(FraudDecisionNotFoundException.class);
    }

    @Test
    void throwsWhenTheDecisionIsNotOpenForReview() {
        FraudDecision decision = decisionWith(Decision.ALLOW);
        when(decisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));

        assertThatThrownBy(() -> reviewService.review(decision.getId(), ReviewOutcome.CONFIRMED_FRAUD, null))
                .isInstanceOf(DecisionAlreadyResolvedException.class);
    }

    @Test
    void falsePositiveOutcomePublishesAnAllowDecision() {
        FraudDecision decision = decisionWith(Decision.REVIEW);
        when(decisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));
        when(decisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubSuccessfulKafkaSend();

        reviewService.review(decision.getId(), ReviewOutcome.FALSE_POSITIVE, "cleared on review");

        ArgumentCaptor<FraudDecisionEvent> captor = ArgumentCaptor.forClass(FraudDecisionEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.FRAUD_DECISION), eq(decision.getTransferId().toString()), captor.capture());
        assertThat(captor.getValue().decision()).isEqualTo("ALLOW");
        assertThat(decision.getReviewedOutcome()).isEqualTo(ReviewOutcome.FALSE_POSITIVE);
        assertThat(decision.isOpenForReview()).isFalse();
    }

    @Test
    void confirmedFraudOutcomePublishesABlockDecision() {
        FraudDecision decision = decisionWith(Decision.REVIEW);
        when(decisionRepository.findById(decision.getId())).thenReturn(Optional.of(decision));
        when(decisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        stubSuccessfulKafkaSend();

        reviewService.review(decision.getId(), ReviewOutcome.CONFIRMED_FRAUD, "confirmed fraud");

        ArgumentCaptor<FraudDecisionEvent> captor = ArgumentCaptor.forClass(FraudDecisionEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.FRAUD_DECISION), eq(decision.getTransferId().toString()), captor.capture());
        assertThat(captor.getValue().decision()).isEqualTo("BLOCK");
    }

    private void stubSuccessfulKafkaSend() {
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(sendResult));
    }

    private FraudDecision decisionWith(Decision decision) {
        return new FraudDecision(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000L, decision, 50, Instant.now());
    }
}
