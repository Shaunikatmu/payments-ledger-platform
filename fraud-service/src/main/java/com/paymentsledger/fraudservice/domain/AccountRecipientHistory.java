package com.paymentsledger.fraudservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_recipient_history")
public class AccountRecipientHistory {

    @Id
    private UUID id;

    @Column(name = "from_account_id", nullable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private UUID toAccountId;

    @Column(name = "transfer_count", nullable = false)
    private long transferCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    protected AccountRecipientHistory() {
        // JPA
    }

    public AccountRecipientHistory(UUID id, UUID fromAccountId, UUID toAccountId, Instant firstSeenAt) {
        this.id = id;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.transferCount = 0;
        this.firstSeenAt = firstSeenAt;
    }

    public void recordTransfer() {
        this.transferCount += 1;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public long getTransferCount() {
        return transferCount;
    }
}
