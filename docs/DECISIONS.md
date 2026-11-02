# Design Decisions

Every non-trivial engineering decision in this project, logged as: the problem, the options considered, what was chosen, and why.

## 1. Gradle multi-module instead of two separate repos

**Problem:** payment-service and fraud-service are independently deployable, but share conventions (Java version, test setup, Spring Boot version).

**Options:** (a) two separate Gradle/Maven projects, (b) a Gradle multi-module build with shared root config, (c) a monorepo with no shared build config.

**Chosen:** (b). `subprojects {}` in the root `build.gradle` keeps Java version, dependency-management, and test config in one place, while each service still builds and deploys as its own independent jar. This mirrors how real microservice monorepos are commonly structured.

## 2. Java 21 via Gradle toolchains, not a manually installed JDK

**Problem:** the spec calls for Java 21, but the local Eclipse setup was proven working with Java 17 (LinkPulse).

**Options:** (a) drop to Java 17 to match the known-working setup, (b) require the developer to manually install JDK 21, (c) use Gradle's toolchain auto-provisioning.

**Chosen:** (c). `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }` plus the `foojay-resolver-convention` plugin lets Gradle download and manage JDK 21 itself on first build, so the stated Java 21 requirement is met without extra manual setup or touching the JDK Eclipse already uses for other projects.

## 3. Database-per-service (payment-db and fraud-db are separate Postgres instances)

**Problem:** should payment-service and fraud-service share one database, or each own their data?

**Options:** (a) one shared Postgres instance/schema, (b) separate schemas in one instance, (c) fully separate database instances.

**Chosen:** (c). Each service should only ever be read from or written to through its own API -- sharing a database (or even just a schema) would let one service reach into the other's tables directly, which defeats the point of splitting them and creates hidden coupling. This is also the natural setup for the outbox/event-driven design in Phase 3: fraud-service only ever learns about payments through Kafka events, never a direct DB read.

## 4. Kafka KRaft mode, dual advertised listeners (reused from LinkPulse)

**Problem:** containers need to reach Kafka by its Docker service name (`kafka:9092`), but Eclipse-run services on the host machine need to reach it via `localhost`.

**Options:** (a) run everything in Docker so only the container-network address is needed, (b) a single listener with a workaround, (c) two advertised listeners -- one for container traffic, one for host traffic.

**Chosen:** (c), the same pattern proven working in LinkPulse. Local dev keeps the app services running in Eclipse (for breakpoints/debugging) while infrastructure runs in Docker, so both container-side and host-side clients need to resolve the broker correctly.

## 5. Entry-level scope: what's deferred from the original spec

**Problem:** the original spec (Testcontainers, JaCoCo coverage in CI, Grafana provisioned as code, full AWS/Terraform deployment, a frontend) is closer to a staff-level system design exercise than a new-grad portfolio piece.

**Chosen:** keep the parts that demonstrate real backend judgment -- double-entry ledger, idempotency, concurrency correctness, event-driven design, a properly-scored fraud engine -- and defer the infrastructure-heavy parts (Testcontainers, coverage tooling, IaC, cloud deployment) to an optional stretch phase at the end. Depth on fewer things is a stronger interview story than shallow coverage of everything.

## 6. Idempotency: claim-then-poll, not a simple "check then insert"

**Problem:** the same Idempotency-Key can arrive dozens of times at once (a client retrying on a timeout, a flaky network). A naive "SELECT to check if the key exists, then INSERT if not" has a race: two concurrent requests can both pass the SELECT before either INSERT commits, and both execute the transfer.

**Options:** (a) check-then-insert (race-prone, rejected), (b) a database advisory lock per key, (c) rely on a UNIQUE constraint as the race-resolution mechanism: try to INSERT a "claim" row first; the constraint itself decides exactly one winner.

**Chosen:** (c), plus a specific structure to make it usable:
- The claim is written in its **own transaction** (`REQUIRES_NEW`), so it's visible to other concurrent requests immediately -- not only after the winner's entire business transaction (locks, journal entries, etc.) finishes committing.
- A losing request doesn't block on the winner's transaction; it fails the INSERT immediately (unique violation) and **polls** the record for a completed response, with a bounded number of attempts.
- This needed two separate Spring beans (`IdempotencyService` and `IdempotencyStore`) rather than one, because `@Transactional` is implemented as a proxy, and a method calling another `@Transactional` method *on the same bean* bypasses the proxy entirely (the "self-invocation" gotcha) -- `REQUIRES_NEW` would silently do nothing if both lived in one class.
- A thrown exception during the winning request's action means nothing was persisted (bad input, unknown account) -- the claim is freed rather than cached, so a corrected retry with the same key still works.

