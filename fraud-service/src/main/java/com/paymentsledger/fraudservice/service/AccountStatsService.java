package com.paymentsledger.fraudservice.service;

import com.paymentsledger.fraudservice.domain.AccountAmountStats;
import com.paymentsledger.fraudservice.domain.AccountRecipientHistory;
import com.paymentsledger.fraudservice.repository.AccountAmountStatsRepository;
import com.paymentsledger.fraudservice.repository.AccountRecipientHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * fraud-service's own local read model, built incrementally from
 * payment.created events -- never a direct query against payment-service's
 * database (see Phase 1 docs/DECISIONS.md #3).
 *
 * Both methods here return the state BEFORE this transaction is folded in
 * (what the rules need to score against), then record it for next time --
 * in that order, since scoring must never see its own event as part of the
 * account's prior history.
 */
@Service
public class AccountStatsService {

    private final AccountAmountStatsRepository statsRepository;
    private final AccountRecipientHistoryRepository recipientHistoryRepository;

    public AccountStatsService(AccountAmountStatsRepository statsRepository,
                                AccountRecipientHistoryRepository recipientHistoryRepository) {
        this.statsRepository = statsRepository;
        this.recipientHistoryRepository = recipientHistoryRepository;
    }

    public PriorStats recordAndGetPriorStats(UUID accountId, long amountCents) {
        AccountAmountStats stats = statsRepository.findById(accountId)
                .orElseGet(() -> new AccountAmountStats(accountId, 0, 0, Instant.now()));

        long priorCount = stats.getTransactionCount();
        long priorAverage = priorCount == 0 ? 0 : stats.getTotalAmountCents() / priorCount;

        stats.record(amountCents, Instant.now());
        statsRepository.save(stats);

        return new PriorStats(priorCount, priorAverage);
    }

    public boolean recordAndCheckIsNewRecipient(UUID fromAccountId, UUID toAccountId) {
        Optional<AccountRecipientHistory> existing = recipientHistoryRepository.findByFromAccountIdAndToAccountId(fromAccountId, toAccountId);
        boolean isNew = existing.isEmpty();

        AccountRecipientHistory history = existing.orElseGet(() -> new AccountRecipientHistory(UUID.randomUUID(), fromAccountId, toAccountId, Instant.now()));
        history.recordTransfer();
        recipientHistoryRepository.save(history);

        return isNew;
    }

    public record PriorStats(long transactionCount, long averageAmountCents) {
    }
}
