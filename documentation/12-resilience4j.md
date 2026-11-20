# Phase 12: Resilience with Resilience4j

## 1. Concepts

Phase 9 taught payment-service to **classify** failures ("refused", "not executed", "outcome unknown"). Phase 12 adds **policies** that decide what to *do* about them, so that a sick account-service doesn't make payment-service sick too.

| Pattern | Question it answers | Here |
|---|---|---|
| **Timeout** (since Phase 9) | How long do I wait? | connect 1 s, read 2 s |
| **Retry** | Is it worth trying again? | reads once more; money-moving calls **only if they certainly didn't execute** |
| **Circuit breaker** | Is the other side so unhealthy that I should stop calling it for a while? | opens at ≥ 50 % failures (or ≥ 80 % slow calls) over the last 10 calls |
| **Bulkhead** | How many of my threads may wait on that service at once? | 25 concurrent calls |
| **Rate limiter** | How fast may I call it? | 100 calls/s from payment-service |

### Circuit breaker states

```
             ≥ 50 % of the last 10 calls failed (min. 5 calls)
   CLOSED ──────────────────────────────────────────────► OPEN ──── every call: rejected instantly,
     ▲  calls go through, results are counted                │       account-service isn't contacted
     │                                                       │ after 10 s
     │   2 probe calls succeed           ┌───────────────────▼┐
     └────────────────────────────────── │     HALF_OPEN      │ ── a probe fails ──► OPEN again
                                         └────────────────────┘
```

**Why fail fast?** Without a breaker, every transfer against a hanging account-service waits for its timeouts: 4.2 s each, measured live. Meanwhile payment-service threads pile up, and so do clients' retries. An open breaker answers in **16 ms** and gives account-service room to recover.

## 2. Why each design decision

| Decision | Why |
|---|---|
| **Programmatic decoration** in `AccountServiceResilience`, not `@CircuitBreaker` annotations | The policy sits next to the Phase 9 "did it happen?" classification and is unit-testable without Spring/AOP. The order of the decorators is explicit, line by line. |
| **Translate HTTP errors before the breaker sees them** | The breaker counts `AccountServiceUnavailableException` only. A 422 `INSUFFICIENT_FUNDS` is a *healthy* service saying no: it must not open the breaker (tested: 10 refusals → still CLOSED). |
| **Three retry policies** (`READ`, `WRITE`, `COMPENSATION`) | Retrying is safe only if repeating is harmless. A GET can always be repeated. A withdrawal after a timeout **may already have happened**: retrying could debit twice. |
| **Writes retried only when "certainly not executed"** | Connection refused, 4xx refusal (e.g. expired token) → the request had no effect → a retry is safe and often helps (a restarting pod, a refreshed token). |
| **Refunds bypass the breaker, with more attempts and exponential backoff** | If other customers' failures opened the breaker, refusing *this* refund would strand this customer's money. Refunds are rare, so they can't overload anyone. |
| **Which exceptions retry: code (`RetryPolicies`). How often and how long: YAML** | The first is a business decision, the second operational tuning. |
| **Breaker doesn't affect `/actuator/health`** (`allow-health-indicator-to-fail: false`) | An OPEN breaker means *account-service* is unhealthy. If payment-service reported DOWN, Kubernetes would restart the wrong service (and every replica at once). |
| **Bulkhead (semaphore, 25)** | A slow account-service would otherwise tie up *all* of Tomcat's 200 request threads: payment history and health checks would hang too. |
| **Outgoing rate limiter, fail immediately** | Protects account-service from payment-service bursts. Queuing would only add latency to an already overloaded path. |
| **Bulkhead/rate-limiter rejections are "ignored" by the breaker** | Our own limits aren't evidence that account-service is unhealthy. |
| **Timeout budget recalculated, gateway route 20 s** | Retries make the worst case longer: ≈ 15.6 s. The gateway must wait longer than that, or clients see 504 for transfers that then complete. |
| **No `TimeLimiter`** | It's for async calls. Our synchronous calls already have HTTP connect/read timeouts, which also free the socket. |

## 3. Architecture

```
payment-service
  PaymentService ──► AccountServiceClient ──► AccountServiceResilience.execute(op, type, call)
                                                │
                       Retry(READ | WRITE | COMPENSATION)            outermost: every attempt passes the breaker
                         └ CircuitBreaker "accountService"          (skipped for COMPENSATION)
                             └ RateLimiter "accountService"          100 calls/s, no queueing
                                 └ Bulkhead "accountService"         25 concurrent, 50 ms wait
                                     └ HTTP call + translation (Phase 9) ─────────► account-service
                                                                    timeouts: connect 1 s, read 2 s
  Rejections (CallNotPermitted, BulkheadFull, RequestNotPermitted)
      → AccountServiceUnavailableException(outcomeUnknown = false): certainly not executed
      → transfer FAILED, 503 + Retry-After, nothing moved

  /actuator/circuitbreakers, /circuitbreakerevents, /retries, /metrics (resilience4j.*)   ADMIN only
```

