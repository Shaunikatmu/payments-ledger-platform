package com.paymentsledger.fraudservice.repository;

import com.paymentsledger.fraudservice.domain.AccountRecipientHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRecipientHistoryRepository extends JpaRepository<AccountRecipientHistory, UUID> {

    Optional<AccountRecipientHistory> findByFromAccountIdAndToAccountId(UUID fromAccountId, UUID toAccountId);
}
