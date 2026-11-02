package com.paymentsledger.paymentservice.kafka;

import com.paymentsledger.paymentservice.kafka.dto.FraudDecisionEvent;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FraudDecisionConsumer {

    private static final String CORRELATION_ID_KEY = "correlationId";

    private final FraudDecisionHandler handler;

    public FraudDecisionConsumer(FraudDecisionHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = KafkaTopics.FRAUD_DECISION, groupId = "payment-service")
    public void onMessage(FraudDecisionEvent event) {
        // Restores the correlation ID carried in the event payload onto
        // this consumer thread's MDC, so every log line produced while
        // applying this decision (settlement, the compensating reversal,
        // etc) ties back to whichever request originally created the
        // transfer -- even though that request finished and its own
        // thread is long gone by the time this runs. Cleared in the
        // finally block since Kafka consumer threads are reused across
        // messages, not thrown away after one.
        if (event.correlationId() != null) {
            MDC.put(CORRELATION_ID_KEY, event.correlationId());
        }
        try {
            handler.handle(event);
        } finally {
            MDC.remove(CORRELATION_ID_KEY);
        }
    }
}
