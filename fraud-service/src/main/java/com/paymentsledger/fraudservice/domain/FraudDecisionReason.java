package com.paymentsledger.fraudservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "fraud_decision_reasons")
public class FraudDecisionReason {

    @Id
    private UUID id;

    @Column(name = "fraud_decision_id", nullable = false)
    private UUID fraudDecisionId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_score", nullable = false)
    private int ruleScore;

    @Column(nullable = false)
    private String explanation;

    protected FraudDecisionReason() {
        // JPA
    }

    public FraudDecisionReason(UUID id, UUID fraudDecisionId, String ruleName, int ruleScore, String explanation) {
        this.id = id;
        this.fraudDecisionId = fraudDecisionId;
        this.ruleName = ruleName;
        this.ruleScore = ruleScore;
        this.explanation = explanation;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFraudDecisionId() {
        return fraudDecisionId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public int getRuleScore() {
        return ruleScore;
    }

    public String getExplanation() {
        return explanation;
    }
}
