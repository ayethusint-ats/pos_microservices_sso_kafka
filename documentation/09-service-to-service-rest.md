# Phase 9: Service-to-Service Communication over REST

> **Later change (Phase 10):** the recipient is now given as `targetAccountNumber`, and the credit/refund use payment-service's own token. See [10-client-credentials.md](10-client-credentials.md).

## 1. Concepts

Until now every request ended in **one** service. A transfer is different: payment-service must ask account-service to **debit** one account and **credit** another. This is the first call from one of our services to another.

| Concept | Meaning in this phase |
|---|---|
| **Synchronous REST call** | payment-service sends HTTP to account-service and **waits** for the answer before replying to the customer. |
| **Declarative HTTP client** | A Java interface annotated with `@HttpExchange`/`@GetExchange`/`@PostExchange`. Spring generates the implementation (built into Spring Framework 6; the modern alternative to OpenFeign). |
| **`RestClient`** | Spring's synchronous HTTP client (successor of `RestTemplate`), underneath the interface. |
| **Token relay** | payment-service forwards the **customer's** access token. account-service sees *Alice*, not payment-service, and applies its own ownership rules. |
| **Orchestration** | One service (payment) drives the steps of a business process that spans several calls. |
| **Partial failure** | Step 1 succeeded, step 2 failed. With two databases there is no rollback across both. |
| **Compensation** | Undo a completed step with a new action (refund the debit). This is the core of the Saga pattern (Phase 13). |
| **Outcome unknown** | A timeout or 5xx after the request was sent: it **may or may not** have been executed. The hardest case in distributed systems. |

### Why a separate payment-service?

| Without (transfer endpoint inside account-service) | With payment-service |
|---|---|
| A local DB transaction would make the transfer atomic. Simpler! | Needs orchestration and compensation. More complex. |
| account-service grows into "everything about money": limits, fees, fraud checks, FX, scheduled payments… | account-service stays a small, strict **ledger**: balances and their rules. |
| Payment history mixed with account data | Payment records are a separate concern with their own lifecycle and status |
| (Phases 11–13) Kafka events, sagas, idempotency all in one place | Each concern scales and changes independently |

Honest note: for a single bank's internal transfers, a transfer endpoint inside the ledger service is a perfectly valid design. We split it because the course needs a real cross-service flow. This is also a realistic one: payment hubs, card switches and core-banking ledgers are separate systems in real banks.

## 2. Why each design decision

| Decision | Why |
|---|---|
| **`@HttpExchange` interface** (`AccountApi`) instead of OpenFeign | Built into Spring Framework 6, no Spring Cloud OpenFeign dependency (now in maintenance mode). Same declarative style, works on `RestClient` (sync) or `WebClient` (reactive). |
| **A translating wrapper** (`AccountServiceClient`) around the interface | The rest of payment-service never sees HTTP status codes or `RestClientException`s. It sees three domain outcomes: result / **refused** / **unavailable (outcome known or unknown)**. |
| **Direct call to account-service, not through the gateway** | The gateway is the edge for *external* clients. Internal hops through it add latency, a second rate limit and a single point of failure. (In Kubernetes, a NetworkPolicy will make account-service reachable only from inside, Phase 16.) |
| **Explicit timeouts** (connect 1 s, read 2 s) | Without them, one hung account-service thread holds a payment-service thread forever. The defaults of the JDK client are *infinite*. |
| **Timeout budget**: the gateway route gets 10 s, more than 4 × 2 s | Otherwise the client receives 504 while payment-service is still working, and may retry a transfer that then completes twice. |
| **Record the payment *before* any remote call** (PENDING), and **checkpoint DEBITED** | If payment-service crashes mid-transfer, the database says exactly where it stopped. Money is never "in flight" without a trace. |
| **No `@Transactional` around the orchestration** | A DB transaction can't include another service's database. Holding one open across HTTP calls would only lock rows and hold a connection for seconds. |
| **Validate before moving money** (ownership, currency, target active) | Most failures then happen while nothing has moved yet: cheap to refuse, nothing to undo. |
| **Don't pre-check the balance** | account-service checks it atomically during the withdraw (optimistic locking). A pre-check would be stale immediately. |
| **No automatic retries** (yet) | Retrying a withdraw after a timeout may debit twice. Safe retries need **idempotency keys** (Phase 13). Resilience4j (Phase 12) will add retries only where they're safe. |
| **Token relay** (for now) | Simple, keeps account-service's ownership checks in force. Its limits are the motivation for Phase 10. |

