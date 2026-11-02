package com.paymentsledger.paymentservice.api.dto;

import com.paymentsledger.paymentservice.domain.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String ownerName,
        String currency,
        long balanceCents,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerName(),
                account.getCurrency(),
                account.getBalanceCents(),
                account.getCreatedAt()
        );
    }
}
