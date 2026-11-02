package com.paymentsledger.paymentservice.service;

import com.paymentsledger.paymentservice.api.dto.CreateAccountRequest;
import com.paymentsledger.paymentservice.domain.Account;
import com.paymentsledger.paymentservice.domain.AccountType;
import com.paymentsledger.paymentservice.domain.Direction;
import com.paymentsledger.paymentservice.domain.JournalEntry;
import com.paymentsledger.paymentservice.domain.Transfer;
import com.paymentsledger.paymentservice.exception.AccountNotFoundException;
import com.paymentsledger.paymentservice.repository.AccountRepository;
import com.paymentsledger.paymentservice.repository.JournalEntryRepository;
import com.paymentsledger.paymentservice.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final JournalEntryRepository journalEntryRepository;

    public AccountService(AccountRepository accountRepository, TransferRepository transferRepository,
                           JournalEntryRepository journalEntryRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
        this.journalEntryRepository = journalEntryRepository;
    }

    /**
     * Creates an account. If an initial balance is requested, it's funded
     * via a real transfer from the EXTERNAL_FUNDING system account and
     * recorded as ordinary journal entries -- there's no backdoor that
     * credits a balance without a traceable counterparty, so the
     * "total money in the system is conserved" invariant holds even for a
     * brand-new account. See docs/DECISIONS.md.
     */
    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        Instant now = Instant.now();
        Account account = new Account(UUID.randomUUID(), request.ownerName(), request.currency(), 0L, AccountType.CUSTOMER, now);
        accountRepository.save(account);

        long initial = request.initialBalanceCents();
        if (initial > 0) {
            fundFromExternalSource(account, initial, now);
        }

        return account;
    }

    private void fundFromExternalSource(Account account, long amountCents, Instant now) {
        Account externalFunding = accountRepository.findByIdForUpdate(Account.EXTERNAL_FUNDING_ID)
                .orElseThrow(() -> new AccountNotFoundException(Account.EXTERNAL_FUNDING_ID));

        externalFunding.debit(amountCents);
        account.credit(amountCents);
        accountRepository.save(externalFunding);
        accountRepository.save(account);

        Transfer transfer = new Transfer(UUID.randomUUID(), externalFunding.getId(), account.getId(), amountCents, now);
        transfer.complete(now);
        transferRepository.save(transfer);

        journalEntryRepository.save(new JournalEntry(UUID.randomUUID(), transfer.getId(), externalFunding.getId(), Direction.DEBIT, amountCents, now));
        journalEntryRepository.save(new JournalEntry(UUID.randomUUID(), transfer.getId(), account.getId(), Direction.CREDIT, amountCents, now));
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
