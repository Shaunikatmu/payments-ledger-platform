package com.paymentsledger.fraudservice.rules;

import com.paymentsledger.fraudservice.kafka.dto.PaymentCreatedEvent;

/** Strategy interface -- see docs/DECISIONS.md for why rules are pluggable Spring beans rather than a hardcoded chain. */
public interface FraudRule {

    RuleResult evaluate(PaymentCreatedEvent event, FraudContext context);
}
