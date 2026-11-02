package com.paymentsledger.paymentservice.exception;

/**
 * Thrown when the same Idempotency-Key header is sent with a materially
 * different request body -- reusing a key is only safe for retrying the
 * exact same request.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
