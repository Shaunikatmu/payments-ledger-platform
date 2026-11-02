package com.paymentsledger.fraudservice.exception;

import java.util.UUID;

public class FraudDecisionNotFoundException extends RuntimeException {

    public FraudDecisionNotFoundException(UUID id) {
        super("Fraud decision not found: " + id);
    }
}
