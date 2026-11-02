package com.paymentsledger.paymentservice.repository;

import com.paymentsledger.paymentservice.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyRecord, String> {
}
