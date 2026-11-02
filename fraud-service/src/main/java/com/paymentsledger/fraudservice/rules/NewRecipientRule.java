package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flags a large transfer to a recipient this account has never paid before
 * -- combining "new recipient" with "large amount" so routine small
 * first-time transfers (which are extremely common and not inherently
 * risky) don't constantly trigger this.
 */
@Component
public class NewRecipientRule implements FraudRule {

    private final long largeAmountThresholdCents;
    private final int score;

    public NewRecipientRule(@Value("${fraud.rules.new-recipient.large-amount-cents:50000}") long largeAmountThresholdCents,
                             @Value("${fraud.rules.new-recipient.score:30}") int score) {
        this.largeAmountThresholdCents = largeAmountThresholdCents;
        this.score = score;
    }

    @Override
    public RuleResult evaluate(PaymentCreatedEvent event, FraudContext context) {
        if (!context.isNewRecipient() || event.amountCents() < largeAmountThresholdCents) {
            return RuleResult.noFinding("NEW_RECIPIENT");
        }

        String explanation = String.format("First-ever transfer to this recipient, for $%.2f", event.amountCents() / 100.0);
        return new RuleResult("NEW_RECIPIENT", score, explanation);
    }
}
