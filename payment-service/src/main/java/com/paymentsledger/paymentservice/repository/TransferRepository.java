package com.paymentsledger.paymentservice.repository;

import com.paymentsledger.paymentservice.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
}
