package com.paymentsledger.fraudservice.exception;

import java.util.UUID;

public class DecisionAlreadyResolvedException extends RuntimeException {

    public DecisionAlreadyResolvedException(UUID id) {
        super("Fraud decision " + id + " is not open for review (already resolved, or wasn't flagged for review)");
    }
}
