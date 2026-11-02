package com.paymentsledger.fraudservice.kafka;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentCreatedConsumer {

    private static final String CORRELATION_ID_KEY = "correlationId";

    private final PaymentCreatedHandler handler;

    public PaymentCreatedConsumer(PaymentCreatedHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENT_CREATED, groupId = "fraud-service")
    public void onMessage(PaymentCreatedEvent event) {
        // Same correlation-ID handoff as payment-service's
        // FraudDecisionConsumer, in the other direction: this is where the
        // originating HTTP request's correlation ID first becomes visible
        // on fraud-service's side, for the whole scoring pipeline's logs.
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