## 4. Request flow: transfers while account-service hangs

```
 t=0    account-service frozen (SIGSTOP): TCP accepts connections, nothing answers
 #1     GET own account ── read timeout 2 s ── retry ── read timeout 2 s ──► FAILED 503   (4.2 s)
 #2     same                                                               ──► FAILED 503   (4.2 s)
        breaker: 8 buffered calls, 50 % failed → OPEN
 #3..5  GET own account ── CallNotPermitted (not sent) ──────────────────────► FAILED 503   (16 ms)
 +10 s  OPEN → HALF_OPEN (automatic)
        account-service resumed; next transfer: 2 probe calls succeed → CLOSED → 201 COMPLETED (52 ms)
```

## 5. Project structure (changes)

```
payment-service/
  pom.xml                                   + io.github.resilience4j:resilience4j-spring-boot3 (version from the BOM)
  client/AccountServiceResilience.java      NEW  decorator chain, CallType, rejection → "not executed"
  client/RetryPolicies.java                 NEW  READ_RETRYABLE / WRITE_RETRYABLE predicates (RetryConfigCustomizer)
  client/AccountServiceClient.java          each call declares its CallType: READ, WRITE, COMPENSATION
  security/SecurityConfig.java              /actuator/** (except health, info) → ADMIN
  application.yml                           resilience4j.* + actuator endpoints; timeout budget comment
  test/.../client/AccountServiceResilienceTest.java   NEW  11 tests
  test/.../support/TestAccountClients.java  NEW  production wiring outside Spring
  test/.../support/StubAccountService.java  + failNext(...) one-shot failures

api-gateway/application.yml                 payment route response-timeout 10 s → 20 s
```

## 6. Important code

### The decorator chain

```java
Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead, call);        // innermost
decorated = RateLimiter.decorateSupplier(rateLimiter, decorated);
if (type != CallType.COMPENSATION) {
    decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);
}
decorated = Retry.decorateSupplier(retryFor(type), decorated);           // outermost
try {
    return decorated.get();
} catch (CallNotPermittedException ex) {     // breaker OPEN: the request was never sent
    throw new AccountServiceUnavailableException("... circuit breaker is " + circuitBreaker.getState(), false, ex);
} catch (BulkheadFullException ex) { ... } catch (RequestNotPermitted ex) { ... }
```

Retry is **outermost**, so each attempt asks the breaker. Once it opens, retries stop too.

### What may be retried

```java
static final Predicate<Throwable> READ_RETRYABLE = ex ->
        ex instanceof AccountServiceUnavailableException unavailable
                && !(unavailable.getCause() instanceof CallNotPermittedException);   // don't knock on an open breaker

static final Predicate<Throwable> WRITE_RETRYABLE = ex ->
        READ_RETRYABLE.test(ex) && !((AccountServiceUnavailableException) ex).isOutcomeUnknown();

@Bean RetryConfigCustomizer writeRetryPolicy() {
    return RetryConfigCustomizer.of("accountServiceWrite", builder -> builder.retryOnException(WRITE_RETRYABLE));
}
```

### Configuration (application.yml, abridged)

```yaml
resilience4j:
  circuitbreaker.instances.accountService:
    sliding-window-size: 10
    minimum-number-of-calls: 5
    failure-rate-threshold: 50
    slow-call-duration-threshold: 1500ms
    slow-call-rate-threshold: 80
    wait-duration-in-open-state: 10s
    permitted-number-of-calls-in-half-open-state: 2
    automatic-transition-from-open-to-half-open-enabled: true
    record-exceptions: [com.banking.paymentservice.client.AccountServiceUnavailableException]
    ignore-exceptions: [...AccountRejectedException, ...BulkheadFullException, ...RequestNotPermitted]
    allow-health-indicator-to-fail: false
  retry.instances:
    accountServiceRead:         { max-attempts: 2, wait-duration: 100ms }
    accountServiceWrite:        { max-attempts: 2, wait-duration: 200ms }
    accountServiceCompensation: { max-attempts: 4, wait-duration: 200ms, enable-exponential-backoff: true, exponential-backoff-multiplier: 2 }
  bulkhead.instances.accountService:    { max-concurrent-calls: 25, max-wait-duration: 50ms }
  ratelimiter.instances.accountService: { limit-for-period: 100, limit-refresh-period: 1s, timeout-duration: 0 }
```

