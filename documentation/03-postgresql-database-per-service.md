# Phase 3 — PostgreSQL and Database-per-Service

## 1. Concept: each service owns its data

**Database-per-service** means a service's tables are private. Other services get the data only through that service's API or its events, **never** through SQL.

```
  ✅ payment-service ──HTTP──► account-service ──SQL──► account_db
  ❌ payment-service ─────────────────SQL────────────► account_db
```

Why it matters:

| Without it (shared DB) | With it |
|---|---|
| Renaming a column can break three other teams' services | Schema is an internal detail; only the API is a contract |
| One slow report query locks tables for everyone | Load is isolated per service |
| "Just join the tables" creates hidden coupling | Coupling is explicit and visible (HTTP/events) |
| Can't choose a different DB per service | Each service picks what fits (Postgres, Redis, …) |

What it costs: **no cross-service JOINs and no cross-service ACID transactions.** A transfer touching two services needs a Saga (Phase 13), and history across services needs events (Phase 11).

### Levels of isolation

| Option | Isolation | Cost | Used here |
|---|---|---|---|
| Shared schema, shared tables | none | lowest | ❌ anti-pattern |
| Schema-per-service, one DB | logical | low | — |
| **Database-per-service, one server** | logical + enforced by DB privileges | low | ✅ local dev |
| Server-per-service | physical (CPU, memory, failures) | highest | production for critical services |

We **enforce** the boundary with Postgres privileges, not just by convention. `user_svc` gets `FATAL: permission denied for database "account_db"`.

## 2. Why the other changes

- **Flyway instead of `ddl-auto: create-drop`.** Hibernate-generated schemas aren't reviewed, versioned, or reproducible, and `update` never drops or renames safely. Flyway scripts are code: reviewed, versioned in git, and applied in the same order everywhere.
- **`ddl-auto: validate`.** Hibernate checks at startup that the tables match the entities. A missing migration fails fast at deploy time, not at 3 a.m. on the first query.
- **DB constraints (`CHECK balance >= 0`, enum CHECKs).** The database is the last line of defense if a code path bypasses the Java rules.
- **Testcontainers instead of H2.** H2 "PostgreSQL mode" isn't PostgreSQL: CHECK regexes, `TIMESTAMPTZ`, locking and error messages all differ. Tests now run against the exact image used in Compose.

## 3. Architecture diagram

```
                     docker compose  (container: banking-postgres, host port 5433)
                    ┌──────────────────────────────────────────────────────────────┐
user-service :8081 ─┼─ role user_svc ────────► user_db         (customers)          │
                    │                                                              │
account-service ────┼─ role account_svc ─────► account_db      (accounts)           │
     :8082          │                                                              │
                    │  role payment_svc ─────► payment_db      (empty, Phase 9)     │
                    │  role transaction_svc ─► transaction_db  (empty, Phase 11)    │
                    │                                                              │
                    │  REVOKE ALL ON DATABASE x FROM PUBLIC → only the owner gets in│
                    └──────────────────────────────────────────────────────────────┘
                    Volume postgres-data keeps the data between restarts.
```

## 4. Startup flow (what happens when account-service starts)

1. `spring.config.import` loads `../.env` → `ACCOUNT_DB_PASSWORD` and `POSTGRES_PORT` become properties.
2. HikariCP opens a pool to `jdbc:postgresql://localhost:5433/account_db` as `account_svc`.
3. **Flyway** reads `flyway_schema_history`, compares it with `classpath:db/migration`, and applies pending scripts in version order, each in a transaction. (First start: `Successfully applied 1 migration … now at version v1`. Later starts: `Schema "public" is up to date`.)
4. **Hibernate** starts with `ddl-auto: validate` and compares entities to tables. On a mismatch → startup fails with `Schema-validation: missing column [...]`.
5. Tomcat starts on 8082. `/actuator/health` now includes a `db` component.

Spring Boot always runs Flyway **before** the JPA `EntityManagerFactory`, so validation sees the migrated schema.

## 5. Files added and changed

