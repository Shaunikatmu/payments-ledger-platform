package com.paymentsledger.fraudservice.api;

import com.paymentsledger.fraudservice.api.dto.FraudDecisionResponse;
import com.paymentsledger.fraudservice.api.dto.ReviewRequest;
import com.paymentsledger.fraudservice.domain.Decision;
import com.paymentsledger.fraudservice.domain.FraudDecision;
import com.paymentsledger.fraudservice.exception.FraudDecisionNotFoundException;
import com.paymentsledger.fraudservice.repository.FraudDecisionReasonRepository;
import com.paymentsledger.fraudservice.repository.FraudDecisionRepository;
import com.paymentsledger.fraudservice.service.FraudReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/fraud")
public class FraudReviewController {

    private final FraudDecisionRepository decisionRepository;
    private final FraudDecisionReasonRepository reasonRepository;
    private final FraudReviewService reviewService;

    public FraudReviewController(FraudDecisionRepository decisionRepository, FraudDecisionReasonRepository reasonRepository,
                                  FraudReviewService reviewService) {
        this.decisionRepository = decisionRepository;
        this.reasonRepository = reasonRepository;
        this.reviewService = reviewService;
    }

    /** Transactions currently flagged REVIEW and awaiting a human decision. */
    @GetMapping("/flagged")
    public List<FraudDecisionResponse> listFlagged() {
        return decisionRepository.findByDecisionAndReviewedAtIsNullOrderByCreatedAtAsc(Decision.REVIEW).stream()
                .map(this::toResponse)
                .toList();
    }

    /** The current fraud assessment for a transfer, with full explainability (which rules fired and why). */
    @GetMapping("/decisions/{transferId}")
    public FraudDecisionResponse getDecision(@PathVariable("transferId") UUID transferId) {
        FraudDecision decision = decisionRepository.findByTransferId(transferId)
                .orElseThrow(() -> new FraudDecisionNotFoundException(transferId));
        return toResponse(decision);
    }

    /** Resolves a REVIEW decision, publishing the final ALLOW/BLOCK that lets payment-service settle the held transfer. */
    @PostMapping("/decisions/{id}/review")
    public FraudDecisionResponse review(@PathVariable("id") UUID id, @Valid @RequestBody ReviewRequest request) {
        FraudDecision decision = reviewService.review(id, request.outcome(), request.note());
        return toResponse(decision);
    }

    private FraudDecisionResponse toResponse(FraudDecision decision) {
        return FraudDecisionResponse.from(decision, reasonRepository.findByFraudDecisionId(decision.getId()));
    }
}
