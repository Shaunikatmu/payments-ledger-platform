package com.paymentsledger.fraudservice.rules;

public record RuleResult(String ruleName, int score, String explanation) {

    public static RuleResult noFinding(String ruleName) {
        return new RuleResult(ruleName, 0, null);
    }
}