```
banking-platform/
├── docker-compose.yml                                   NEW  Postgres 17.12 + healthcheck + volume
├── .env.example                                         +POSTGRES_PORT
├── infrastructure/postgres/init/
│   └── 01-create-service-databases.sh                   NEW  roles + DBs + REVOKE
├── user-service/
│   ├── pom.xml                                          H2 → postgresql, flyway, testcontainers
│   ├── src/main/resources/application.yml               Postgres datasource, validate, Flyway
│   ├── src/main/resources/db/migration/V1__create_customers_table.sql
│   └── src/test/java/.../TestcontainersConfiguration.java
└── account-service/  (same pattern)
    ├── src/main/resources/db/migration/V1__create_accounts_table.sql
    └── src/test/java/.../repository/DatabaseConstraintsTest.java   NEW
```

## 6. Important configuration explained

| Setting | Meaning |
|---|---|
| `spring.config.import: optional:file:../.env[.properties]` | Reads the git-ignored `.env` as a properties file. A **local-dev convenience**; in containers the same names arrive as environment variables. |
| `password: ${USER_DB_PASSWORD}` (no default) | Secrets have no fallback in code. A missing secret should fail loudly. |
| `url: …:${DB_PORT:${POSTGRES_PORT:5433}}/user_db` | Nested placeholders: an explicit `DB_PORT` wins, otherwise use the port Compose publishes. In Docker (Phase 15) we set `DB_HOST=postgres`, `DB_PORT=5432`. |
| `hikari.maximum-pool-size: 10` | 4 services × 2 replicas × 10 = 80 connections, close to Postgres' default `max_connections=100`. Pool sizing is a **system-wide** budget. |
| `hibernate.jdbc.time_zone: UTC` | Timestamps are stored and read in UTC no matter where the JVM runs. |
| `TIMESTAMPTZ` columns | Stores an absolute instant, the right match for `java.time.Instant`. |
| `NUMERIC(19,2)` | Exact decimal, matching `BigDecimal` with `precision=19, scale=2`. |
| `@ServiceConnection` on the container bean | Spring Boot 3.1+ wires URL, user, and password from the running container, so tests need no properties. |
| `postgres:17.12-alpine` pinned | `latest` or `17` would silently change under you; tests and Compose use the same tag. |
| `${VAR:?message}` in Compose | Compose refuses to start if `.env` is missing a value. |
| Init script runs only on an **empty** volume | Changing it later needs `docker compose down -v` (deletes data). |

### Migration naming rules

`V<version>__<description>.sql`, with **two** underscores. Once applied, a script is **immutable** (Flyway stores its checksum). Fix mistakes with a *new* version. In teams, prefer timestamps (`V20261119_1__...`) to avoid version collisions between branches.

### Zero-downtime schema changes (expand/contract)

During a rolling deploy, the old and new versions of a service run at the same time against one schema. So you never rename a column in one step:

1. **Expand:** add the new column (nullable) and write to both.
2. Backfill existing rows.
3. Switch reads to the new column.
4. **Contract:** drop the old column in a later release.

## 7. How services communicate

Still not at all, and now the database guarantees it. The only way for payment-service to touch balances (Phase 9) is account-service's API.

## 8. Commands

```bash
cd banking-platform
cp .env.example .env        # first time only, then put real passwords in .env

docker compose up -d        # start Postgres
docker compose ps           # wait for "(healthy)"
docker compose logs postgres | grep "Creating database"

mvn verify                  # 30 tests, integration tests use Testcontainers (Docker must be running)

mvn -pl user-service spring-boot:run        # terminal 1
mvn -pl account-service spring-boot:run     # terminal 2

docker compose down         # stop, keep data
docker compose down -v      # stop and DELETE data (re-runs the init script next start)
```

### Inspect the databases

