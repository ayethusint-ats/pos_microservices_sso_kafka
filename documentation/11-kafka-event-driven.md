# Phase 11: Event-Driven Communication with Kafka

## 1. Concepts

Until now every interaction was a **request**: "account-service, debit this account", followed by a wait for the answer. Phase 11 adds **events**: "a balance changed", published once to Kafka. Anyone interested reacts to it, whenever they're ready.

| Concept | Meaning in this system |
|---|---|
| **Broker** | The Kafka server. Stores messages durably on disk; here one broker in **KRaft** mode (it is its own controller, no ZooKeeper). |
| **Topic** | A named, append-only log: `banking.account-transactions`, `banking.payment-events`, `banking.customer-events`. |
| **Partition** | A topic is split into partitions (3 here). Order is guaranteed **within** a partition, and parallelism comes **across** partitions. |
| **Key** | Decides the partition: same key → same partition → in order. We key account events by **account ID**, so one account's events are never reordered. |
| **Offset** | A message's position in its partition. Consumers remember (commit) how far they've read. |
| **Consumer group** | A set of instances sharing the work. **Each group gets every message once**; different groups are independent (transaction-service and notification-service both read every account event). |
| **Lag** | end offset minus committed offset: how far behind a consumer is. |
| **At-least-once delivery** | The offset is committed *after* processing. A crash in between means redelivery, so consumers must be **idempotent**. |
| **Compacted topic** | Kafka keeps at least the **latest message per key** forever. Reading it from the beginning gives the current state of everything: a table in a topic. |
| **Tombstone** | A message with a key and a `null` value: "delete this key" on a compacted topic. |
| **Dead-letter topic (DLT)** | Where a consumer puts a message it can't process, so one bad message can't block its partition forever. |

### Commands vs events

| | REST call (Phases 9–10) | Event (Phase 11) |
|---|---|---|
| Example | payment → account: "debit 75" | account-service: "account X was debited 75, balance 425" |
| Sender knows the receiver | yes | **no**: anyone may subscribe |
| Receiver must be up | yes, or the call fails | no: Kafka keeps the message, the consumer catches up |
| Answer | yes, synchronous | none |
| Consistency | immediate | **eventual** (milliseconds here) |
| Good for | "do this now, I need the result" | "this happened, react as you like" |

Phase 11 doesn't replace the transfer's REST calls. A transfer needs an immediate yes/no. Events are used for everything that may **follow** a transfer: statements and notifications.

## 2. Why each design decision

| Decision | Why |
|---|---|
| **account-service publishes every balance change** (not payment-service publishing "transfer done") | account-service is the ledger, the single source of truth for money. Its events also cover deposits and withdrawals, and they know the recipient's owner (payment-service deliberately doesn't since Phase 10). |
| **Key = account ID** | All movements of one account are in one partition, so the statement is in order and `balanceAfter` can be checked for gaps. |
| **`balanceAfter`, `kind`, `paymentId` in the event** | Consumers can display the statement without calling account-service, and link a transfer's debit and credit. |
| **Publish only AFTER COMMIT** (`@TransactionalEventListener`) | Publishing inside the transaction could announce a deposit that then rolls back. |
| **Logged, not thrown, publish failures** | The money already moved and the customer got their answer. Failing the request now would be wrong. The gap ("dual write") is visible and fixed properly in Phase 13 (Transactional Outbox). |
| **`max.block.ms=3000`** | With Kafka down, the producer would block a request thread up to 60 s (default) waiting for metadata. |
| **`acks=all` + idempotent producer** | A message is "sent" only when stored durably; producer retries can't create duplicates in the log. |
| **JSON strings, no type headers** | The contract is the JSON, not a Java class name. Each consumer has its **own** record for the fields it reads (tolerant reader); no shared library couples the teams. |
| **`eventId` + primary key in transaction-service** | At-least-once delivery → duplicates happen. The database refuses a second insert of the same event. |
| **DLT with explicit name `<topic>.DLT`** | Spring Kafka's default suffix changed between versions (see section 10). A missing topic blocked the partition. |
| **Topics created by a script; broker auto-create OFF** | A typo in a topic name fails loudly instead of silently writing into a new topic nobody reads. |
| **Customer data via a compacted topic** (event-carried state transfer) | notification-service needs e-mail addresses. Instead of calling user-service on every message (coupling, and outages spread), it keeps its own copy, rebuilt from the topic on startup. Only name and e-mail are broadcast: no phone, no address. |
| **Startup snapshot in user-service** | Profiles created before Phase 11 (or whose event was lost) would otherwise never reach consumers. On a compacted topic republishing is idempotent. |
| **notification-service: `auto-offset-reset: latest`** | On its very first deployment it must not e-mail customers about last month's payments. transaction-service uses `earliest`: it *wants* the full history. |

