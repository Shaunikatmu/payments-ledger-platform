package com.paymentsledger.paymentservice.domain;

/**
 * PENDING -> COMPLETED (fraud ALLOW), PENDING -> REJECTED (fraud BLOCK), or
 * PENDING -> UNDER_REVIEW -> COMPLETED/REJECTED once a human resolves it via
 * fraud-service's review queue.
 *
 * APPROVED exists in the lifecycle name but isn't persisted as its own
 * state today: nothing happens between "fraud approved it" and "ledger
 * entries posted" in this scope, so they're applied atomically as
 * COMPLETED. It's a seam for a real settlement delay later, not dead code.
 */
public enum TransferStatus {
    PENDING,
    APPROVED,
    UNDER_REVIEW,
    COMPLETED,
    REJECTED
}