## 3. Architecture

```
                         ┌──────────────────────────────────────────────────────────┐
 Angular / curl          │  api-gateway :8080                                        │
 Bearer <alice JWT> ───► │  JWT check · rate limit · route /api/payments/** (10 s)   │
                         └───────────────┬──────────────────────────────────────────┘
                                         │ Bearer <alice JWT>
                                         ▼
                         ┌───────────────────────────────┐        ┌──────────────────────────────┐
                         │ payment-service :8083         │  REST  │ account-service :8082        │
                         │  PaymentController            │ ─────► │  GET  /api/accounts/{id}     │
                         │  PaymentService (orchestrator)│ Bearer │  POST /api/accounts/{id}/    │
                         │  AccountServiceClient         │ <alice>│        withdraw | deposit    │
                         │   └ AccountApi (@HttpExchange)│        │  ownership: id AND owner=sub │
                         │      └ RestClient + relay     │        └──────────────┬───────────────┘
                         └───────────────┬───────────────┘                       │
                                         │                                       │
                                 ┌───────▼───────┐                        ┌──────▼──────┐
                                 │ payment_db    │                        │ account_db  │
                                 │ payments      │                        │ accounts    │
                                 └───────────────┘                        └─────────────┘
```

payment-service has **no balances**. It owns the *payment record* (who asked for what, and what happened). account-service remains the only place a balance changes.

### Payment lifecycle

```
  PENDING ──validation or debit refused (404/422)──────► REJECTED              nothing moved
     │    ──account-service unreachable (before debit)──► FAILED                nothing moved
     │    ──debit timed out / 5xx───────────────────────► NEEDS_RECONCILIATION  maybe debited
     ▼ debit OK  (saved as checkpoint)
  DEBITED ──credit OK──────────────────────────────────► COMPLETED             moved
          ──credit refused → refund OK─────────────────► REVERSED              moved and returned
          ──credit timed out, or refund failed──────────► NEEDS_RECONCILIATION  a human must check
```

The transitions are enforced in the entity (`Payment.moveTo`): e.g. a payment can't be `REJECTED` after the money left the source, and a final state is final.

## 4. Request flow: Alice transfers 120 THB from savings to checking

```
Alice ─POST /api/payments {source, target, amount:120}─► gateway ─► payment-service
  1. validate DTO (positive, ≤ 2 decimals, source ≠ target)                          400 if not
  2. INSERT payment (PENDING, payer = token sub)
  3. GET  account-service /api/accounts/{source}    (Bearer alice)   404 → REJECTED SOURCE_ACCOUNT_NOT_FOUND
  4. GET  account-service /api/accounts/{target}    (Bearer alice)   404 → REJECTED TARGET_ACCOUNT_NOT_FOUND
  5. same currency? target ACTIVE?                                   no → REJECTED CURRENCY_MISMATCH / ...
  6. POST account-service /api/accounts/{source}/withdraw {120}       422 → REJECTED INSUFFICIENT_FUNDS
                                                                      timeout/5xx → NEEDS_RECONCILIATION
  7. UPDATE payment → DEBITED                       ◄── checkpoint
  8. POST account-service /api/accounts/{target}/deposit {120}        refused → refund (9)
                                                                      timeout/5xx → NEEDS_RECONCILIATION
  9. (only if 8 was refused) POST /api/accounts/{source}/deposit {120} → REVERSED, or REFUND_FAILED
 10. UPDATE payment → COMPLETED; AUDIT log line
◄─ 201 Created, Location: /api/payments/{id}, body: payment
```

HTTP answers of `POST /api/payments`:

| Status | Payment status | Meaning for the client |
|---|---|---|
| **201** | COMPLETED | Done. |
| **202** | NEEDS_RECONCILIATION | Received, outcome being verified by the bank. **Don't send it again.** |
| **422** | REJECTED / REVERSED (business reason) | Didn't happen; fix the request (more money, other account). Body has `code`, `paymentId`. |
| **503** + `Retry-After` | FAILED / REVERSED (account-service unavailable) | Didn't happen; try again later. |
| 400 / 401 / 403 | (no payment recorded) | Invalid input / no token / not a customer |

## 5. Project structure (new and changed)

```
payment-service/                                   NEW module (port 8083, database payment_db)
├── pom.xml
└── src/main/java/com/banking/paymentservice/
    ├── client/
    │   ├── AccountApi.java                  @HttpExchange interface: getAccount, withdraw, deposit
    │   ├── AccountClientConfig.java         RestClient + JDK HttpClient (timeouts) + proxy factory
    │   ├── BearerTokenRelayInterceptor.java forwards the caller's JWT
    │   ├── AccountServiceClient.java        HTTP → domain outcomes; "did it happen?" classification
    │   ├── AccountServiceProperties.java    banking.clients.account-service.{base-url,connect-timeout,read-timeout}
    │   ├── AccountView.java / MoneyCommand.java      our view of account-service's contract
    │   └── AccountRejectedException.java / AccountServiceUnavailableException.java
    ├── controller/PaymentController.java    /api/payments          (@CustomerOnly)
    ├── controller/AdminPaymentController.java /api/admin/payments  (@StaffReadAccess, ?status=)
    ├── service/PaymentService.java          the orchestrator (validate → debit → credit / compensate)
    ├── entity/Payment.java, PaymentStatus.java   state machine
    ├── repository/PaymentRepository.java
    ├── dto/TransferRequest.java, PaymentResponse.java, PageResponse.java
    ├── exception/GlobalExceptionHandler.java, PaymentNotCompletedException.java, ...
    └── security/                            same resource-server setup as the other services
   src/main/resources/db/migration/V1__create_payments_table.sql
   src/test/.../support/StubAccountService.java   fake account-service on a real socket

api-gateway/src/main/resources/application.yml      route payment-service, response-timeout 10 s
frontend/src/app/pages/payments/                     Transfers page (form + history)
frontend/src/app/core/api.service.ts                 transfer(), myPayments()
frontend/src/app/core/api-error.interceptor.ts       ApiError.code (e.g. INSUFFICIENT_FUNDS)
frontend/e2e/payments.e2e.ts                         transfer + overdraft in a real browser
pom.xml                                              <module>payment-service</module>
```

account-service is **unchanged**. It already had everything payment-service needs: ownership-checked GET, withdraw and deposit, and machine-readable `code`s in its 422 responses (added in Phase 2 with exactly this use in mind).

## 6. Important code

### The declarative client

```java
@HttpExchange("/api/accounts")
public interface AccountApi {
    @GetExchange("/{id}")            AccountView getAccount(@PathVariable UUID id);
    @PostExchange("/{id}/withdraw")  AccountView withdraw(@PathVariable UUID id, @RequestBody MoneyCommand command);
    @PostExchange("/{id}/deposit")   AccountView deposit(@PathVariable UUID id, @RequestBody MoneyCommand command);
}
```

Wiring (`AccountClientConfig`):

```java
HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())     // 1 s
        .version(HttpClient.Version.HTTP_1_1).build();
JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
requestFactory.setReadTimeout(properties.readTimeout()); // 2 s

RestClient restClient = restClientBuilder                // Boot's builder: JSON + (Phase 14) tracing
        .baseUrl(properties.baseUrl().toString())
        .requestFactory(requestFactory)
        .requestInterceptor(new BearerTokenRelayInterceptor())
        .build();

return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
        .createClient(AccountApi.class);
```

| Piece | Role |
|---|---|
| `RestClient.Builder` (injected) | Pre-configured by Spring Boot with the app's `ObjectMapper` and observation hooks |
| `JdkClientHttpRequestFactory` | Uses the JDK's `java.net.http.HttpClient`; no extra dependency |
| `HttpServiceProxyFactory` | Creates the runtime implementation of `AccountApi` |
| `@ConfigurationProperties` record | URL and timeouts from config, so they're different per environment (localhost now, Docker DNS in Phase 15) |