## 3. Architecture

```
            REST (Phases 9–10)                              Kafka (Phase 11)
 ┌──────────────┐  debit/credit  ┌────────────────┐
 │payment-service│──────────────►│account-service │──► banking.account-transactions ──┬──► transaction-service ──► transaction_db
 │               │               │ (the ledger)   │     key=accountId, 3 partitions    │    group "transaction-service"  (statements)
 │               │──► banking.payment-events ─────────────────────────────────────────┐ │
 └──────────────┘    key=paymentId                                                    │ └──► notification-service
 ┌──────────────┐                                                                     └───►  group "notification-service"   (e-mails)
 │ user-service │──► banking.customer-events (COMPACTED) key=keycloakUserId ─────────────►  group "...-directory", always from offset 0
 └──────────────┘                                                                            (in-memory directory: who has which e-mail)

 Failures in a consumer: retry 2× (1 s), then the original message goes to <topic>.DLT
```

Kafka in Docker: `apache/kafka:4.1.1`, KRaft, two listeners: `localhost:9092` for services started on your machine, `kafka:29092` for containers (Phase 15).

## 4. Request flow: Alice pays Bob 75 THB, then what?

```
  t=0     POST /api/payments  ─► payment-service ─► account-service: withdraw (Alice's token)  ─┐ COMMIT
                                                                                              └─► event TRANSFER_OUT  key=Alice's account
                                                  ─► account-service: credit (service token)  ─┐ COMMIT
                                                                                              └─► event TRANSFER_IN   key=Bob's account
                              ◄─ 201 Created                                                     ─► event PAYMENT_COMPLETED key=paymentId
  t≈ms    transaction-service  (group transaction-service)   stores both lines; Bob's statement shows +75
          notification-service (group notification-service)  "Hi Bob, you received 75.00 THB…"  (address from its directory)
                                                             "You sent 75.00 THB to account 7742…" to Alice
```

Nobody called transaction-service or notification-service. payment-service doesn't know they exist. Adding an SMS service or a fraud detector means adding a consumer group, with **zero changes** to the producers.

## 5. Project structure (new and changed)

```
docker-compose.yml                        + kafka (KRaft) + kafka-init (one-shot topic creation)
infrastructure/kafka/create-topics.sh     3 topics + 3 DLTs, partitions 3, retention, compaction

account-service/   events/AccountTransactionEvent, TransactionKind, AccountEventPublisher (AFTER_COMMIT)
                   service/AccountService: raises an event per balance change; MoneyRequest.paymentId; CreditRequest.refund
payment-service/   events/PaymentEvent, PaymentEventPublisher; passes paymentId (debit) and refund flag (credit)
user-service/      events/CustomerEvent, CustomerChanged, CustomerEventPublisher (tombstone on delete),
                   CustomerSnapshotPublisher (startup bootstrap)

transaction-service/   NEW :8084, transaction_db
    messaging/AccountTransactionListener       @KafkaListener, group transaction-service, 3 threads
    service/TransactionHistoryService          idempotent record(event)
    config/KafkaConsumerConfig                 JsonMessageConverter, DefaultErrorHandler + DLT
    entity/AccountTransaction (PK = eventId), V1 migration
    controller/ /api/transactions (own), /api/admin/transactions (staff)

notification-service/  NEW :8085, no database
    messaging/NotificationListeners            payment-events + account-transactions
    messaging/CustomerDirectoryListener        compacted topic, seekToBeginning on every start
    notification/ CustomerDirectory, NotificationService, NotificationStore (dedupe), EmailSender (log)
    controller/ /api/notifications (own)

api-gateway/       routes /api/transactions/**, /api/admin/transactions/**, /api/notifications/**
frontend/          pages/activity: statement + e-mails, "Refresh" (eventual consistency)
```

