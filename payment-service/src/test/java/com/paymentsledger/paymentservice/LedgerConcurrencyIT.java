package com.paymentsledger.paymentservice;

import com.paymentsledger.paymentservice.api.dto.AccountResponse;
import com.paymentsledger.paymentservice.api.dto.CreateAccountRequest;
import com.paymentsledger.paymentservice.api.dto.CreateTransferRequest;
import com.paymentsledger.paymentservice.api.dto.TransferResponse;
import com.paymentsledger.paymentservice.domain.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two correctness invariants the spec calls out, end-to-end over
 * real HTTP against a live Postgres (payments_test_db). Trimmed from the
 * original spec's full 1,000-request load scenario for entry-level scope
 * (see docs/DECISIONS.md), but a real concurrent proof rather than a
 * single-threaded one.
 *
 * Since Phase 3, a transfer's funds are held (debited) at creation but
 * settlement is asynchronous (see docs/DECISIONS.md #11) -- so these tests
 * check PENDING-state invariants, not completed balances. Run this with
 * fraud-service NOT running against the same Kafka/topics, so nothing
 * completes these transfers out from under the assertions mid-test.
 *
 * Requires `docker compose up -d` to be running. Run with:
 *   ./gradlew integrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LedgerConcurrencyIT {

    private static final int CONCURRENCY = 50;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void resetDatabaseAndOpenTwoFundedAccounts() {
        jdbcTemplate.execute("TRUNCATE TABLE journal_entries, idempotency_keys, outbox_events, processed_fraud_decisions, transfers RESTART IDENTITY CASCADE");
        jdbcTemplate.execute("DELETE FROM accounts WHERE account_type = 'CUSTOMER'");
        jdbcTemplate.update("UPDATE accounts SET balance_cents = ? WHERE id = ?", Long.MAX_VALUE, Account.EXTERNAL_FUNDING_ID);

        accountA = openAccount("Account A", 100_000L);
        accountB = openAccount("Account B", 100_000L);
    }

    @Test
    void sameIdempotencyKeySentConcurrentlyHoldsFundsExactlyOnce() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        List<ResponseEntity<TransferResponse>> responses = runConcurrently(CONCURRENCY,
                () -> postTransfer(idempotencyKey, accountA, accountB, 1_000L));

        Set<UUID> transferIds = new HashSet<>();
        for (ResponseEntity<TransferResponse> response : responses) {
            assertThat(response.getStatusCode().value()).isEqualTo(201);
            assertThat(response.getBody().status()).isEqualTo("PENDING");
            transferIds.add(response.getBody().id());
        }
        // Every one of the 50 concurrent callers got back the same transfer.
        assertThat(transferIds).hasSize(1);

        Long transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE from_account_id = ? AND to_account_id = ?",
                Long.class, accountA, accountB);
        assertThat(transferCount).isEqualTo(1L);

        // Held exactly once, not 50 times. The recipient hasn't been paid
        // yet -- that only happens once a fraud.decision resolves it.
        assertThat(balanceOf(accountA)).isEqualTo(100_000L - 1_000L);
        assertThat(balanceOf(accountB)).isEqualTo(100_000L);
    }

    @Test
    void concurrentTransfersConserveMoneyAcrossBalancesAndHeldAmounts() throws Exception {
        long transferAmount = 5_000L;
        long startingTotal = balanceOf(accountA) + balanceOf(accountB);

        runConcurrently(CONCURRENCY, index -> {
            boolean aToB = index % 2 == 0;
            UUID from = aToB ? accountA : accountB;
            UUID to = aToB ? accountB : accountA;
            return postTransfer(UUID.randomUUID().toString(), from, to, transferAmount);
        });

        long balanceA = balanceOf(accountA);
        long balanceB = balanceOf(accountB);
        long heldInPendingTransfers = sumPendingTransferAmounts();

        assertThat(balanceA).isGreaterThanOrEqualTo(0);
        assertThat(balanceB).isGreaterThanOrEqualTo(0);
        // Nothing is created or destroyed: every cent is either still in an
        // account's spendable balance, or held against a PENDING transfer
        // waiting on a fraud decision.
        assertThat(balanceA + balanceB + heldInPendingTransfers).isEqualTo(startingTotal);
    }

    private interface IndexedCall<T> {
        T call(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(int count, java.util.function.Supplier<T> action) throws Exception {
        return runConcurrently(count, index -> action.get());
    }

    private <T> List<T> runConcurrently(int count, IndexedCall<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return action.call(index);
            }));
        }

        ready.await();
        go.countDown();

        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private UUID openAccount(String ownerName, long initialBalanceCents) {
        CreateAccountRequest request = new CreateAccountRequest(ownerName, "USD", initialBalanceCents);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<AccountResponse> response = restTemplate.exchange(
                "/accounts", HttpMethod.POST, new HttpEntity<>(request, headers), AccountResponse.class);
        return response.getBody().id();
    }

    private ResponseEntity<TransferResponse> postTransfer(String idempotencyKey, UUID from, UUID to, long amountCents) {
        CreateTransferRequest request = new CreateTransferRequest(from, to, amountCents);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return restTemplate.exchange("/transfers", HttpMethod.POST, new HttpEntity<>(request, headers), TransferResponse.class);
    }

    private long balanceOf(UUID accountId) {
        Long balance = jdbcTemplate.queryForObject("SELECT balance_cents FROM accounts WHERE id = ?", Long.class, accountId);
        return balance;
    }

    private long sumPendingTransferAmounts() {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM transfers WHERE status = 'PENDING' AND from_account_id IN (?, ?)",
                Long.class, accountA, accountB);
        return sum == null ? 0 : sum;
    }
}
