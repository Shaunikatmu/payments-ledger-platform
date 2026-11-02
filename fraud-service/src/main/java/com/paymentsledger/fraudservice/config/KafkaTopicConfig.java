package com.paymentsledger.fraudservice.config;

import com.paymentsledger.fraudservice.kafka.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
