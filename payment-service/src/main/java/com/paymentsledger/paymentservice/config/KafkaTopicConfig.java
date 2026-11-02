package com.paymentsledger.paymentservice.config;

import com.paymentsledger.paymentservice.kafka.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service produces/consumes so they exist with a
 * known partition count up front, instead of relying on Kafka's
 * auto.create.topics.enable default (which would create them with whatever
 * the broker default happens to be). Spring's KafkaAdmin applies these
 * idempotently -- a no-op if the topic already exists.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic paymentCreatedTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_CREATED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic fraudDecisionTopic() {
        return TopicBuilder.name(KafkaTopics.FRAUD_DECISION).partitions(1).replicas(1).build();
    }
}