```bash
# As admin: list databases, owners and privileges
docker exec -it banking-postgres psql -U postgres -c '\l'

# Look inside account_db
docker exec -it banking-postgres psql -U postgres -d account_db \
  -c 'select account_number, balance, status, version from accounts'

# Flyway's bookkeeping
docker exec -it banking-postgres psql -U postgres -d account_db \
  -c 'select version, description, success, installed_on from flyway_schema_history'

# Prove isolation (expect: permission denied for database "account_db")
set -a; . ./.env; set +a
docker exec -e PGPASSWORD="$USER_DB_PASSWORD" banking-postgres \
  psql -h localhost -U user_svc -d account_db -c 'select 1'
```

From a GUI client (DBeaver, IntelliJ): host `localhost`, port **5433**, database `account_db`, user `account_svc`, password from `.env`.

## 9. Testing

The API is unchanged from Phase 2, so [http/phase-02.http](http/phase-02.http) and the curl commands still work. What's new: **restart a service and the data is still there.**

```bash
curl -s localhost:8082/actuator/health
# {"status":"UP","components":{"db":{"status":"UP","details":{"database":"PostgreSQL",...}}, ...}}
```

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Connection to localhost:5433 refused` | Postgres isn't running: `docker compose up -d`, then `docker compose ps`. |
| `password authentication failed for user "user_svc"` | You changed `.env` **after** the volume was created; the init script only runs once. Run `docker compose down -v && docker compose up -d` (deletes data), or `ALTER ROLE user_svc PASSWORD '...'`. |
| Password is literally `${USER_DB_PASSWORD}` / auth failed when running from an IDE | The IDE's working directory doesn't contain `.env` or `../.env`. Set the env var in the run configuration, or set the working directory to the module or project root. |
| `Schema-validation: missing column [x] in table [y]` | The entity changed without a migration. Add `V2__...sql`. |
| `Schema-validation: wrong column type ... found [varchar], but expecting [...]` | The migration's type doesn't match the entity mapping (e.g. `TIMESTAMP` vs `TIMESTAMPTZ`). |
| `Validate failed: Migration checksum mismatch for migration version 1` | An applied migration file was edited. Revert the edit and add a new version. (Locally you can also reset with `down -v`.) |
| `Could not find a valid Docker environment` in tests | Docker Desktop isn't running. Testcontainers needs it. |
| `port is already allocated` on 5433 | Something else uses the port. Set `POSTGRES_PORT=5434` in `.env` (the services follow it automatically). |
| `Unsupported Database: PostgreSQL 17` from Flyway | The `flyway-database-postgresql` module is missing (Flyway 10+ split DB support into modules). |
| `TLS handshake timeout` on `docker pull` | Network hiccup inside Docker Desktop's VM right after it starts. Retry. |

## 11. Trade-offs we accepted (and production differences)

- **One Postgres server for all services.** Cheap and simple locally. In production, critical services (accounts, payments) usually get their own instance or a managed DB, so one service's load or outage can't affect the other.
- **The app role also runs migrations.** Production often separates a *migration role* (DDL) from a *runtime role* (only SELECT/INSERT/UPDATE/DELETE), so a SQL injection can't `DROP TABLE`.
- **No FK from `accounts.owner_id` to customers.** Impossible across databases by design. Consistency comes from Keycloak being the identity source (the token proves the user exists).
- **Secrets in a `.env` file.** OK for a laptop. Production uses a secret store (Kubernetes Secrets + external secrets operator, Vault, AWS Secrets Manager).

## 12. Interview questions

1. What is the database-per-service pattern? What problems does it solve and what does it make harder?
2. How do you query data that spans two services (e.g. "customers with balance > X")? (API composition, CQRS read models fed by events.)
3. Why not use `ddl-auto: update` in production?
4. What does Flyway store in `flyway_schema_history`, and why can't you edit an applied migration?
5. How do you rename a column with zero downtime? (Expand/contract.)
6. Why use Testcontainers instead of H2 for integration tests?
7. How do you size a connection pool, and why is it a system-wide concern in microservices?
8. Why store `Instant` as `TIMESTAMPTZ`? What goes wrong with `TIMESTAMP` and a JVM in a different time zone?
9. Why have DB CHECK constraints when the Java code already validates?
10. Shared server vs separate servers per service: what are the trade-offs?
11. How would you keep DB credentials out of source code in Kubernetes?
