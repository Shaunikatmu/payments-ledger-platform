package com.paymentsledger.paymentservice.service;

import com.paymentsledger.paymentservice.api.dto.CreateAccountRequest;
import com.paymentsledger.paymentservice.domain.Account;
import com.paymentsledger.paymentservice.domain.AccountType;
import com.paymentsledger.paymentservice.domain.JournalEntry;
import com.paymentsledger.paymentservice.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private com.paymentsledger.paymentservice.repository.AccountRepository accountRepository;
    @Mock
    private com.paymentsledger.paymentservice.repository.TransferRepository transferRepository;
    @Mock
    private com.paymentsledger.paymentservice.repository.JournalEntryRepository journalEntryRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void createsAccountWithZeroBalanceAndNoFundingTransferByDefault() {
        CreateAccountRequest request = new CreateAccountRequest("Ada Lovelace", "USD", 0L);

        Account result = accountService.createAccount(request);

        assertThat(result.getBalanceCents()).isZero();
        assertThat(result.getAccountType()).isEqualTo(AccountType.CUSTOMER);
        verify(accountRepository, times(1)).save(any(Account.class));
        verifyNoInteractions(transferRepository, journalEntryRepository);
    }

    @Test
    void fundsInitialBalanceFromExternalFundingAccountWithBalancedJournalEntries() {
        CreateAccountRequest request = new CreateAccountRequest("Ada Lovelace", "USD", 5_000L);
        Account externalFunding = new Account(Account.EXTERNAL_FUNDING_ID, "EXTERNAL_FUNDING", "USD", 1_000_000L, AccountType.SYSTEM, Instant.now());

        when(accountRepository.findByIdForUpdate(Account.EXTERNAL_FUNDING_ID)).thenReturn(Optional.of(externalFunding));
        when(transferRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Account result = accountService.createAccount(request);

        assertThat(result.getBalanceCents()).isEqualTo(5_000L);
        assertThat(externalFunding.getBalanceCents()).isEqualTo(995_000L);
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(journalEntryRepository, times(2)).save(any(JournalEntry.class));
    }
}
