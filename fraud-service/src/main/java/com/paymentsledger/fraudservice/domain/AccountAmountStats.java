package com.paymentsledger.fraudservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_amount_stats")
public class AccountAmountStats {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "transaction_count", nullable = false)
    private long transactionCount;

    @Column(name = "total_amount_cents", nullable = false)
    private long totalAmountCents;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountAmountStats() {
        // JPA
    }

    public AccountAmountStats(UUID accountId, long transactionCount, long totalAmountCents, Instant updatedAt) {
        this.accountId = accountId;
        this.transactionCount = transactionCount;
        this.totalAmountCents = totalAmountCents;
        this.updatedAt = updatedAt;
    }

    public void record(long amountCents, Instant now) {
        this.transactionCount += 1;
        this.totalAmountCents += amountCents;
        this.updatedAt = now;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getTransactionCount() {
        return transactionCount;
    }

    public long getTotalAmountCents() {
        return totalAmountCents;
    }
}
