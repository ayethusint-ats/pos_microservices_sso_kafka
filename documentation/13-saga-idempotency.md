# Phase 13: Saga, idempotency and the transactional outbox

## 1. Concepts

Phases 9–12 left three gaps, each one named where it appeared:

| Gap | Where it showed up | Phase 13 fix |
|---|---|---|
| A client retries a timed-out transfer → **two transfers** | Phase 9: "please do not send it again" | **Idempotency-Key** on `POST /api/payments` |
| payment-service can't retry a timed-out debit/credit → payment stuck in `NEEDS_RECONCILIATION` | Phase 9, Phase 12 (late execution seen live) | **Idempotent payment operations** in account-service (key = payment ID) + a **fence** for unknown debits |
| A commit followed by a Kafka outage → **event lost** ("EVENT LOST" in the log) | Phase 11 (live: deposit with Kafka down) | **Transactional Outbox** in account-service and payment-service |

And the transfer becomes an explicit **saga** with a **recovery job** that finishes unfinished payments by itself.

### Idempotency

An operation is idempotent if doing it twice has the same effect as doing it once. A retry is safe only when the operation is idempotent. That's the core of this phase:

```
client ──Idempotency-Key──► payment-service ──payment ID──► account-service
         same key → same payment                same payment → each money movement once
```

### Saga

A saga is a sequence of **local transactions** in different services, each one recorded before the next starts. If a later step is refused, earlier steps are undone by **compensating transactions**. There's no distributed lock and no two-phase commit. Instead:
- every step is idempotent, so it can be repeated;
- every step's outcome is recorded (the payment's status);
- unknown outcomes are **resolved** later, never guessed.

```
 step            local transaction in account-service     compensation
 validate        (reads only)                             -
 debit source    TRANSFER_OUT  (customer's token)         refund: REFUND (service token)
 credit target   TRANSFER_IN   (service token)            -  (last step: once it succeeded, done)
```