## 7. Pessimistic locking (SELECT ... FOR UPDATE), not optimistic

**Problem:** two transfers touching the same account concurrently must not both read the same starting balance and each apply their change on top of stale data.

**Options:** (a) optimistic locking (a `version` column, retry on conflict), (b) pessimistic row locking (`SELECT ... FOR UPDATE`), (c) no locking, rely on the database's default isolation level.

**Chosen:** (b). For a ledger, correctness matters more than throughput at this scale, and pessimistic locking is easier to reason about and prove correct: the account row is simply unavailable to a second transaction until the first finishes, no retry logic needed. Optimistic locking would avoid blocking but adds retry-storm risk on "hot" accounts (an account everyone happens to be paying at once) and more code to get right. Locking both accounts also requires a **fixed lock order** (lower UUID first, regardless of which account is the sender): without it, a transfer A->B running concurrently with B->A could each hold one lock and wait forever on the other -- a classic deadlock. This is implemented in `TransferService.executeTransfer`.

## 8. Insufficient funds is a business outcome, not an HTTP error

**Problem:** should a transfer that can't be completed because the sender doesn't have the funds return an HTTP 4xx, or something else?

**Chosen:** it's recorded as a real `Transfer` row with `status = REJECTED` and returned as a normal `201 Created` response describing that outcome -- the same pattern real payment APIs (e.g. a declined card charge) use. It's a legitimate, persisted result that needs the same idempotency protection as a successful transfer (retrying shouldn't create a second rejected record). Only genuinely invalid requests -- an unknown account, a transfer to yourself -- are real HTTP errors (404 / 400), because those never got far enough to persist anything.

## 9. Seeding a new account's balance goes through a real transfer, not a direct balance write

**Problem:** `POST /accounts` can optionally open an account with a non-zero starting balance. Where does that money "come from" in the ledger?

**Chosen:** from the `EXTERNAL_FUNDING` system account seeded in the Phase 2 migration. Opening a funded account is implemented as an ordinary transfer (with matching debit/credit journal entries) from `EXTERNAL_FUNDING` to the new account, not a direct `UPDATE accounts SET balance_cents = ...`. This keeps "total money in the system is conserved" true and mechanically checkable even for money that's "created" at account-opening time -- there's always a traceable counterparty.

## 10. Testcontainers replaced with a dedicated test database + a Gradle task split

**Problem:** the original spec called for Testcontainers-backed integration tests; that scope was cut for the entry-level version of this project (see decision 5).

**Chosen:** `payment-db`'s Docker init script also creates a second database, `payments_test_db`, so integration tests run against a real (not embedded/H2) Postgres without needing Testcontainers. `./gradlew test` excludes anything named `*IT` and has no external dependencies; `./gradlew integrationTest` runs only `*IT` classes and requires `docker compose up -d` first. This is the same test/integration-test split Maven's Failsafe plugin enforces by convention, just expressed as two Gradle `Test` tasks with a name filter.

## 11. Funds hold at creation time, not at approval

**Problem:** Phase 2 completed transfers synchronously, so locking both accounts for the whole operation was enough to prevent overdraft. Phase 3 makes approval asynchronous (it waits on a fraud.decision event that might take a while, or require a human) -- so what stops two PENDING transfers from both passing a "sufficient funds" check against the same stale balance before either is approved?

**Options:** (a) don't touch balances until approval, and re-check funds at approval time (re-introduces the exact race this project exists to prevent), (b) a separate "available balance" vs "ledger balance" with explicit holds (the fully realistic answer, but meaningfully more schema and code for this project's scope), (c) debit the sender and write the DEBIT journal entry immediately at creation time, under the same lock used in Phase 2, and only defer the recipient's CREDIT until approval.

**Chosen:** (c). The money is removed from the sender's spendable balance the instant the transfer is accepted -- there's no window where it could be double-spent, because it's already gone. If fraud later blocks the transfer, `rejectAndReverse()` credits the sender back via a **compensating transaction** (a new CREDIT journal entry, never an edit to the original DEBIT) -- the standard saga pattern for undoing a step that already committed in a multi-step async workflow. This is also why REVIEW is safe to leave for a human at their own pace: the funds are already held, so nothing more can go wrong while it waits.

