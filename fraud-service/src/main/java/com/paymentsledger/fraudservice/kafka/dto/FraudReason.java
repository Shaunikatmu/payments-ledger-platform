package com.paymentsledger.fraudservice.kafka.dto;

public record FraudReason(String ruleName, int score, String explanation) {
}
