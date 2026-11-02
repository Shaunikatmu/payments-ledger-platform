package com.paymentsledger.paymentservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    public static final UUID EXTERNAL_FUNDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private String currency;

    @Column(name = "balance_cents", nullable = false)
    private long balanceCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // JPA
    }

    public Account(UUID id, String ownerName, String currency, long balanceCents, AccountType accountType, Instant createdAt) {
        this.id = id;
        this.ownerName = ownerName;
        this.currency = currency;
        this.balanceCents = balanceCents;
        this.accountType = accountType;
        this.createdAt = createdAt;
    }

    /**
     * Moves {@code amountCents} out of this account. CUSTOMER accounts can
     * never go negative -- callers are expected to check
     * {@link #hasSufficientFunds(long)} first and treat a shortfall as a
     * REJECTED transfer, not an exception; this is a last-line invariant
     * guard, not the primary control flow.
     */
    public void debit(long amountCents) {
        if (accountType == AccountType.CUSTOMER && balanceCents < amountCents) {
            throw new IllegalStateException("Account " + id + " has insufficient funds for a debit of " + amountCents);
        }
        this.balanceCents -= amountCents;
    }

    public void credit(long amountCents) {
        this.balanceCents += amountCents;
    }

    public boolean hasSufficientFunds(long amountCents) {
        return accountType == AccountType.SYSTEM || balanceCents >= amountCents;
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceCents() {
        return balanceCents;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
