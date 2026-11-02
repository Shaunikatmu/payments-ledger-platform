package com.paymentsledger.fraudservice.rules;

import java.util.List;

public record ScoringResult(String decision, int riskScore, List<RuleResult> reasons) {
}