## 6. Important code

### Publish after commit (account-service)

```java
// inside the @Transactional service method
events.publishEvent(new AccountTransactionEvent(UUID.randomUUID(), ..., account.getBalance(), paymentId));

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void publish(AccountTransactionEvent event) {
    kafkaTemplate.send(topic, event.accountId().toString(), toJson(event))
            .whenComplete((result, failure) -> { if (failure != null) log.error("EVENT LOST ... event={}", json, failure); });
}
```

### Producer settings

```yaml
spring.kafka.producer:
  key-serializer:   org.apache.kafka.common.serialization.StringSerializer
  value-serializer: org.apache.kafka.common.serialization.StringSerializer   # JSON made by the app's ObjectMapper
  acks: all
  properties:
    enable.idempotence: true
    max.block.ms: 3000          # don't hang a request thread 60 s when Kafka is down
    request.timeout.ms: 5000
    delivery.timeout.ms: 10000
```

### Consumer: listener, conversion, failure policy (transaction-service)

```java
@KafkaListener(topics = "${banking.kafka.topics.account-transactions}", groupId = "transaction-service")
public void on(@Payload AccountTransactionEvent event, ...) { history.record(event); }

@Bean RecordMessageConverter jsonMessageConverter(ObjectMapper om) { return new JsonMessageConverter(om); }

@Bean CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));   // explicit!
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));            // 2 retries, 1 s
    handler.addNotRetryableExceptions(InvalidEventException.class);   // retrying can't fix bad data
    return handler;
}
```

Not-JSON messages are not retried at all: conversion errors are in `DefaultErrorHandler`'s built-in non-retryable list.

### Idempotent consumer

```java
if (repository.existsById(event.eventId())) return false;            // fast path
try { repository.saveAndFlush(new AccountTransaction(event.eventId(), ...)); }
catch (DataIntegrityViolationException race) { return false; }       // PK = eventId: the DB is the guard
```

`AccountTransaction implements Persistable<UUID>` with `isNew() = true`: the ID is assigned (the event ID), so without it Spring Data would do `SELECT` + merge instead of a plain `INSERT`.

### Compacted topic as a table (notification-service)

```java
@Override
public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
    callback.seekToBeginning(assignments.keySet());    // in-memory copy: re-read everything on every start
}

@KafkaListener(topics = "${banking.kafka.topics.customer-events}", groupId = "notification-service-directory")
public void on(@Payload(required = false) CustomerEvent customer, @Header(KafkaHeaders.RECEIVED_KEY) String id) {
    if (customer == null) directory.remove(id); else directory.upsert(customer);   // null = tombstone
}
```

## 7. How services communicate (after Phase 11)

| From → To | Style | Why this style |
|---|---|---|
| Browser → gateway → services | REST, user JWT | the user waits for an answer |
| payment → account | REST (customer token / service token) | a transfer needs an immediate, definite result |
| account → **transaction**, **notification** | **Kafka** `banking.account-transactions` | statements and mails can follow a moment later; producers shouldn't know consumers |
| payment → **notification** | **Kafka** `banking.payment-events` | same |
| user → **notification** | **Kafka** compacted `banking.customer-events` | replicate the little data notifications need, instead of calling user-service per message |

The two new services only **read** from Kafka and only **serve** their own data. They make no outgoing calls, need no service token, and are unaffected by outages of the services that feed them.

## 8. Run it

