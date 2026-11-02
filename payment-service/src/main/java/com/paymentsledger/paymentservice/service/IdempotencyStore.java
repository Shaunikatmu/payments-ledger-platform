package com.paymentsledger.paymentservice.service;

import com.paymentsledger.paymentservice.domain.IdempotencyRecord;
import com.paymentsledger.paymentservice.repository.IdempotencyKeyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Claims and completes idempotency records in their own, separate
 * transactions (REQUIRES_NEW).
 *
 * This has to be a distinct bean from IdempotencyService: Spring's
 * @Transactional is implemented as a proxy around the bean, and a proxy
 * only intercepts calls that arrive from OUTSIDE the bean. If claim()/
 * complete() lived on IdempotencyService and were called from its own
 * execute() method, the annotation would silently do nothing (the
 * "self-invocation" problem). Splitting it into its own bean forces the
 * call through the proxy so REQUIRES_NEW actually takes effect.
 *
 * REQUIRES_NEW matters here specifically: it commits the "claim" row
 * immediately, independent of however long the winning request's business
 * transaction takes. Without it, concurrent requests wouldn't see the claim
 * until the whole outer transaction committed, and they'd all block on the
 * insert instead of failing fast and polling.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyKeyRepository repository;

    public IdempotencyStore(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String key, String requestPath, String requestHash) {
        try {
            repository.saveAndFlush(new IdempotencyRecord(key, requestPath, requestHash, Instant.now()));
            return true;
        } catch (DataIntegrityViolationException alreadyClaimed) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, int responseStatus, String responseBody) {
        IdempotencyRecord record = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record disappeared for key " + key));
        record.complete(responseStatus, responseBody, Instant.now());
        repository.save(record);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void free(String key) {
        repository.deleteById(key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return repository.findById(key);
    }
}