### Timeout budget (worst case of one transfer)

| Step | Worst case |
|---|---|
| 2 reads × (2 attempts × 2 s read timeout + 0.1 s backoff) | 8.2 s |
| debit (no retry after a timeout) | 2.0 s |
| credit (no retry after a timeout) | 2.0 s |
| refund: 2 s, or up to 4 fast "not executed" failures + 1.4 s backoff | ≈ 3.4 s |
| **Total** | **≈ 15.6 s < gateway 20 s** |

## 7. How services communicate

Unchanged paths. What changed is **how payment-service behaves when account-service misbehaves**:

| account-service is… | Before Phase 12 | Phase 12 |
|---|---|---|
| down (connection refused) | 503 in ms | same, plus one retry of the read; breaker opens after a few |
| **hanging** | every transfer waits for timeouts (4 s+), threads pile up | first transfers wait, then **16 ms** fail-fast; bulkhead caps waiting threads |
| flaky (one 503 now and then) | the transfer fails | the read is retried, the transfer succeeds |
| saying "no" (422) | 422 | 422. The breaker doesn't count it |
| recovered | | HALF_OPEN probes, then CLOSED, automatically |

Kafka consumers (Phase 11) already have their own resilience: retries + dead-letter topic, and consumer lag instead of failure when a database is down.

## 8. Run it

```bash
docker compose up -d
mvn verify                                   # 216 backend tests
mvn -pl account-service spring-boot:run      # :8082
mvn -pl payment-service spring-boot:run      # :8083
mvn -pl api-gateway spring-boot:run          # :8080
```

