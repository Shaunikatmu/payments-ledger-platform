package com.paymentsledger.paymentservice.kafka;

import com.paymentsledger.paymentservice.domain.OutboxEvent;
import com.paymentsledger.paymentservice.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The relay half of the transactional outbox pattern: payment-service's
 * business logic only ever writes a row to outbox_events, inside the same
 * DB transaction as the change it describes (see TransferService). This
 * poller is the only thing that actually talks to Kafka, on its own
 * schedule, decoupled from request latency -- so a slow or momentarily
 * unavailable Kafka broker never blocks a payment API call, and a crash
 * right after committing the transfer can never lose the event (it's
 * already durably in Postgres, waiting to be picked up).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getEventType(), event.getAggregateId().toString(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            event.markPublished(Instant.now());
            outboxEventRepository.save(event);
        } catch (Exception ex) {
            // Left unpublished -- the next poll retries it. A production
            // system would add a retry-count/backoff and a dead-letter path
            // after N failures; out of scope for this project's size.
            log.warn("Failed to publish outbox event {} ({}); will retry on the next poll", event.getId(), event.getEventType(), ex);
        }
    }
}
