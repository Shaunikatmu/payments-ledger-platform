package com.paymentsledger.fraudservice.rules;

/**
 * Everything a FraudRule needs about the sender's history, already looked
 * up/recorded for this event by AccountStatsService and VelocityTracker.
 * Rules themselves never touch Redis or the database -- keeping them pure
 * functions of (event, context) is what makes RuleTest classes trivial to
 * write with no mocking.
 */
public record FraudContext(
        int recentTransferCount,
        long priorTransactionCount,
        long priorAverageAmountCents,
        boolean isNewRecipient
) {
}
