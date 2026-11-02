package com.paymentsledger.fraudservice.repository;

import com.paymentsledger.fraudservice.domain.AccountAmountStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountAmountStatsRepository extends JpaRepository<AccountAmountStats, UUID> {
}
