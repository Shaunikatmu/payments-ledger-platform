package com.paymentsledger.paymentservice.api;

import com.paymentsledger.paymentservice.api.dto.CreateTransferRequest;
import com.paymentsledger.paymentservice.api.dto.TransferResponse;
import com.paymentsledger.paymentservice.domain.Transfer;
import com.paymentsledger.paymentservice.repository.TransferRepository;
import com.paymentsledger.paymentservice.service.IdempotencyService;
import com.paymentsledger.paymentservice.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferRepository transferRepository;
    private final IdempotencyService idempotencyService;

    public TransferController(TransferService transferService, TransferRepository transferRepository,
                               IdempotencyService idempotencyService) {
        this.transferService = transferService;
        this.transferRepository = transferRepository;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {

        return idempotencyService.execute(idempotencyKey, "/transfers", request, TransferResponse.class,
                () -> {
                    Transfer transfer = transferService.executeTransfer(request);
                    return ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(transfer));
                });
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable("transferId") UUID transferId) {
        return transferRepository.findById(transferId)
                .map(TransferResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
