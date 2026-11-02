package com.paymentsledger.paymentservice.api.dto;

import com.paymentsledger.paymentservice.domain.Transfer;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        String status,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmountCents(),
                transfer.getStatus().name(),
                transfer.getRejectionReason(),
                transfer.getCreatedAt(),
                transfer.getUpdatedAt()
        );
    }
}
