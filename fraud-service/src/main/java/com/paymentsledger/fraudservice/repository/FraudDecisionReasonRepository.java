package com.paymentsledger.fraudservice.repository;

import com.paymentsledger.fraudservice.domain.FraudDecisionReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FraudDecisionReasonRepository extends JpaRepository<FraudDecisionReason, UUID> {

    List<FraudDecisionReason> findByFraudDecisionId(UUID fraudDecisionId);
}
