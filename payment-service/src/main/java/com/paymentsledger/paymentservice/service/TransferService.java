package com.paymentsledger.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.paymentservice.api.dto.CreateTransferRequest;
import com.paymentsledger.paymentservice.domain.Account;
import com.paymentsledger.paymentservice.domain.Direction;
import com.paymentsledger.paymentservice.domain.JournalEntry;
import com.paymentsledger.paymentservice.domain.OutboxEvent;
import com.paymentsledger.paymentservice.domain.Transfer;
import com.paymentsledger.paymentservice.exception.AccountNotFoundException;
import com.paymentsledger.paymentservice.exception.SelfTransferException;
import com.paymentsledger.paymentservice.kafka.KafkaTopics;
import com.paymentsledger.paymentservice.kafka.dto.FraudDecisionEvent;
import com.paymentsledger.paymentservice.kafka.dto.PaymentCreatedEvent;
import com.paymentsledger.paymentservice.repository.AccountRepository;
import com.paymentsledger.paymentservice.repository.JournalEntryRepository;
import com.paymentsledger.paymentservice.repository.OutboxEventRepository;
import com.paymentsledger.paymentservice.repository.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Transfers are now asynchronous: this class only ever gets a transfer to
 * PENDING (or an immediate REJECTED for insufficient funds -- that doesn't
 * need a fraud opinion). Everything past that point is driven by a
 * fraud.decision event arriving later, handled in applyFraudDecision.
 *
 * The key design point is the funds "hold": when a transfer is accepted,
 * the sender's funds are debited and a DEBIT journal entry is written
 * immediately -- not deferred until approval. Only the matching CREDIT to
 * the recipient waits for fraud-service's decision. This is what keeps the
 * Phase 2 concurrency guarantees (no overdraft, no stale-balance races)
 * true even though settlement is now async: the money is safely removed
 * from circulation the moment it's committed to a transfer, exactly like a
 * real payment hold. If fraud later blocks it, rejectAndReverse() credits
 * the sender back via a compensating journal entry -- a saga-style
 * compensating transaction, not a silent balance correction. See
 * docs/DECISIONS.md.
 */
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TransferService(AccountRepository accountRepository, TransferRepository transferRepository,
                            JournalEntryRepository journalEntryRepository, OutboxEventRepository outboxEventRepository,
                            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Transfer executeTransfer(CreateTransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new SelfTransferException(request.fromAccountId());
        }

        // Fixed lock order (lower UUID first) regardless of sender/recipient
        // role, to avoid deadlocking against a transfer running the
        // opposite direction concurrently. See docs/DECISIONS.md.
        UUID lockFirst = request.fromAccountId().compareTo(request.toAccountId()) < 0
                ? request.fromAccountId() : request.toAccountId();
        UUID lockSecond = lockFirst.equals(request.fromAccountId())
                ? request.toAccountId() : request.fromAccountId();

        Account firstLocked = accountRepository.findByIdForUpdate(lockFirst)
                .orElseThrow(() -> new AccountNotFoundException(lockFirst));
        Account secondLocked = accountRepository.findByIdForUpdate(lockSecond)
                .orElseThrow(() -> new AccountNotFoundException(lockSecond));

        Account fromAccount = firstLocked.getId().equals(request.fromAccountId()) ? firstLocked : secondLocked;
        Account toAccount = firstLocked.getId().equals(request.fromAccountId()) ? secondLocked : firstLocked;

        Instant now = Instant.now();
        Transfer transfer = new Transfer(UUID.randomUUID(), fromAccount.getId(), toAccount.getId(), request.amountCents(), now);

        if (!fromAccount.hasSufficientFunds(request.amountCents())) {
            transfer.reject("INSUFFICIENT_FUNDS", now);
            recordCreated("REJECTED", "INSUFFICIENT_FUNDS");
            return transferRepository.save(transfer);
        }

        // Hold the funds now: debit + journal entry committed immediately.
        fromAccount.debit(request.amountCents());
        accountRepository.save(fromAccount);
        journalEntryRepository.save(new JournalEntry(UUID.randomUUID(), transfer.getId(), fromAccount.getId(), Direction.DEBIT, request.amountCents(), now));

        transferRepository.save(transfer);
        enqueuePaymentCreatedEvent(transfer, now);
        recordCreated("PENDING", "NONE");

        return transfer;
    }

    /**
     * Applies a fraud.decision event. Called by FraudDecisionHandler, which
     * is responsible for the idempotency check (see its Javadoc) -- this
     * method assumes it's safe to apply and only guards against a decision
     * arriving for a transfer that's already in a terminal state (e.g. a
     * stale REVIEW decision processed after a later ALLOW/BLOCK already
     * resolved it).
     */
    @Transactional
    public void applyFraudDecision(FraudDecisionEvent event) {
        Transfer transfer = transferRepository.findById(event.transferId())
                .orElseThrow(() -> new IllegalStateException("Received a fraud decision for unknown transfer " + event.transferId()));

        if (transfer.isResolved()) {
            return;
        }

        switch (event.decision()) {
            case "ALLOW" -> { completeTransfer(transfer); recordResolved("COMPLETED", "NONE"); }
            case "BLOCK" -> { rejectAndReverse(transfer, "FRAUD_BLOCKED"); recordResolved("REJECTED", "FRAUD_BLOCKED"); }
            case "REVIEW" -> transfer.markUnderReview(Instant.now());
            default -> throw new IllegalArgumentException("Unknown fraud decision: " + event.decision());
        }

        transferRepository.save(transfer);
    }

    private void completeTransfer(Transfer transfer) {
        Instant now = Instant.now();
        Account toAccount = accountRepository.findByIdForUpdate(transfer.getToAccountId())
                .orElseThrow(() -> new AccountNotFoundException(transfer.getToAccountId()));

        toAccount.credit(transfer.getAmountCents());
        accountRepository.save(toAccount);
        journalEntryRepository.save(new JournalEntry(UUID.randomUUID(), transfer.getId(), toAccount.getId(), Direction.CREDIT, transfer.getAmountCents(), now));

        transfer.complete(now);
    }

    private void rejectAndReverse(Transfer transfer, String reason) {
        Instant now = Instant.now();
        Account fromAccount = accountRepository.findByIdForUpdate(transfer.getFromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(transfer.getFromAccountId()));

        // Compensating transaction: the original DEBIT already happened at
        // creation time (the hold), so blocking the transfer means crediting
        // it back -- not touching the original journal entry.
        fromAccount.credit(transfer.getAmountCents());
        accountRepository.save(fromAccount);
        journalEntryRepository.save(new JournalEntry(UUID.randomUUID(), transfer.getId(), fromAccount.getId(), Direction.CREDIT, transfer.getAmountCents(), now));

        transfer.reject(reason, now);
    }

    private void enqueuePaymentCreatedEvent(Transfer transfer, Instant now) {
        // The correlation ID travels INSIDE the event payload, not via MDC
        // thread-propagation -- the outbox relay publishes from a
        // @Scheduled poller on a different thread, potentially seconds
        // later, so MDC set on the original request thread would already
        // be gone. Stashing it in the serialized payload is what lets it
        // survive that async handoff. See docs/DECISIONS.md.
        PaymentCreatedEvent event = new PaymentCreatedEvent(
                UUID.randomUUID(), transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getAmountCents(), now, MDC.get("correlationId"));
        outboxEventRepository.save(new OutboxEvent(
                UUID.randomUUID(), "Transfer", transfer.getId(), KafkaTopics.PAYMENT_CREATED, serialize(event), now));
    }

    private void recordCreated(String status, String reason) {
        Counter.builder("payments.transfers.created")
                .tag("status", status)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private void recordResolved(String outcome, String reason) {
        Counter.builder("payments.transfers.resolved")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload", ex);
        }
    }
}
