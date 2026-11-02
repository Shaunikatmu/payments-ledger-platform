package com.paymentsledger.paymentservice.repository;

import com.paymentsledger.paymentservice.domain.ProcessedFraudDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedFraudDecisionRepository extends JpaRepository<ProcessedFraudDecision, UUID> {
}
