package com.paymentsledger.fraudservice.repository;

import com.paymentsledger.fraudservice.domain.Decision;
import com.paymentsledger.fraudservice.domain.FraudDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FraudDecisionRepository extends JpaRepository<FraudDecision, UUID> {

    Optional<FraudDecision> findByTransferId(UUID transferId);

    List<FraudDecision> findByDecisionAndReviewedAtIsNullOrderByCreatedAtAsc(Decision decision);
}
