package com.paymentsledger.paymentservice.exception;

import java.util.UUID;

public class SelfTransferException extends RuntimeException {

    public SelfTransferException(UUID accountId) {
        super("An account cannot transfer to itself: " + accountId);
    }
}
