package com.paymentsledger.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.paymentservice.domain.IdempotencyRecord;
import com.paymentsledger.paymentservice.exception.IdempotencyKeyReusedException;
import com.paymentsledger.paymentservice.exception.IdempotencyTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes any POST endpoint safe to retry with the same Idempotency-Key
 * header. Guarantees: concurrent callers using the same key all receive
 * the identical response, and the wrapped action runs at most once.
 *
 * How it works (see IdempotencyStore for why claim/complete are a separate
 * bean):
 *  1. Try to claim the key (INSERT with a UNIQUE constraint, committed
 *     immediately via REQUIRES_NEW).
 *  2. Winner: runs the action, then stores its response against the key.
 *     A thrown exception at this point means nothing was persisted (a
 *     validation/not-found error), so the claim is freed for a corrected
 *     retry rather than cached.
 *  3. Losers: the claim insert fails fast on the unique constraint, so
 *     they poll the record until it's marked COMPLETED and replay the
 *     winner's exact response.
 */
@Service
public class IdempotencyService {

    // Widened from 60x50ms (3s) -- under LedgerConcurrencyIT's 50-way
    // concurrent load on a slower CI runner, losers can need more than 3s
    // to see the winner's commit, especially once the connection pool is
    // under pressure too. 100x50ms (5s) gives real headroom without making
    // a genuinely stuck request hang too long.
    private static final int MAX_POLL_ATTEMPTS = 100;
    private static final long POLL_INTERVAL_MS = 50;

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public <T> ResponseEntity<T> execute(String idempotencyKey, String requestPath, Object requestBody,
                                          Class<T> responseType, Supplier<ResponseEntity<T>> action) {
        String requestHash = hash(requestBody);
        boolean claimed = store.claim(idempotencyKey, requestPath, requestHash);

        if (!claimed) {
            return awaitResult(idempotencyKey, requestHash, responseType);
        }

        try {
            ResponseEntity<T> response = action.get();
            store.complete(idempotencyKey, response.getStatusCode().value(), serialize(response.getBody()));
            return response;
        } catch (RuntimeException ex) {
            // Nothing was persisted for a validation/not-found failure --
            // free the key so a corrected retry with the same header still
            // works, instead of caching an error against it forever.
            store.free(idempotencyKey);
            throw ex;
        }
    }

    private <T> ResponseEntity<T> awaitResult(String idempotencyKey, String requestHash, Class<T> responseType) {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Optional<IdempotencyRecord> record = store.find(idempotencyKey);
            if (record.isPresent()) {
                IdempotencyRecord found = record.get();
                if (!found.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyKeyReusedException(idempotencyKey);
                }
                if (found.isCompleted()) {
                    return ResponseEntity.status(found.getResponseStatus())
                            .body(deserialize(found.getResponseBody(), responseType));
                }
            }
            sleep();
        }
        throw new IdempotencyTimeoutException(idempotencyKey);
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for an in-flight idempotent request", ex);
        }
    }

    private String hash(Object requestBody) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash request body for idempotency check", ex);
        }
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize response for idempotency cache", ex);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize cached idempotent response", ex);
        }
    }
}