Watch the breaker (ADMIN token, directly on payment-service; actuator isn't routed through the gateway):

```bash
ADA=$(./infrastructure/keycloak/pkce-login.sh ada 2>/dev/null)
curl -s localhost:8083/actuator/circuitbreakers -H "Authorization: Bearer $ADA" | jq
curl -s localhost:8083/actuator/circuitbreakerevents/accountService -H "Authorization: Bearer $ADA" | jq '.circuitBreakerEvents[] | select(.type=="STATE_TRANSITION")'
curl -s localhost:8083/actuator/metrics/resilience4j.circuitbreaker.state -H "Authorization: Bearer $ADA" | jq
```

Make account-service hang (not crash) and send transfers:

```bash
kill -STOP $(lsof -ti tcp:8082 -sTCP:LISTEN)     # frozen: accepts connections, never answers
# ... send transfers, watch latency drop from ~4 s to ~16 ms when the breaker opens ...
kill -CONT $(lsof -ti tcp:8082 -sTCP:LISTEN)     # resume; after 10 s the breaker probes and closes
```

Request file: [http/phase-12.http](http/phase-12.http).

## 9. Real test results

### Live (real services, account-service frozen with SIGSTOP)

| Moment | Result |
|---|---|
| Healthy transfer | 201 in 0.8 s (first call: service token fetch); breaker CLOSED |
| Transfers #1, #2 while frozen | 503 in **4.17 s** each: 2 attempts × 2 s read timeout, `no response` |
| Breaker | **OPEN**: 8 buffered calls, failure rate 50 % |
| Transfers #3–#5 | 503 in **16–18 ms**: `circuit breaker is OPEN`, account-service not contacted (`notPermittedCalls 3`) |
| `/actuator/health` (anonymous) | `{"status":"UP"}`. With ADMIN: circuitBreakers component UNKNOWN, state OPEN |
| Events | `CLOSED_TO_OPEN` 00:23:59 → `OPEN_TO_HALF_OPEN` 00:24:09 (automatic, after 10 s) → `HALF_OPEN_TO_CLOSED` 00:24:14 |
| After resume | first transfer = the 2 probes → 201 COMPLETED in 52 ms; breaker CLOSED |

Worth noticing: the GET requests that timed out while account-service was frozen sat in its socket queue and were **processed after it resumed**. They were reads, so that was harmless. A timed-out **withdrawal** would have executed late in exactly the same way. That's why money-moving calls are never retried after an unknown outcome.

### Automated

| Suite | Count | Highlights |
|---|---|---|
| payment-service | 81 | **AccountServiceResilienceTest (11):** read retried once and succeeds; withdrawal after a 500 **not** retried (exactly 1 call); withdrawal after a 401 retried; 10 × 422 → breaker stays CLOSED; breaker opens, then fails fast in < 50 ms without contacting the stub; probes close it, a failing probe re-opens it; refund passes an OPEN breaker; refund retries 4×; bulkhead rejects the 2nd concurrent call; rate limiter rejects the 3rd without counting a failure. **Integration (+3):** read retry makes a transfer succeed; YAML breaker opens, `/actuator/circuitbreakers` shows OPEN to ADMIN and 403 to customers, health stays UP; business rejections don't open it |
| user / account / transaction / notification | 32 / 56 / 11 / 12 | unchanged |
| api-gateway | 24 | unchanged (payment route timeout raised to 20 s) |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| Breaker opens when customers simply have no money | Business errors recorded as failures. Translate HTTP first; put `AccountRejectedException` in `ignore-exceptions` (or use `record-exceptions`). |
| Money debited twice after a timeout | A write was retried although its outcome was unknown. Retry writes only when "certainly not executed", until Phase 13 makes them idempotent. |
| Kubernetes restarts payment-service whenever account-service has problems | The breaker's health indicator drags `/actuator/health` DOWN. Set `allow-health-indicator-to-fail: false`, and keep breakers out of liveness probes (Phase 16). |
| Retries make everything slower during an outage | Retry inside the breaker instead of outside, or no breaker at all. With Retry outermost, an OPEN breaker stops retries immediately (`READ_RETRYABLE` excludes `CallNotPermittedException`). |
| Client gets 504 but the transfer completed | Gateway timeout < service worst case. Recompute the budget whenever retries or timeouts change. |
| `cannot find symbol Decorators` | `Decorators` is in `resilience4j-all`. Compose with `CircuitBreaker.decorateSupplier(...)` etc. (done here). |
| Breaker never opens in a test | Fewer calls than `minimum-number-of-calls`. Count retries: each attempt is a call. |
| One test's failures open the breaker for the next test | Registries are singletons in the Spring context: `circuitBreakerRegistry.circuitBreaker(name).reset()` in `@BeforeEach`. |
| `/actuator/circuitbreakers` → 403 | By design: ADMIN token required. 401 without a token. |
| Retry config in YAML has no effect | Instance name mismatch (`accountServiceRead` vs code), or the predicate wasn't registered (`RetryConfigCustomizer`). |

## 11. Production notes

- **Idempotent writes** (Phase 13) make "outcome unknown" retryable: the same `Idempotency-Key`/payment ID can be sent again safely, and account-service answers with the original result.
- **Retry budgets / jitter:** add random jitter to backoffs (`randomized-wait-factor`) so many instances don't retry in lockstep.
- **Per-dependency breakers and bulkheads:** one per downstream (account-service, Keycloak token endpoint, future fraud service), so one bad dependency can't consume another's capacity.
- **Alerting:** `resilience4j_circuitbreaker_state` and `not_permitted_calls` in Prometheus/Grafana (Phase 14). An OPEN breaker is a page-worthy signal about the *downstream* service.
- **Gateway-level breakers** (Spring Cloud Gateway `CircuitBreaker` filter with a fallback route) for the edge, in addition to service-level ones.
- **Load shedding at the edge:** the gateway rate limiter (Phase 6) plus these limits form layers: user → gateway → service → downstream.
- **Service mesh** (Istio/Linkerd) can provide timeouts, retries and outlier detection outside the code, but it doesn't know which calls are safe to retry. That knowledge stays in the application.

## 12. Interview questions

1. Explain the circuit breaker states and transitions. What do `minimum-number-of-calls` and `sliding-window-size` do?
2. Why must business errors (422) not count as circuit-breaker failures?
3. Which calls is it safe to retry? Why is retrying a withdrawal after a timeout dangerous, and when is it safe?
4. In which order do you combine retry, circuit breaker, rate limiter and bulkhead, and why?
5. What problem does a bulkhead solve that timeouts alone don't?
6. Why should an open circuit breaker not make the service's health check fail?
7. Why does the refund bypass the circuit breaker? What's the risk, and why is it acceptable?
8. What is a timeout budget? Compute it for the transfer flow.
9. What does "fail fast" buy the caller, the callee, and the users?
10. What happens to requests that timed out on the client while the server was frozen? Why does that matter for writes?
11. Client-side rate limiting vs server-side rate limiting: what does each protect?
12. Annotations (`@CircuitBreaker`, `@Retry`) vs programmatic decoration: trade-offs?
13. How would you test a circuit breaker without waiting 10 seconds or relying on mocks of the HTTP client?
14. What would a service mesh add or remove from this design?