```bash
docker compose up -d                       # now also kafka + kafka-init (creates the topics, then exits 0)
docker compose ps -a                       # banking-kafka healthy, banking-kafka-init "Exited (0)"
mvn verify                                 # 202 backend tests (each service starts a Kafka Testcontainer)
mvn -pl user-service spring-boot:run       # :8081
mvn -pl account-service spring-boot:run    # :8082
mvn -pl payment-service spring-boot:run    # :8083
mvn -pl transaction-service spring-boot:run   # :8084  (new)
mvn -pl notification-service spring-boot:run  # :8085  (new)
mvn -pl api-gateway spring-boot:run        # :8080
cd frontend && npm start                   # :4200 → Activity page
```

Watch Kafka from the command line:

```bash
K=/opt/kafka/bin
docker exec banking-kafka $K/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker exec banking-kafka $K/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic banking.account-transactions --from-beginning --property print.key=true --property print.partition=true
docker exec banking-kafka $K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups
# rebuild transaction-service's read model (service stopped, table emptied):
docker exec banking-kafka $K/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group transaction-service --topic banking.account-transactions --reset-offsets --to-earliest --execute
```

Request file: [http/phase-11.http](http/phase-11.http).

## 9. Real test results

### Live, on the docker-compose broker

| Experiment | Result |
|---|---|
| Alice deposits 500 and pays Bob 75 | 3 account events: Alice's DEPOSIT and TRANSFER_OUT **same key, same partition, in order** (balanceAfter 500 → 425); Bob's TRANSFER_IN. Plus PAYMENT_COMPLETED. Both consumer groups at lag 0. |
| **transaction-service stopped**, 2 more transfers | Transfers **201** (producers don't care). Lag grows to **4**. Statement endpoint 502 meanwhile (that service is down). |
| transaction-service restarted | Catches up to lag 0; Bob's statement has all 3 incoming transfers. |
| **Read model rebuilt**: table wiped, offsets reset to earliest, restart | **15 rows, net 890.00**: identical to before, recreated purely from the log. |
| **Kafka stopped**, Alice deposits 1.00 | **200 in 13 ms**, balance 395 → 396 committed. Event logged `EVENT LOST (replay manually) … event={…}`; statement stays at 395: the **dual-write gap** (Phase 13). |
| Kafka back; the logged JSON replayed **twice** with the console producer | Statement shows the deposit **once** (balanceAfter 396). Idempotency by event ID. |
| Poison message `not-json{{` on the real topic | **Two** copies in `banking.account-transactions.DLT`, one per consumer group (header `kafka_dlt-original-consumer-group`), each with the exception. The next deposit is processed normally (partition not blocked). |

### Automated

| Suite | Count | Highlights (Phase 11) |
|---|---|---|
| user-service | 32 | create/update/delete → current state, then a **tombstone**; phone number not broadcast |
| account-service | 56 | committed changes published keyed by account, in order, ISO dates; **refused change publishes nothing**; refund labelled REFUND |
| payment-service | 67 | outcome published once, keyed by payment; rejections too; debit carries the payment ID |
| transaction-service | 11 | event → statement line; ownership (Bob can't read Alice's, even by account ID); **duplicate stored once**; **poison + invalid event → DLT, next event still processed** |
| notification-service | 12 | recipient mailed at the address from the compacted topic; payer mailed about the outcome; **redelivery mails once**; tombstone forgets the customer |
| api-gateway | 24 | unchanged |
| Angular unit | 14 | |
| Playwright e2e | 9 | Alice pays Bob → Bob's **Activity** shows TRANSFER_IN and "You received 40.00 THB…"; Alice's shows TRANSFER_OUT |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| A test talks to the docker-compose broker instead of its Testcontainer | `@Value("${spring.kafka.bootstrap-servers}")` still says `localhost:9092`: `@ServiceConnection` provides a `KafkaConnectionDetails` **bean**, it doesn't change the property. Inject `KafkaConnectionDetails`. (Happened here: stray `test-…` consumer groups appeared on the dev broker.) |
| One bad message blocks a partition; logs show `UNKNOWN_TOPIC_OR_PARTITION` for `…-dlt` | `DeadLetterPublishingRecoverer`'s **default** destination is `<topic>-dlt` in Spring Kafka 3.3 (it used to be `.DLT`); with auto-create off, the producer waits for a topic that never comes. Name the destination explicitly. |
| A test consumer never sees a topic that was created after it subscribed | Consumers discover new topics only on metadata refresh (minutes). Create topics up front (`create-topics.sh`; `NewTopic` beans in tests). |
| `Connection to node -1 (localhost/127.0.0.1:9092) could not be established` | Kafka not running (`docker compose up -d kafka`), or wrong listener: containers must use `kafka:29092`, the host uses `localhost:9092`. |
| Containers can connect once, then fail | `KAFKA_ADVERTISED_LISTENERS` returns an address the client can't reach (classic "advertised listener" mistake). |
| Request hangs ~60 s when Kafka is down | `max.block.ms` default. Set it low on producers in request paths. |
| Topics/messages vanish after `docker compose down` + `up` | Data in `/tmp/kraft-combined-logs` (image default) instead of the volume: set `KAFKA_LOG_DIRS`. |
| `Cannot convert from [java.lang.String] to [...Event]` | No `JsonMessageConverter` bean, or a producer sent type headers for a class the consumer doesn't have. |
| Duplicates in the statement | Consumer not idempotent. Use the event ID as key/unique constraint. |
| notification-service mails "unknown address" | Directory not loaded yet, or the profile was never published: user-service's startup snapshot fixes old profiles. |
| `kafka-consumer-groups --reset-offsets` fails: group is active | Stop the consuming service first; offsets can only be reset for an inactive group. |
| Killing a service with `lsof -ti tcp:PORT \| xargs kill` also kills its **clients** | Without `-sTCP:LISTEN`, lsof lists every process with a connection to that port (it took down the gateway during testing). |

## 11. Production notes

- **3 brokers, replication factor 3, `min.insync.replicas=2`**: with `acks=all`, a write survives a broker loss.
- **Security:** TLS + SASL (SCRAM or OAuth via Keycloak) between clients and brokers, and **ACLs** so only account-service may write `banking.account-transactions`. Right now any client on the network could publish a fake "deposit".
- **Schema governance:** a schema registry (Avro/Protobuf/JSON Schema) with compatibility checks in CI, instead of convention.
- **Transactional Outbox** to close the dual-write gap (**Phase 13**), and **idempotent producers end-to-end** (keys, event IDs).
- **DLT operations:** alerts on DLT growth, a tool to inspect and re-drive messages after a fix.
- **Monitoring consumer lag** (Phase 14): rising lag is the first sign of a stuck or slow consumer.
- **Data retention vs privacy:** compacted topics keep data until a tombstone; account events are retained 30 days here.

## 12. Interview questions

1. Commands vs events: when do you call a service, and when do you publish an event?
2. What do topics, partitions, keys and offsets guarantee about ordering? How did we keep each account's statement in order?
3. What is a consumer group? How can two services each receive every message, while two instances of one service share them?
4. At-most-once, at-least-once, exactly-once: which do we have, and what does that require from consumers?
5. What is the dual-write problem? Show where it exists in account-service and how the Outbox pattern solves it.
6. Why publish after commit instead of inside the transaction?
7. What is a poison message? How do retries + a dead-letter topic handle it, and why are some errors not retried?
8. What is a compacted topic? How does notification-service rebuild its directory after a restart? What is a tombstone for?
9. Event-carried state transfer vs calling the owning service: trade-offs?
10. What is consumer lag, and what does it tell you? How would you rebuild a read model from scratch?
11. Why did notification-service use `auto-offset-reset: latest` and transaction-service `earliest`?
12. Why doesn't every service share one `events` library with the event classes?
13. How do advertised listeners work, and why are there two in docker-compose?
14. What does eventual consistency mean for the UI? How did the Activity page and its e2e test deal with it?
