package com.paymentsledger.paymentservice.kafka.dto;

public record FraudReason(String ruleName, int score, String explanation) {
}