### Token relay

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication instanceof JwtAuthenticationToken jwt) {
    request.getHeaders().setBearerAuth(jwt.getToken().getTokenValue());
}
```

The SecurityContext is thread-bound. That works because the outgoing call happens synchronously **on the request thread**. In `@Async` code or a Kafka listener there is no user and no token. That's one of the reasons for Phase 10.

### "Did it happen?": the classification

```java
} catch (RestClientResponseException ex) {          // we GOT an HTTP response
    404 → AccountRejectedException("ACCOUNT_NOT_FOUND")
    422 → AccountRejectedException(code from problem+json, e.g. INSUFFICIENT_FUNDS)
    other 4xx → Unavailable(outcomeUnknown = false)   // refused: not executed
    5xx       → Unavailable(outcomeUnknown = true)    // could have failed AFTER the commit
} catch (ResourceAccessException ex) {              // NO HTTP response
    ConnectException / HttpConnectTimeoutException / UnknownHostException
              → Unavailable(outcomeUnknown = false)   // request never left
    anything else (read timeout, connection reset)
              → Unavailable(outcomeUnknown = true)    // request left, answer didn't come back
}
```

This table is the most important thing in the phase. For a **read**, unknown outcomes don't matter. For a **write that moves money**, "unknown" must never be reported as "failed" (the customer would retry and pay twice), nor as "completed".

### Orchestrator, step 3 (credit) and compensation

```java
try {
    accounts.credit(payment.getTargetAccountId(), payment.getAmount());
    payment.complete();
    return repository.save(payment);
} catch (AccountServiceUnavailableException ex) {
    if (ex.isOutcomeUnknown()) {
        return reconcile(payment, "CREDIT_OUTCOME_UNKNOWN", ex);   // refunding now could pay out twice
    }
    return compensate(payment, ACCOUNT_SERVICE_UNAVAILABLE, ex.getMessage());
} catch (AccountRejectedException ex) {
    return compensate(payment, ex.getCode(), ex.getMessage());     // e.g. target frozen meanwhile
}
```

`compensate` deposits the amount back into the source → `REVERSED`. If even that fails → `NEEDS_RECONCILIATION / REFUND_FAILED`, logged at ERROR, visible in `GET /api/admin/payments?status=NEEDS_RECONCILIATION`.

### Each step is its own short transaction

`repository.save(payment)` runs in Spring Data's own transaction. **Always use the returned instance** (`payment = repository.save(payment)`): for a detached entity `save` performs a merge and returns a *new* object with the incremented `@Version`. Continuing with the old object would fail the next save with an optimistic-locking error.

## 7. How services communicate

| From → To | How | Identity seen by the receiver |
|---|---|---|
| Browser → gateway | HTTPS (HTTP locally), Bearer JWT | Alice |
| gateway → payment-service | HTTP, same JWT forwarded | Alice |
| payment-service → account-service | **HTTP via `RestClient`, same JWT relayed** | **Alice** |
| payment-service → payment_db | JDBC as `payment_svc` | (database role) |

Consequences of token relay, all visible in tests and live:

- ✅ Bob can't use payment-service to drain Alice's account: account-service answers 404 to *his* token → `SOURCE_ACCOUNT_NOT_FOUND`.
- ❌ Alice can't pay Bob: *her* token can't read or credit Bob's account → `TARGET_ACCOUNT_NOT_FOUND`. Giving customers "deposit into any account" rights would be wrong. **Phase 10** gives payment-service its own identity (Client Credentials) and a narrow internal permission to credit accounts.
- ❌ If Alice's token expires between debit and credit (5-minute tokens, sub-second transfer: unlikely but possible), the credit gets 401 and the transfer is reversed.

## 8. Run it

```bash
docker compose up -d
mvn verify                                   # 145 backend tests
mvn -pl user-service spring-boot:run         # :8081
mvn -pl account-service spring-boot:run      # :8082
mvn -pl payment-service spring-boot:run      # :8083  (new)
mvn -pl api-gateway spring-boot:run          # :8080
cd frontend && npm start                     # :4200
npm test                                     # 14 unit tests
export DEMO_USER_PASSWORD=$(grep -E '^DEMO_USER_PASSWORD=' ../.env | cut -d= -f2-)
npm run e2e                                  # 8 e2e tests
```

In the browser as **alice**: *Accounts* → open two THB accounts, deposit into one → *Transfers* → move money → see `COMPLETED` in the history and the new balances in *Accounts*. Try more than the balance to see `INSUFFICIENT_FUNDS`.

Simulate an outage: stop account-service (Ctrl+C) and transfer again. You get **503** with `Retry-After: 5`, and the history shows `FAILED`. No money moved.

Request file: [http/phase-09.http](http/phase-09.http).

### curl

```bash
TOKEN=$(./infrastructure/keycloak/pkce-login.sh alice 2>/dev/null)
curl -i -X POST http://localhost:8080/api/payments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sourceAccountId":"<savings-id>","targetAccountId":"<checking-id>","amount":120.00,"description":"rent"}'

