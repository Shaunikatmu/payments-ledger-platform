package com.paymentsledger.paymentservice.api.dto;

import com.paymentsledger.paymentservice.domain.JournalEntry;

import java.time.Instant;
import java.util.UUID;

public record JournalEntryResponse(
        UUID id,
        UUID transferId,
        String direction,
        long amountCents,
        Instant createdAt
) {
    public static JournalEntryResponse from(JournalEntry entry) {
        return new JournalEntryResponse(
                entry.getId(),
                entry.getTransferId(),
                entry.getDirection().name(),
                entry.getAmountCents(),
                entry.getCreatedAt()
        );
    }
}
