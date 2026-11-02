package com.paymentsledger.paymentservice.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Every request either brings its own X-Correlation-Id (a caller chaining
 * calls together, or a human re-submitting the same ID while debugging) or
 * gets a fresh one here. Either way it's echoed back on the response and
 * stashed in MDC so every log line this request's thread produces --
 * including, crucially, the one line that writes the outbox_events row --
 * carries it. From there TransferService reads it out of MDC and embeds it
 * in the PaymentCreatedEvent payload, which is how it survives the jump to
 * fraud-service on a completely different thread, possibly on a different
 * machine. See docs/DECISIONS.md.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