curl -s http://localhost:8080/api/payments -H "Authorization: Bearer $TOKEN"     # history, newest first

SAM=$(./infrastructure/keycloak/pkce-login.sh sam 2>/dev/null)
curl -s "http://localhost:8080/api/admin/payments?status=NEEDS_RECONCILIATION" -H "Authorization: Bearer $SAM"
```

## 9. Real test results

### Live, with real Keycloak tokens through the gateway

| # | Scenario | Result |
|---|---|---|
| 1 | Alice: savings → checking, 120 THB | **201** COMPLETED, `Location: /api/payments/{id}` (relative). Balances 500 → 380 / 0 → 120 |
| 2 | Alice → **Bob's** account | **422** `TARGET_ACCOUNT_NOT_FOUND` (token relay limit; nothing moved) |
| 3 | Alice: 9 999 THB | **422** `INSUFFICIENT_FUNDS` with account-service's own message, passed through |
| 4 | Alice: THB → USD account | **422** `CURRENCY_MISMATCH` |
| 5 | **Bob** uses Alice's account as source | **422** `SOURCE_ACCOUNT_NOT_FOUND`: account-service's ownership check, through the relay |
| 6 | account-service stopped | **503**, `Retry-After: 5`, `FAILED` in **37 ms** (connection refused is instant) |

Every attempt, including rejected ones, is recorded and visible in the customer's history and the `AUDIT` log:

```
AUDIT : payment=c3b7… payer=b0b5… f763… -> 314c… amount=50 status=REJECTED code=SOURCE_ACCOUNT_NOT_FOUND
AUDIT : payment=14ad… payer=7f3c… f763… -> 9188… amount=5 status=FAILED code=ACCOUNT_SERVICE_UNAVAILABLE
```

### Failures in the middle (automated, against a stub with real sockets)

You can't reliably make account-service fail *between* two calls by hand, so these run against `StubAccountService` (JDK `HttpServer` on a random port, so the timeouts are real):

| Scenario | Expected and verified |
|---|---|
| Debit answers after 2 s, read timeout 300 ms | **202**, `NEEDS_RECONCILIATION / DEBIT_OUTCOME_UNKNOWN`; withdraw sent **exactly once** (no blind retry); listed in the admin queue |
| Credit refused (target frozen meanwhile) | Refund deposit to source → **422**, `REVERSED`; call order withdraw → deposit(target) → deposit(source) |
| Credit times out | `NEEDS_RECONCILIATION / CREDIT_OUTCOME_UNKNOWN`, **no refund** (could duplicate money) |
| Credit and refund both fail | `NEEDS_RECONCILIATION / REFUND_FAILED`, logged at ERROR |
| DB state at each step | saves observed: `PENDING → DEBITED → COMPLETED` |

### Automated suites

| Suite | Count | Highlights |
|---|---|---|
| payment-service | 48 | client classification (connection refused vs read timeout vs 5xx vs 422), token relay header, state machine, orchestrator branches, HTTP mapping 201/202/422/503, ownership 404, staff read-only |
| account-service | 42 | unchanged |
| user-service | 31 | unchanged |
| api-gateway | 24 | + payment route with its own 10 s timeout (global 1 s in the test) |
| Angular unit | 14 | `ApiError.code` from problem+json |
| Playwright e2e | 8 | + open two accounts, deposit, transfer 75 → COMPLETED; transfer 1000 → `INSUFFICIENT_FUNDS` → REJECTED; balances 125 / 75 |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| Every transfer → 503 `ACCOUNT_SERVICE_UNAVAILABLE` "unreachable" | account-service not running, or wrong `ACCOUNT_SERVICE_URL` (in Docker it will be `http://account-service:8082`, not localhost). |
| 503 with "returned 401" | The relayed token was rejected: interceptor not registered, or account-service validates a different issuer/audience. Check both services use the same `KEYCLOAK_ISSUER_URI`. |
| Transfer to another customer → `TARGET_ACCOUNT_NOT_FOUND` | Expected in Phase 9 (token relay). Phase 10 fixes it properly. |
| Client gets **504** but the payment shows COMPLETED later | Gateway timeout shorter than payment-service's worst case. Keep the gateway route timeout > sum of downstream timeouts (here 10 s > 4 × 2 s). |
| Requests hang for minutes when account-service is stuck | No read timeout. The JDK client and `RestTemplate` defaults are infinite. |
| `ObjectOptimisticLockingFailureException` on the second `save` | Kept using the entity passed to `save()` instead of the returned one. |
| `HttpMessageNotReadableException` reading account-service's response | The response DTO doesn't match. Keep client DTOs minimal and tolerant (unknown fields are ignored by Boot's default `ObjectMapper`). |
| Amount `1.005` accepted then rounded | Must not happen: `@Digits(fraction = 2)` → 400, and `setScale(2, UNNECESSARY)` in the entity throws instead of rounding. |
| `IllegalStateException: cannot move from REJECTED to DEBITED` | A code path tried an illegal transition: a bug in the orchestrator, caught by the state machine on purpose. |

## 11. Production notes

- **Idempotency key** on `POST /api/payments` (client-generated `Idempotency-Key` header) so that a client retry after a timeout returns the original payment instead of creating a second one. Also idempotent debit/credit in account-service, keyed by payment ID. **Phase 13.**
- **Reconciliation job**: periodically find `DEBITED` payments older than N seconds and `NEEDS_RECONCILIATION` ones, ask account-service what actually happened (needs per-payment references in the ledger), and finish or compensate. **Phase 13.**
- **Circuit breaker, bulkhead, retries with backoff** (only for safe/idempotent calls). **Phase 12.**
- **Distributed tracing** across gateway → payment → account with one trace ID. **Phase 14.**
- **Service identity and mTLS** between services (service mesh or Spring's SSL bundles). **Phases 10, 16.**
- **Asynchronous alternative**: publish a `TransferRequested` event and let account-service process it (Phase 11 introduces Kafka; Phase 13 combines it with the saga).

## 12. Interview questions

1. When should a service call another synchronously, and when should it publish an event instead?
2. `RestTemplate` vs `RestClient` vs `WebClient` vs `@HttpExchange` interfaces vs OpenFeign: what are the differences?
3. What is token relay? What can it do, and what can't it do (give three limits)?
4. Why can't you use `@Transactional` to make a cross-service transfer atomic? What is 2PC, and why isn't it used here?
5. A withdraw request times out. Did the money move? How should the system react, and why not simply retry?
6. Which failures prove that a request was **not** executed, and which leave the outcome unknown?
7. What is a compensating action? Why is a refund not the same as a rollback?
8. Why save a `DEBITED` checkpoint between debit and credit?
9. How do you choose timeouts across gateway → service → service ("timeout budget")?
10. Why does payment-service call account-service directly instead of through the gateway?
11. Why return 202 for an unknown outcome instead of 500 or 201?
12. What is the "tolerant reader" pattern, and why does payment-service have its own `AccountView` instead of sharing account-service's DTO class?
13. How would you test a read timeout and a refused connection without mocking the HTTP client?
14. Why is holding a DB transaction open during a remote call harmful?