## 12. Idempotent Kafka consumption: a dedup table per consumer, checked and written in the same transaction as the business effect

**Problem:** Kafka's at-least-once delivery means both payment-service (consuming fraud.decision) and fraud-service (consuming payment.created) can see the same event more than once -- a consumer restart before committing its offset is the common case.

**Chosen:** `processed_fraud_decisions` and `processed_payment_events` tables, one per consuming service, keyed by the event's own `eventId` (a UUID generated by the publisher, carried in the event payload). The existence check and the business effect happen in the same `@Transactional` method: a clean duplicate short-circuits immediately, and in the narrow case where two deliveries are genuinely concurrent, the dedup table's primary key constraint makes the second one fail and roll back everything it did -- so the business effect is applied at most once either way, not just "probably once."

## 13. The rule engine is pure functions over a precomputed context, not rules that query the database themselves

**Problem:** where should a rule like "is this amount anomalous" get the data it needs (the account's transaction history, whether this recipient is new)?

**Chosen:** `PaymentCreatedHandler` looks everything up first (via `VelocityTracker` and `AccountStatsService`) and hands each `FraudRule` a plain `FraudContext` record. A rule's `evaluate(event, context)` never touches Redis or Postgres directly. This keeps every rule a pure function, meaning `VelocityRuleTest`, `AmountAnomalyRuleTest`, and `NewRecipientRuleTest` need zero mocking -- they just construct a `FraudContext` and assert on the result. Adding a new rule later is adding one more `@Component` implementing `FraudRule`; `FraudScoringService` picks it up automatically via Spring's `List<FraudRule>` injection.

## 14. Velocity checking reuses LinkPulse's sliding-window-log Lua script

**Problem:** counting "how many transfers has this account sent in the last 60 seconds" needs the same sliding-window semantics as a rate limiter -- old entries must expire out of the count, and the check-and-record step must be atomic under concurrent events for the same account.

**Chosen:** the exact sliding-window-log technique built for LinkPulse's Redis rate limiter (a sorted set scored by timestamp, trimmed and counted in one Lua script), applied here to fraud velocity instead of request throttling. The one change: the sorted set's **member** is the event's own id, not the timestamp -- using the timestamp as both score and member would silently collide if two events landed in the same millisecond, undercounting them. Using the eventId also makes re-running the script for the same event a no-op (ZADD with an unchanged member/score), so velocity tracking is naturally idempotent under Kafka redelivery.

## 15. fraud-service publishes directly, without an outbox

**Problem:** payment-service's transfer creation uses the transactional outbox pattern (decision #4 was really about not losing an event) -- should fraud-service's decision publishing use the same pattern?

**Chosen:** no. fraud-service persists its decision and calls `KafkaTemplate.send(...).get(...)` inside the same `@Transactional` method; if the send fails, the exception rolls back the whole transaction (decision, reasons, and the processed-event marker), so the next redelivery of that `payment.created` event retries cleanly from scratch. The failure mode this doesn't cover -- the send succeeds but a later step in the same transaction then fails -- is a real, non-zero-probability gap. It's accepted here because the blast radius is small and recoverable: a transfer just sits in PENDING a little longer, rather than a lost or double-applied ledger entry, which is what the outbox pattern exists to prevent on the payment-service side. Duplicating that pattern here would be the same mechanism solving a lower-stakes problem -- a good instinct to resist, not just a shortcut.

## 16. A REVIEW decision is resolved in place, not superseded by a new row

**Problem:** when a human resolves a REVIEW decision, should that create a second `fraud_decisions` row for the final verdict, or update the existing one?

**Chosen:** update in place (`reviewed_at`, `reviewed_outcome`, `reviewer_note` on the original row). `transfer_id` stays UNIQUE, so there's always exactly one current fraud assessment per transfer, and `GET /fraud/decisions/{transferId}` never has to decide which of several rows is "the" answer. The original `decision` value (`REVIEW`) is preserved as the historical record of what the automated system concluded, separate from `reviewed_outcome` (what the human concluded) -- both are visible, nothing is overwritten.

## 17. Correlation IDs travel inside event payloads, not via thread-local propagation alone

**Problem:** a single transfer now spans an HTTP request, a scheduled outbox poll, a Kafka publish, a Kafka consume on a different service, and possibly a second HTTP request (manual review) days later. MDC alone only survives within one thread -- it says nothing once that thread's work is done and a `@Scheduled` task or `@KafkaListener` picks the rest up later, possibly on another machine.

**Chosen:** `CorrelationIdFilter` (one per service) reads an inbound `X-Correlation-Id` header or generates one, puts it in MDC for that request's own logs, and echoes it back on the response. `TransferService` reads it out of MDC at the point a `PaymentCreatedEvent` is built and embeds it as a field on the event itself -- so it rides along in the outbox payload across the async handoff to the scheduled relay, through Kafka, and into fraud-service's consumer, which restores it onto its own MDC before processing. The same happens in reverse for `fraud.decision` events. A manually-resolved `REVIEW` decision deliberately gets a *new* correlation ID (that REST call's own), not the original transfer's -- it's a genuinely separate, later operation, and that's the more useful trace to see in payment-service's logs when the transfer finally settles. The trade-off: this only traces one transfer's lifecycle end-to-end, not a full distributed-tracing picture (span hierarchies, timing breakdowns across services) -- that's what OpenTelemetry/Zipkin exist for, and is out of scope for this project's size.

## 18. Prometheus scrapes host-run services via `host.docker.internal`, not container DNS

**Problem:** payment-service and fraud-service run on the host (via Eclipse, per decision -- see README), but Prometheus and Grafana run in `docker-compose` alongside the databases. Prometheus can't reach `payment-service:8090` the way it reaches `payment-db:5432`, because there is no `payment-service` container.

**Chosen:** `monitoring/prometheus/prometheus.yml` scrapes `host.docker.internal:8090` and `host.docker.internal:8091` -- Docker Desktop's built-in DNS name for "the machine running Docker," which resolves automatically on Mac and Windows. (A Linux host would need an explicit `extra_hosts: host.docker.internal:host-gateway` entry, left as a comment in `docker-compose.yml` for that case.) This is a direct consequence of decision -- the Eclipse-first workflow -- not a new trade-off of its own.

## 19. A small number of hand-picked business metrics, not "metrics everywhere"

**Problem:** Micrometer/Prometheus can measure almost anything -- where's the line for what's worth instrumenting on a project this size?

**Chosen:** four custom metrics, each answering a specific question a reviewer or on-call engineer would actually ask: `payments.transfers.created` (tagged `status`/`reason` -- how many transfers are being accepted vs. rejected for insufficient funds, right now), `payments.transfers.resolved` (tagged `outcome`/`reason` -- of the ones that got a fraud opinion, how many completed vs. got blocked), `fraud.decisions` (tagged `decision` -- the ALLOW/REVIEW/BLOCK split), and `fraud.scoring.duration` (a Timer -- is scoring getting slower as rules or data volume grow). Everything else (JVM heap, GC pauses, HTTP request latency per endpoint, DB connection pool usage) comes for free from `spring-boot-starter-actuator` + `micrometer-registry-prometheus` without writing a line of code, and is left for Prometheus/Grafana to surface rather than hand-rolled.

## 20. CI runs unit tests and payment-service's integration tests, but doesn't spin up Kafka

**Problem:** `LedgerConcurrencyIT` needs a real Postgres (already solved by decision #10's dedicated test-db approach) and boots the full Spring context, which includes Kafka consumer/producer beans. Should CI also run a Kafka broker so those beans connect cleanly?

**Chosen:** no. `LedgerConcurrencyIT`'s assertions only ever read account/transfer rows directly from Postgres -- they never depend on a `fraud.decision` actually arriving (by design, per decision #11: a transfer is provably safe sitting in PENDING). Spring Kafka's producer/consumer factories are lazy about connecting, so the Spring context boots fine without a broker present; the outbox relay's scheduled publish attempts simply log a warning and retry, which doesn't fail the test. Adding a Kafka service container to GitHub Actions (plus the matching KRaft environment variables `docker-compose.yml` needs) would be real effort spent making CI harder to read for a benefit this specific test doesn't need. fraud-service's event-driven logic is already covered without a broker, by unit-testing `PaymentCreatedHandler` and `FraudReviewService` directly against mocks (see decision #13's same reasoning, applied to Kafka handlers instead of fraud rules).
