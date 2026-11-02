package com.paymentsledger.paymentservice.exception;

/**
 * Thrown when another request is still processing the same Idempotency-Key
 * and hasn't finished within our poll budget. The client should retry with
 * the same key later rather than assume failure.
 */
public class IdempotencyTimeoutException extends RuntimeException {

    public IdempotencyTimeoutException(String key) {
        super("Timed out waiting for the original request with Idempotency-Key '" + key + "' to complete");
    }
}
