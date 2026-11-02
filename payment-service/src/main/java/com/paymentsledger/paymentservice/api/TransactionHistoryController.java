package com.paymentsledger.paymentservice.api;

import com.paymentsledger.paymentservice.api.dto.JournalEntryResponse;
import com.paymentsledger.paymentservice.repository.JournalEntryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts/{accountId}/transactions")
public class TransactionHistoryController {

    private final JournalEntryRepository journalEntryRepository;

    public TransactionHistoryController(JournalEntryRepository journalEntryRepository) {
        this.journalEntryRepository = journalEntryRepository;
    }

    @GetMapping
    public List<JournalEntryResponse> listTransactions(@PathVariable("accountId") UUID accountId) {
        return journalEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(JournalEntryResponse::from)
                .toList();
    }
}
