package com.paymentsledger.paymentservice.repository;

import com.paymentsledger.paymentservice.domain.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
