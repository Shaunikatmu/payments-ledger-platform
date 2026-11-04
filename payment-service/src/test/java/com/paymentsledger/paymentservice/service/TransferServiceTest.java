package com.paymentsledger.paymentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.paymentsledger.paymentservice.api.dto.CreateTransferRequest;
import com.paymentsledger.paymentservice.domain.Account;
import com.paymentsledger.paymentservice.domain.AccountType;
import com.paymentsledger.paymentservice.domain.Direction;
import com.paymentsledger.paymentservice.domain.JournalEntry;
import com.paymentsledger.paymentservice.domain.OutboxEvent;
import com.paymentsledger.paymentservice.domain.Transfer;
import com.paymentsledger.paymentservice.domain.TransferStatus;
import com.paymentsledger.paymentservice.exception.AccountNotFoundException;
import com.paymentsledger.paymentservice.exception.SelfTransferException;
import com.paymentsledger.paymentservice.kafka.KafkaTopics;
import com.paymentsledger.paymentservice.kafka.dto.FraudDecisionEvent;
import com.paymentsledger.paymentservice.repository.AccountRepository;
import com.paymentsledger.paymentservice.repository.JournalEntryRepository;
import com.paymentsledger.paymentservice.repository.OutboxEventRepository;
import com.paymentsledger.paymentservice.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    // Constructed manually (not via @InjectMocks) so it gets a real
    // ObjectMapper -- @InjectMocks would leave that parameter null since
    // it isn't a @Mock field, and TransferService needs a working one to
    // serialize outbox event payloads.
    private TransferService transferService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        transferService = new TransferService(accountRepository, transferRepository, journalEntryRepository, outboxEventRepository, objectMapper, new SimpleMeterRegistry());
    }

    // --- executeTransfer: creation / funds hold ---

    @Test
    void rejectsSelfTransferBeforeTouchingAnyRepository() {
        UUID accountId = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(accountId, accountId, 100L);

        assertThatThrownBy(() -> transferService.executeTransfer(request))
                .isInstanceOf(SelfTransferException.class);

        verifyNoInteractions(accountRepository, transferRepository, journalEntryRepository, outboxEventRepository);
    }

    @Test
    void throwsWhenEitherAccountIsUnknown() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        CreateTransferRequest request = new CreateTransferRequest(from, to, 100L);

        lenient().when(accountRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.executeTransfer(request))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void recordsInsufficientFundsAsARejectedTransferWithNoHoldAndNoEvent() {
        Account from = customerAccount(500L);
        Account to = customerAccount(0L);
        CreateTransferRequest request = new CreateTransferRequest(from.getId(), to.getId(), 1_000L);

        stubLookup(from, to);
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Transfer result = transferService.executeTransfer(request);

        assertThat(result.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(result.getRejectionReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(from.getBalanceCents()).isEqualTo(500L);
        verifyNoInteractions(journalEntryRepository, outboxEventRepository);
    }

    @Test
    void acceptedTransferHoldsFundsAndStaysPendingUntilAFraudDecisionArrives() {
        Account from = customerAccount(1_000L);
        Account to = customerAccount(200L);
        CreateTransferRequest request = new CreateTransferRequest(from.getId(), to.getId(), 300L);

        stubLookup(from, to);
        when(transferRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Transfer result = transferService.executeTransfer(request);

        // Funds held immediately...
        assertThat(from.getBalanceCents()).isEqualTo(700L);
        // ...but the recipient hasn't been paid and the transfer isn't done.
        assertThat(to.getBalanceCents()).isEqualTo(200L);
        assertThat(result.getStatus()).isEqualTo(TransferStatus.PENDING);

        verify(journalEntryRepository, times(1)).save(any(JournalEntry.class));

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(KafkaTopics.PAYMENT_CREATED);
    }

    // --- applyFraudDecision ---

    @Test
    void allowDecisionCreditsRecipientAndCompletesTheTransfer() {
        Transfer transfer = pendingTransfer(300L);
        Account to = customerAccount(200L);
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));
        when(accountRepository.findByIdForUpdate(transfer.getToAccountId())).thenReturn(Optional.of(to));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        transferService.applyFraudDecision(allowEvent(transfer.getId()));

        assertThat(to.getBalanceCents()).isEqualTo(500L);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void blockDecisionReversesTheHoldBackToTheSender() {
        Transfer transfer = pendingTransfer(300L);
        Account from = customerAccount(700L); // already debited by 300 at creation time, in this scenario
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));
        when(accountRepository.findByIdForUpdate(transfer.getFromAccountId())).thenReturn(Optional.of(from));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        transferService.applyFraudDecision(blockEvent(transfer.getId()));

        assertThat(from.getBalanceCents()).isEqualTo(1_000L); // credited back
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getRejectionReason()).isEqualTo("FRAUD_BLOCKED");

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(Direction.CREDIT);
    }

    @Test
    void reviewDecisionMovesTheTransferToUnderReviewWithoutTouchingAnyBalance() {
        Transfer transfer = pendingTransfer(300L);
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        transferService.applyFraudDecision(reviewEvent(transfer.getId()));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.UNDER_REVIEW);
        verifyNoInteractions(accountRepository, journalEntryRepository);
    }

    @Test
    void aDecisionForAnAlreadyResolvedTransferIsIgnored() {
        Transfer transfer = pendingTransfer(300L);
        transfer.complete(Instant.now());
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        transferService.applyFraudDecision(blockEvent(transfer.getId()));

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verifyNoInteractions(accountRepository, journalEntryRepository, outboxEventRepository);
    }

    private void stubLookup(Account a, Account b) {
        lenient().when(accountRepository.findByIdForUpdate(a.getId())).thenReturn(Optional.of(a));
        lenient().when(accountRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
    }

    private Account customerAccount(long balanceCents) {
        return new Account(UUID.randomUUID(), "Test Account", "USD", balanceCents, AccountType.CUSTOMER, Instant.now());
    }

    private Transfer pendingTransfer(long amountCents) {
        return new Transfer(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amountCents, Instant.now());
    }

    private FraudDecisionEvent allowEvent(UUID transferId) {
        return new FraudDecisionEvent(UUID.randomUUID(), transferId, "ALLOW", 10, List.of(), Instant.now());
    }

    private FraudDecisionEvent blockEvent(UUID transferId) {
        return new FraudDecisionEvent(UUID.randomUUID(), transferId, "BLOCK", 90, List.of(), Instant.now());
    }

    private FraudDecisionEvent reviewEvent(UUID transferId) {
        return new FraudDecisionEvent(UUID.randomUUID(), transferId, "REVIEW", 50, List.of(), Instant.now());
    }
}
