package com.paymentsledger.paymentservice.repository;

import com.paymentsledger.paymentservice.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Locks the row with SELECT ... FOR UPDATE. Callers must always acquire
     * locks for a pair of accounts in a fixed order (e.g. by UUID) to avoid
     * deadlocking against a concurrent transfer running in the opposite
     * direction -- see TransferService and docs/DECISIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