This is an **orchestrated** saga: payment-service (`TransferSaga`) tells account-service what to do, step by step. (A *choreographed* saga would have each service react to the previous service's events instead.)

### Payment statuses

```
  PENDING ──validation/debit refused──────────────► REJECTED              nothing moved
     │    ──account-service unreachable───────────► FAILED                nothing moved
     │    ──debit unknown, then VOIDED (fence)────► FAILED                nothing moved
     ▼ debit OK (or confirmed later by the fence)
  DEBITED ──credit OK─────────────────────────────► COMPLETED             moved
     │    ──credit refused (e.g. target frozen)
     ▼
  COMPENSATING ──refund OK────────────────────────► REVERSED              moved and returned
               ──a late credit had landed─────────► COMPLETED             moved

  any unfinished status ──recovery gave up / refund refused──► NEEDS_RECONCILIATION   a person
```

New: `COMPENSATING`. Changed: `PENDING` and `DEBITED` are no longer "stuck" states that need a human. They're **in progress**, and the recovery job moves them on. `NEEDS_RECONCILIATION` is now rare: only when automation has truly run out of options.

### Transactional outbox

```
  one database transaction (account_db)
  ┌──────────────────────────────────────────────┐
  │ UPDATE accounts SET balance = ...            │
  │ INSERT INTO payment_operations ...           │    commit: all or nothing
  │ INSERT INTO outbox_events (payload = JSON)   │
  └──────────────────────────────────────────────┘
          │ OutboxRelay, every 500 ms: SELECT unpublished ... FOR UPDATE SKIP LOCKED
          ▼
       Kafka  (ack) ──► UPDATE outbox_events SET published_at = now()
```

The event can no longer be lost: it's committed with the data. It can be **sent twice**: the process may die after Kafka acknowledged the event but before the row was marked as published. That's fine, because consumers already deduplicate by `eventId` (Phase 11). So delivery is **at-least-once**, and the effect is exactly-once thanks to idempotent consumers.

## 2. Why each design decision

| Decision | Why |
|---|---|
| **Idempotency key for account-service = the payment ID**, stored in `payment_operations` with PK `(payment_id, leg)` | The payment ID already exists, is unique, and is sent with every call. The **primary key** makes duplicates impossible even when two requests race: the loser's INSERT fails and its whole transaction (including the balance change) rolls back. |
| **Legs `DEBIT` and `SETTLEMENT`**; credit (`TRANSFER_IN`) and refund (`REFUND`) share the SETTLEMENT leg | A payment is credited **or** refunded, never both. account-service enforces it, so even a race between a late credit and a refund can't pay out twice. |
| **Credit requires a recorded debit of the same amount**; refunds only go back to the debited account | Defense in depth (Phase 10 continued): even a compromised payment-service can't create money or redirect a refund. |
| **The fence** (`POST /internal/accounts/debits/{paymentId}/resolve`) instead of re-sending a debit | A debit needs the **customer's token** (their consent, Phase 10). The recovery job has none. So it asks "was it debited?", and if not, account-service records a **VOID**, which makes a debit request still in flight fail on the primary key. The answer can never change afterwards. |
| **Writes are now retried after timeouts** (`WRITE_RETRYABLE = READ_RETRYABLE`) | Phase 12 forbade it because a retry could debit twice. With idempotent operations, a repeat returns the original result. |
| **Debit = "unknown" if ANY attempt timed out** (even if the last attempt got a clean 422) | The timed-out attempt may still execute later, as Phase 12 measured live. Only the fence can say for sure. |
| **Credit failures don't refund at once any more**; the payment stays `DEBITED` and is retried | Phase 9 refunded as soon as the credit "certainly failed". Now a retry is safe, and completing the transfer is what the customer asked for. Refunds happen only after a definite **refusal** (422). |
| **Recovery job with a claim under optimistic locking** (`@Version`), grace period 30 s, exponential backoff, max 10 attempts | Several instances can run it without coordination: one wins the claim, the others skip. The grace period (> the request's ≈ 17 s worst case) keeps it away from live requests. After ~25 min a person takes over (`RECOVERY_EXHAUSTED`). |
| **Idempotency-Key stored on the payment row**, unique `(payer_id, idempotency_key)`, plus a SHA-256 `request_hash` | The key and the result live in the same row, so they can never disagree. The key is per customer, so two customers can't collide. The hash catches a key reused for a *different* request (422 `IDEMPOTENCY_KEY_REUSED`). |
| **Replays return the same status code** and `Idempotent-Replayed: true` | The client's retry logic sees exactly what it would have seen the first time. |
| **A FAILED payment keeps its key**: retry with a NEW key | The key stands for one attempt. FAILED (503) means "nothing moved". A new attempt is a new payment. The frontend creates a new key after every real answer and keeps the key only when there was **no** answer (network error, 504). |
| **Outbox row in the same transaction**, `Propagation.MANDATORY` | An outbox write outside a business transaction would bring the dual-write gap back. MANDATORY makes that a loud error (tested). |
| **Relay: send the whole batch, wait for the acks in order, stop at the first failure** | Pipelined (fast) but ordered: a later event for the same account never overtakes an earlier one that couldn't be sent. |
| **Payment events only for FINAL statuses**, written by `PaymentStore` | Final statuses never change, so exactly one event per payment. "In progress" isn't news for consumers. |
| **Customer deposits/withdrawals stay non-idempotent** | Money between customers moves through payments. Adding keys to the cash simulation would be the same pattern again (see Production notes). |
| **user-service keeps after-commit publishing** | Its topic is compacted and re-snapshotted on every start (Phase 11), so a lost profile event heals itself. Money events can't heal that way, so they got the outbox. |

## 3. Architecture

```
 Angular ── POST /api/payments  Idempotency-Key: <uuid> ──► gateway ──► payment-service
                                                                       │
   PaymentService: same key? → replay stored outcome (no calls)        │
   new payment (PENDING, next_recovery_at = now + 30 s)                │
   TransferSaga.start ─┬─ GET own account (customer token)             │
                       ├─ GET recipient (service token)                │
                       ├─ POST withdraw {paymentId} (customer token)   ├──► account-service
                       │     unknown? → POST debits/{id}/resolve       │      payment_operations (PK payment_id+leg)
                       ├─ POST credit {paymentId} (service token)      │      outbox_events ──► Kafka
                       └─ POST credit {paymentId, refund} (compensation)
   PaymentStore.save: status (+ outbox event if final) in ONE transaction ──► payment_db.outbox_events ──► Kafka

 PaymentRecoveryJob (every 5 s, all instances)
   SELECT payments WHERE status IN (PENDING, DEBITED, COMPENSATING) AND next_recovery_at <= now
   claim (optimistic lock) → TransferSaga.resume:
       PENDING      → resolve debit (fence) → APPLIED: continue with credit │ VOIDED: FAILED
       DEBITED      → credit (idempotent)
       COMPENSATING → refund (idempotent)
   still unfinished after max attempts → NEEDS_RECONCILIATION (RECOVERY_EXHAUSTED)
```

## 4. Request flow: what happens when a step times out

### The debit hangs (live, section 9)

```
 t=0     withdraw {paymentId: P} ─────────────────► account-service (row locked: UPDATE waits)
 t=2.0s  read timeout ─ retry, same P ────────────► (waits too)
 t=4.2s  read timeout → outcome UNKNOWN
         POST debits/P/resolve ───────────────────► no DEBIT row for P → INSERT VOID → "VOIDED"
 t=4.3s  ◄── 503 FAILED DEBIT_NOT_APPLIED (nothing moved, retry with a new key)
 t=11s   lock released: both waiting withdraws continue ─► INSERT (P, DEBIT) → duplicate key → ROLLBACK
         balance unchanged
```

### The credit hangs (live, section 9)

```
 t=0     debit OK (DEBITED)
         credit {P} ──────────────────────────────► account-service (target row locked)
 t=4.2s  two timeouts → ◄── 202 DEBITED CREDIT_OUTCOME_UNKNOWN ("being completed")
 t=8s    lock released: the waiting credit commits (TRANSFER_IN for P)
 t=34s   recovery job: credit {P} again ──────────► (P, SETTLEMENT) exists, same → "replayed", 200
         payment → COMPLETED, PAYMENT_COMPLETED written to the outbox
```

## 5. Project structure (new and changed)

```
account-service/
  db/migration/V2__payment_operations_and_outbox.sql   NEW  payment_operations, outbox_events
  entity/PaymentOperation, PaymentLeg, PaymentOperationKind   NEW  the idempotency ledger
  repository/PaymentOperationRepository                NEW
  service/AccountService            withdraw/credit idempotent by payment ID; resolveDebit (the fence)
  controller/InternalAccountController   + POST /internal/accounts/debits/{paymentId}/resolve
  dto/DebitResolution                                   NEW
  exception/PaymentOperationConflictException           NEW  422 codes (see section 10)
  events/AccountEventPublisher      after-commit Kafka send → outbox insert (MANDATORY)
  outbox/OutboxEvent, OutboxRepository, OutboxWriter, OutboxRelay, OutboxConfig   NEW
  test: PaymentOperationsIntegrationTest (10), OutboxIntegrationTest (3), OutboxRelayTest (3)

payment-service/
  db/migration/V3__idempotency_recovery_and_outbox.sql   idempotency_key, request_hash, COMPENSATING,
                                                         next_recovery_at, recovery_attempts, outbox_events
  entity/PaymentStatus              + COMPENSATING, IN_PROGRESS, new transitions
  entity/Payment                    key, hash, recovery fields; startCompensation, noteIssue, recoveryAttempted
  service/TransferSaga                                  NEW  the saga steps (start = live, resume = recovery)
  service/PaymentRecoveryJob, RecoveryProperties        NEW
  service/PaymentStore                                  NEW  save a step (+ outbox event if final)
  service/PaymentService            Idempotency-Key handling, request fingerprint, replay
  service/TransferResult                                NEW
  controller/PaymentController      Idempotency-Key header, Idempotent-Replayed header
  client/AccountServiceClient       resolveDebit(); debit reports "unknown" if any attempt timed out
  client/RetryPolicies              WRITE_RETRYABLE = READ_RETRYABLE
  events/PaymentEventPublisher      → outbox
  outbox/*                                              NEW  (same as account-service)
  exception/IdempotencyKeyReusedException, InvalidIdempotencyKeyException   NEW
  application.yml                   banking.outbox, banking.payments.recovery, scheduling pool 2,
                                    compensation retries 4 → 2, new timeout budget

frontend/
  core/api.service.ts               transfer(request, idempotencyKey); status COMPENSATING
  pages/payments                    key per transfer, kept only after "no answer"; in-progress message
  e2e/payments.e2e.ts               asserts a UUID Idempotency-Key, a new one per transfer
```

## 6. Important code

### Idempotent debit (account-service)

```java
@Transactional
public AccountResponse withdraw(UUID id, String ownerId, BigDecimal amount, UUID paymentId) {
    Account account = findOwnedOrThrow(id, ownerId);
    if (paymentId != null) {
        Optional<PaymentOperation> previous = operations.find(paymentId, PaymentLeg.DEBIT);
        if (previous.isPresent()) {
            requireRepeat(previous.get(), TRANSFER_OUT, id, amount);   // VOID → 422 PAYMENT_VOIDED, different → 422
            return accountMapper.toResponse(account);                  // a repeat: no second debit
        }
    }
    account.withdraw(amount);
    accountRepository.flush();
    if (paymentId != null) {
        // Same transaction. A concurrent duplicate fails HERE on the primary key and rolls back entirely.
        operations.saveAndFlush(PaymentOperation.moneyMoved(paymentId, TRANSFER_OUT, id, amount));
    }
    recordTransaction(...);   // → outbox, same transaction
    ...
}
```

### The fence

```java
@Transactional
public DebitResolution resolveDebit(UUID paymentId, String clientId) {
    PaymentOperation debit = operations.find(paymentId, PaymentLeg.DEBIT)
            .orElseGet(() -> operations.saveAndFlush(PaymentOperation.voidedDebit(paymentId)));
    return debit.getKind() == VOID ? VOIDED : APPLIED(debit.getAccountId(), debit.getAmount());
}
```

### Idempotency-Key (payment-service)

```java
if (idempotencyKey != null) {
    Optional<Payment> earlier = repository.findByPayerIdAndIdempotencyKey(payerId, idempotencyKey);
    if (earlier.isPresent()) return replay(earlier.get(), idempotencyKey, requestHash);   // hash differs → 422
}
Payment payment = new Payment(..., idempotencyKey, requestHash);
try {
    payment = store.save(payment);
} catch (DataIntegrityViolationException duplicate) {    // two requests, same key, same moment
    return replay(repository.findByPayerIdAndIdempotencyKey(payerId, idempotencyKey).orElseThrow(), ...);
}
payment = saga.start(payment);
```

### Saga steps: never guess

```java
private Payment credit(Payment payment) {           // DEBITED
    try {
        accounts.credit(payment.getTargetAccountId(), payment.getAmount(), payment.getId());
    } catch (AccountServiceUnavailableException ex) {
        return note(payment, "CREDIT_OUTCOME_UNKNOWN", ...);   // stays DEBITED; recovery retries (idempotent)
    } catch (AccountRejectedException ex) {
        payment.startCompensation(ex.getCode(), ex.getMessage());   // a definite "no": undo the debit
        return refund(store.save(payment));
    }
    payment.complete();
    return store.save(payment);
}

private Payment refund(Payment payment) {           // COMPENSATING
    try { accounts.refund(...); }
    catch (AccountServiceUnavailableException ex) { return payment; }      // stays COMPENSATING
    catch (AccountRejectedException ex) {
        if ("PAYMENT_ALREADY_SETTLED".equals(ex.getCode())) { payment.complete(); ... }   // a late credit won
        payment.requireReconciliation("REFUND_FAILED", ...); ...
    }
    payment.reverse();
    return store.save(payment);
}
```

### Recovery: claim, then resume

```java
List<Payment> due = repository.findByStatusInAndNextRecoveryAtLessThanEqualOrderByNextRecoveryAtAsc(
        PaymentStatus.IN_PROGRESS, now, Limit.of(batchSize));
for (Payment candidate : due) {
    candidate.recoveryAttempted(now.plus(backoffAfter(candidate.getRecoveryAttempts() + 1)));
    try { claimed = store.save(candidate); }                       // @Version: one instance wins
    catch (OptimisticLockingFailureException other) { continue; }
    recover(claimed);   // saga.resume(...); RECOVERY_EXHAUSTED after max attempts
}
```

### Outbox relay

```java
List<OutboxEvent> batch = repository.lockNextBatch(batchSize);     // ... FOR UPDATE SKIP LOCKED
List<CompletableFuture<SendResult<String, String>>> acks = batch.stream().map(e -> kafka.send(...)).toList();
for (int i = 0; i < acks.size(); i++) {
    try { acks.get(i).get(sendTimeout); batch.get(i).markPublished(now()); }
    catch (ExecutionException | TimeoutException ex) { batch.get(i).markFailed(...); break; }   // keep order
}
```

## 7. How services communicate (after Phase 13)

| From → to | What | Identity | New in Phase 13 |
|---|---|---|---|
| client → payment-service | `POST /api/payments` | customer | `Idempotency-Key`; 202 for PENDING/DEBITED/COMPENSATING |
| payment-service → account-service | withdraw | customer | idempotent by payment ID; retried after timeouts |
| payment-service → account-service | credit / refund | service | idempotent; needs a recorded debit; credit XOR refund |
| payment-service → account-service | `POST /internal/accounts/debits/{id}/resolve` | service | NEW: the fence |
| account-service → Kafka | `banking.account-transactions` | n/a | via outbox |
| payment-service → Kafka | `banking.payment-events` | n/a | via outbox, final statuses only |

HTTP answers of `POST /api/payments`:

| Status | Meaning | Client should |
|---|---|---|
| 201 | COMPLETED | done |
| 202 | PENDING / DEBITED / COMPENSATING: being finished automatically; NEEDS_RECONCILIATION: a person checks | not send again; poll `GET /api/payments/{id}` |
| 422 | REJECTED, REVERSED, `IDEMPOTENCY_KEY_REUSED` | fix the request |
| 503 + Retry-After | FAILED, nothing moved | retry later **with a new key** |
| no answer / 504 | unknown | retry **with the same key** |

## 8. Run it

```bash
docker compose up -d
mvn verify                                    # 257 backend tests
mvn -pl account-service spring-boot:run       # :8082   (Flyway V2)
mvn -pl payment-service spring-boot:run       # :8083   (Flyway V3)
mvn -pl transaction-service spring-boot:run   # :8084
mvn -pl api-gateway spring-boot:run           # :8080
```

Idempotency-Key:

```bash
ALICE=$(./infrastructure/keycloak/pkce-login.sh alice 2>/dev/null)
KEY=$(uuidgen)
for i in 1 2 3; do
  curl -s -o /dev/null -w "%{http_code} %{time_total}s replayed=%header{idempotent-replayed}\n" \
    localhost:8080/api/payments -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $KEY" \
    -H "Content-Type: application/json" \
    -d '{"sourceAccountId":"<alice-account-id>","targetAccountNumber":"<bob-number>","amount":10.00}'
done
```

Kafka down, nothing lost:

```bash
docker stop banking-kafka
# deposit / transfer as usual: fast, successful
docker exec -it banking-postgres psql -U <admin> -d account_db \
  -c "select seq, published_at, attempts, last_error from outbox_events where published_at is null"
docker start banking-kafka          # the backlog drains within seconds; statements catch up
```

Make a debit hang (lock the source account row), and watch the fence:

```bash
docker exec banking-postgres psql -U <admin> -d account_db \
  -c "BEGIN; SELECT id FROM accounts WHERE id = '<alice-account-id>' FOR UPDATE; SELECT pg_sleep(12); ROLLBACK;" &
# transfer → 503 FAILED DEBIT_NOT_APPLIED after ~4.3 s
docker exec banking-postgres psql -U <admin> -d account_db \
  -c "select leg, kind, amount from payment_operations where payment_id = '<paymentId>'"     # VOID
```

Lock the **target** row instead for a credit hang: 202 DEBITED, then COMPLETED by the recovery job ~30 s later.

Request file: [http/phase-13.http](http/phase-13.http).

## 9. Real test results

### Live (docker-compose Postgres + Kafka, real services, through the gateway)

**Idempotency-Key**

| Request | Result |
|---|---|
| 1st with key K, 10.00 | 201 COMPLETED in 253 ms |
| 2nd, 3rd with K | 201, the **same payment ID**, in **10 ms**, `Idempotent-Replayed: true`, account-service not called |
| Balances | Alice −10.00, Bob +10.00: once |
| K with 99.00 | 422 `IDEMPOTENCY_KEY_REUSED` |
| Key `short` | 400 |

**Kafka stopped** (`docker stop banking-kafka`, 14:37:01)

| Moment | Result |
|---|---|
| Deposit 33.33 | 200 in **13 ms** (Phase 11: also fast, but the event was **lost**) |
| Transfer 4.44 | 201 COMPLETED in 50 ms |
| Outbox | account_db: 3 rows waiting (only the oldest attempted: `attempts 1`, the rest held back to keep order); payment_db: 1 row |
| Kafka started 14:37:18 | backlog **empty at 14:37:22**; transaction-service shows the DEPOSIT 33.33 and the TRANSFER_OUT 4.44 made during the outage |

**Debit hangs** (Alice's row locked 12 s)

| Moment | Result |
|---|---|
| Transfer 50.00 | **503 FAILED `DEBIT_NOT_APPLIED` in 4.30 s**: 2 timed-out attempts, then the fence |
| `payment_operations` | `VOID` row for the payment (AUDIT `action=VOID_DEBIT actor=client:payment-service`) |
| Lock released (14:37:47) | **both** waiting withdraw attempts executed, hit `duplicate key value violates unique constraint` and rolled back |
| Balances | unchanged. In Phase 12 this exact situation could have moved money after the customer was told it hadn't |

**Credit hangs** (Bob's row locked 8 s)

| Moment | Result |
|---|---|
| Transfer 20.00 | **202 DEBITED `CREDIT_OUTCOME_UNKNOWN`** in 4.29 s |
| 14:38:13, lock released | the waiting credit committed (`action=CREDIT`) |
| 14:38:39, recovery job | credit sent again → `TRANSFER_IN_REPLAYED` → **COMPLETED** ("Recovered payment …: DEBITED -> COMPLETED (attempt 1)") |
| Ledger | `DEBIT TRANSFER_OUT 20.00`, `SETTLEMENT TRANSFER_IN 20.00`. Bob +20.00 exactly once |

### Automated

| Suite | Count | Highlights |
|---|---|---|
| account-service | 72 (+16) | **PaymentOperationsIntegrationTest (10):** same debit twice → once; same payment ID with another amount → 422; **5 concurrent identical debits → exactly one** (others 200-replay or 409); no credit without a debit; credit amount must match; credit XOR refund both ways; refund only to the debited account; resolve before debit → VOID, late debit → 422 `PAYMENT_VOIDED`; resolve after debit → APPLIED with account and amount; only payment-service may resolve. **Outbox (3+3):** committed change → row → published (attempts 1); rollback → no row; append without a transaction → `IllegalTransactionStateException`; relay stops at the first failed ack, keeps later rows, records the error; synchronous send failure handled the same |
| payment-service | 106 (+25) | **PaymentIntegrationTest (32):** debit timeout → fence APPLIED → COMPLETED (withdraw retried once, resolve with the service token); fence VOIDED → 503 `DEBIT_NOT_APPLIED`; fence unavailable → 202 PENDING, job ignores it during the grace period, later completes it **without re-sending the debit**; credit unavailable → 202 DEBITED, no outbox event yet, recovery → COMPLETED with exactly one event; refund unavailable → COMPENSATING → recovery → REVERSED; recovery gives up after max attempts → `RECOVERY_EXHAUSTED`, then leaves it alone; Idempotency-Key replay (same ID, no account-service calls), replayed 422, key reused → 422, keys are per customer, **5 parallel requests with one key → one payment, one debit**, malformed key → 400. **PaymentServiceTest (24)**, **PaymentStoreTest (2)**, resilience tests updated (writes retried after timeouts; "unknown" survives a clean last attempt) |
| user / transaction / notification / gateway | 32 / 11 / 12 / 24 | unchanged |
| Angular | 14 unit, **9 e2e** (Chrome) | the e2e transfer asserts a UUID `Idempotency-Key`, a different one for the next transfer |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| 422 `IDEMPOTENCY_KEY_REUSED` | The client reused a key for a different body (amount, account, description). Generate a new key per transfer, reuse it only to retry the same one. |
| Retrying a 503 returns the same 503 forever | The retry used the same key, which stands for the failed attempt. Use a new key after any real answer; the same key only after no answer. |
| 422 `PAYMENT_NOT_DEBITED` from account-service | A credit/refund for a payment whose debit isn't in `payment_operations`. Payments from before Phase 13 have no ledger rows, which is why V3 leaves their `next_recovery_at` NULL. |
| 422 `PAYMENT_VOIDED` in account-service's log | A debit arrived after the fence voided it. Working as intended: that's the late request being stopped. |
| 422 `PAYMENT_ALREADY_SETTLED` | Credit after a refund, or refund after a credit. The saga treats "refund refused because credited" as COMPLETED. |
| 409 on a debit/credit under load | Two identical requests raced; one lost on the primary key or version. payment-service retries it (now allowed) and gets the replay. |
| `IllegalTransactionStateException: No existing transaction found for transaction marked with propagation 'mandatory'` | Something wrote to the outbox outside a business transaction. Call it from the `@Transactional` method that changes the data. |
| Events pile up in `outbox_events`, `last_error` filled | Kafka unreachable, or a topic missing (auto-create is off). Nothing is lost; fix Kafka and the backlog drains. Alert on the backlog size (Phase 14). |
| Events arrive twice at a consumer | Expected (at-least-once). Consumers must deduplicate by `eventId`, as transaction-service and notification-service do. |
| Recovery job never touches a PENDING payment | Grace period not over (`next_recovery_at` in the future), or a pre-Phase-13 row (`next_recovery_at` NULL by design). |
| Recovery attempts all fail right after an outage | The circuit breaker is OPEN (seen in tests): the job's calls are refused until it half-opens. The backoff gives it time. |
| Outbox never drains / recovery never runs | `@EnableScheduling` missing (OutboxConfig). Or the single default scheduler thread is busy: a relay waiting on a down Kafka would delay recovery, hence `spring.task.scheduling.pool.size: 2`. |
| Relay log says `TimeoutException: null` | Found live: `Future.get(timeout)` throws a TimeoutException without a message. The relay now records "no acknowledgement from Kafka within 5000 ms". |

## 11. Production notes

- **Outbox via CDC:** instead of polling, Debezium reads the database's write-ahead log and publishes outbox rows (the "outbox event router"). Lower latency, no polling load, and ordering per key without row locks.
- **Relay ordering across instances:** `SKIP LOCKED` lets several instances relay in parallel, but two instances may then publish events of the same key out of order. Options: a single active relay (ShedLock / leader election), partition the outbox by key hash, or CDC. Here, ordering mostly matters per account, and consumers can use `balanceAfter` to detect gaps.
- **Idempotency key retention:** keys live forever here (on the payment row). APIs like Stripe expire keys after 24 h. That's fine as long as clients never retry later than that.
- **Idempotency for every unsafe endpoint:** deposits/withdrawals should get the same treatment (a key column + unique constraint, or a generic `idempotency_keys` table with the stored response).
- **Reconciliation with the source of truth:** a nightly job compares `payment_operations` with payment statuses (e.g. DEBIT without SETTLEMENT for completed payments) and reports differences. Banks always reconcile, even with sagas.
- **Back-office actions for NEEDS_RECONCILIATION:** "retry now", "refund", "mark resolved" as audited commands (four-eyes principle), never as status edits.
- **Monitoring (Phase 14):** outbox backlog and oldest-unpublished age, payments per status, recovery attempts, `RECOVERY_EXHAUSTED` count. Each is an alert.
- **Exactly-once in Kafka itself** (transactions, `read_committed`) only covers Kafka-to-Kafka. As soon as a database is involved, the outbox + idempotent consumers is the standard answer.
- **Temporal / Camunda / AWS Step Functions** run sagas as durable workflows (state, retries and timers handled by the engine). They're worth it when there are many long-running, multi-step processes.

## 12. Interview questions

1. What is idempotency, and why is it the precondition for safe retries? Which HTTP methods are idempotent by definition, and why isn't POST?
2. How does an `Idempotency-Key` header work? Where would you store the key, and what happens with a concurrent duplicate?
3. Why compare a hash of the request when the key matches? What status code do you return, and why?
4. Why is the idempotency of the debit enforced in account-service and not only in payment-service?
5. Explain the "dual write" problem. How does the transactional outbox solve it, and why is delivery still at-least-once?
6. Polling relay vs CDC (Debezium): trade-offs?
7. What's a saga? Orchestration vs choreography: when would you pick each?
8. What is a compensating transaction? Why isn't a refund the same as a rollback?
9. A debit timed out. Why is re-sending it (with the same payment ID) safe in the live request but not possible in the recovery job? What does the fence do instead?
10. Why must the fence write a VOID record instead of just answering "not found"?
11. How do you stop a payment from being both credited and refunded, even when a late credit and a refund race?
12. How do several instances of a recovery job avoid working on the same payment? What if an instance crashes mid-recovery?
13. Why is there a grace period before the recovery job touches a new payment?
14. When should automatic recovery give up, and what happens then?
15. Two-phase commit (XA) vs saga: why don't microservices use 2PC across services?
