# Phase 15: Docker

## 1. Concepts

Until now every service ran with `mvn spring-boot:run` on the developer's machine. Phase 15 packages each one as a **container image** and runs the whole platform with one command:

```bash
docker compose --profile app up -d --build
```

| Term | Meaning here |
|---|---|
| **Image** | A read-only, layered file system + start command: `banking/payment-service:local` = Ubuntu + JRE 21 + dependencies + our jar |
| **Container** | A running instance of an image: own process tree, own network address, resource limits |
| **Layer** | One build step's file changes. Unchanged layers are reused from cache and shared between images |
| **Multi-stage build** | Build with the big toolchain (Maven + JDK, Node), ship only the result (JRE + jar, nginx + static files) |
| **Compose** | Declares the containers, networks, volumes and secrets of the system, and their start order |
| **Health check** | A command Docker runs periodically. `depends_on: condition: service_healthy` waits for it |

### What runs where

```
 host (published ports only)                 Compose network "banking-platform_default"
 ───────────────────────────                 ─────────────────────────────────────────────────────────────
 :4200  ──► frontend (nginx)                  DNS = service name:
 :8080  ──► api-gateway ──────────────────►   user-service:8081  account-service:8082  payment-service:8083
 :8180  ──► keycloak                          transaction-service:8084  notification-service:8085
 :5433  ──► postgres     :9092 ──► kafka      postgres:5432  kafka:29092  keycloak:8180  jaeger:4318
 :3000 grafana  :9090 prometheus              loki:3100 ◄── alloy (reads container logs via the Docker API)
 :16686 jaeger  :3100 loki
```

The five services have **no published port**. From the host they are unreachable (tested). The gateway really is the single entry point now.

## 2. Why each design decision

| Decision | Why |
|---|---|
| **One parameterized Dockerfile for the six services** (`--build-arg SERVICE=…`) | They're built and run identically. One file to review, one place to fix. |
| **Multi-stage: Maven build → layer extraction → JRE runtime** | The runtime image has no JDK, Maven, sources or `~/.m2`: smaller, and less for an attacker to use. |
| **Spring Boot layered jar** (`-Djarmode=tools extract --layers`) | Dependencies (~93 MB) and our classes (~120 KB) are separate layers. A code change re-ships kilobytes, and the six images share all their base layers. |
| **BuildKit cache mount for `~/.m2`, `sharing=locked`** | Dependencies are downloaded once for all builds, but never end up in an image. `locked`: found when Compose built all six in parallel. Concurrent Maven writes to one repository failed with `DependencyResolutionException`. |
| **Tests not run in `docker build`** | They need Docker themselves (Testcontainers) and already ran in `mvn verify`. The pipeline is test → build images → deploy. |
| **Non-root user with a fixed UID (10001)** | A compromised app doesn't get root in the container. Kubernetes can enforce `runAsNonRoot` (Phase 16). |
| **`-XX:MaxRAMPercentage=75`, `mem_limit: 512m`** | The JVM sizes its heap from the container limit (384 MiB, verified), leaving room for metaspace, threads and buffers. Without a limit it would size itself from the host's RAM. |
| **`-XX:+ExitOnOutOfMemoryError`** | After an OOM the JVM is in an unknown state. Exit, and let the restart policy (or Kubernetes) start a clean one. |
| **Exec-form `ENTRYPOINT ["java", …]`** | Java is PID 1 and receives SIGTERM directly. A shell wrapper wouldn't forward it, and `docker stop` would kill the JVM after the timeout. |
| **Graceful shutdown** (`server.shutdown: graceful`, 20 s) **< `stop_grace_period: 30s`** | Requests in flight finish; new ones aren't accepted. Tested with a transfer in flight: it completed. |
| **Liveness/readiness probes** (`/actuator/health/liveness`, `/readiness`) | The image's `HEALTHCHECK` and Compose use readiness. In Phase 16 Kubernetes uses both, with different meanings. |
| **Secrets as files** (`/run/secrets/<NAME>`, Compose `secrets:` from `.env`), read via `spring.config.import: optional:configtree:/run/secrets/` | Unlike environment variables they don't appear in `docker inspect`, `/proc/<pid>/environ`, crash reports, or "print all env" debug endpoints. The same mechanism works for Kubernetes Secrets mounted as files. |
| **`.dockerignore`** excluding `.env`, `target/`, `node_modules/`, `.git/` | A secret can't slip into an image layer via `COPY . .`, and the build context stays small. |
| **Issuer vs fetch URLs for Keycloak** | Tokens say `iss=http://localhost:8180/realms/banking` (Keycloak's fixed `KC_HOSTNAME`), so the issuer stays that string. Keys and the token endpoint are fetched at `http://keycloak:8180/...`, because `localhost` inside a container is the container itself. The split from Phase 5 pays off here. |
| **Kafka's internal listener `kafka:29092`** | Prepared in Phase 11: the broker advertises an address that works from containers. |
| **Application services behind a Compose profile (`app`)** | `docker compose up -d` still starts only the infrastructure, for running services from the IDE. `--profile app` adds the application. |
| **nginx-unprivileged for the SPA**, port 8080 inside, `4200:8080` outside | Non-root nginx. 4200 keeps the Keycloak redirect URI unchanged. |
| **nginx: SPA fallback, `no-store` for `index.html`, 1-year cache for hashed assets, CSP and security headers** | Deep links work; users never run a stale release; repeat visits are fast. The CSP limits where scripts load from and where the browser may send data. |
| **Angular `inlineCritical: false`** | Found while writing the CSP: Angular's critical-CSS inlining adds an inline `<script>`, which `script-src 'self'` blocks. |
| **Loki + Alloy for logs** | Containers log JSON (ECS) to stdout. Alloy collects them via the Docker API and labels them by service and level. The trace ID is kept as *structured metadata*, not as a label: a label per request would explode Loki's index. |
| **Docker socket mounted only into Alloy, read-only** | Collecting logs needs it. It's powerful (it sees every container), so never in an app container. |

## 3. Architecture: the build

```
 Dockerfile (root)                                  frontend/Dockerfile
 ┌────────────────────────────────────────┐         ┌──────────────────────────────┐
 │ build  maven:3.9.11-eclipse-temurin-21  │         │ build  node:24.18.0-alpine     │
 │   poms → src → mvn -pl SERVICE -am      │         │   npm ci → ng build            │
 │   (cache mount ~/.m2, sharing=locked)   │         ├──────────────────────────────┤
 ├────────────────────────────────────────┤         │ nginx-unprivileged:1.29.3     │
 │ layers  jarmode=tools extract --layers  │         │   nginx.conf + dist/browser   │
 ├────────────────────────────────────────┤         │   HEALTHCHECK /healthz         │
 │ runtime eclipse-temurin:21.0.13_9-jre   │         └──────────────────────────────┘
 │   user spring (10001)                   │
 │   dependencies/  ← 93 MB, rarely changes│
 │   spring-boot-loader/, snapshot-deps/   │
 │   application/   ← 120 KB, every build  │
 │   JAVA_TOOL_OPTIONS, HEALTHCHECK        │
 │   ENTRYPOINT ["java","-jar","app.jar"]  │
 └────────────────────────────────────────┘
```

## 4. Request flow: start-up and a request

```
docker compose --profile app up -d --build
  1. postgres (healthy) ─┬─► kafka (healthy) ─► kafka-init (topics, exits 0)
                         └─► keycloak (healthy)
  2. user/account/payment/transaction-service: depends_on postgres healthy + kafka-init completed
     notification-service: depends_on kafka-init completed; api-gateway: no dependencies
  3. frontend: depends_on api-gateway healthy
  4. each service: Flyway migrates, Kafka consumers join their groups, readiness → UP → "healthy"

browser :4200 (nginx) ─ login ─► keycloak :8180 ─ token (iss=localhost:8180)
browser ─ Bearer ─► :8080 api-gateway ─► payment-service:8083 ─► account-service:8082
         (keys from keycloak:8180, service token from keycloak:8180/…/token, same issuer)
```

## 5. Project structure (new and changed)

```
Dockerfile                               NEW  multi-stage, all six Spring Boot services
.dockerignore                            NEW
frontend/Dockerfile, .dockerignore       NEW
frontend/nginx.conf                      NEW  SPA fallback, caching, CSP + security headers, /healthz
frontend/angular.json                    production: inlineCritical false (CSP)
docker-compose.yml                       + 7 app services (profile app), loki, alloy, secrets, YAML anchors
*/application.yml                        + configtree import (/run/secrets), health probes, graceful shutdown
infrastructure/monitoring/
  prometheus/prometheus.yml              targets: container names instead of host.docker.internal
  alloy/config.alloy                     NEW  container logs → Loki (service, level; trace_id metadata)
  grafana/provisioning/datasources       + Loki; log line → trace, span → logs
  grafana/dashboards/banking-platform.json   + "Warnings and errors" logs panel
infrastructure/docker/smoke-test.sh      NEW  38 checks against the running stack
```

## 6. Important code

### Compose: one service (abridged)

```yaml
x-java-service: &java-service
  build: { context: ., dockerfile: Dockerfile }
  profiles: [app]
  restart: unless-stopped
  mem_limit: 512m
  stop_grace_period: 30s
  labels: { banking.logs: "true" }

payment-service:
  <<: *java-service
  build: { <<: *java-build, args: { SERVICE: payment-service, PORT: "8083" } }
  image: banking/payment-service:${IMAGE_TAG:-local}
  environment:
    <<: *java-env                      # DB_HOST=postgres, KAFKA_BOOTSTRAP_SERVERS=kafka:29092, …
    ACCOUNT_SERVICE_URL: http://account-service:8082
    KEYCLOAK_TOKEN_URI: http://keycloak:8180/realms/banking/protocol/openid-connect/token
  secrets:
    - { source: payment_db_password, target: PAYMENT_DB_PASSWORD }
    - { source: payment_service_client_secret, target: PAYMENT_SERVICE_CLIENT_SECRET }
  depends_on: *depends-on-data

secrets:
  payment_db_password:
    environment: PAYMENT_DB_PASSWORD     # value from .env, delivered as /run/secrets/PAYMENT_DB_PASSWORD
```

### Secrets as files → Spring properties

```yaml
spring:
  config:
    import:
      - optional:file:.env[.properties]          # local runs
      - optional:file:../.env[.properties]
      - optional:configtree:/run/secrets/         # containers: file name = property name
  datasource:
    password: ${PAYMENT_DB_PASSWORD}             # resolved from /run/secrets/PAYMENT_DB_PASSWORD
```

### Runtime stage

```dockerfile
FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --system --gid 10001 spring && useradd --system --uid 10001 --gid spring ... spring
WORKDIR /app
COPY --from=layers --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=layers --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=spring:spring /workspace/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD curl -fsS "$HEALTH_URL" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Container settings in every service

```yaml
management.endpoint.health.probes.enabled: true     # /actuator/health/liveness, /readiness
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 20s
```

## 7. How services communicate (in containers)

| From → to | Before (host) | Now (Compose network) |
|---|---|---|
| gateway → services | `localhost:8081…8085` | `user-service:8081` … (`*_SERVICE_URL`) |
| payment → account | `localhost:8082` | `account-service:8082` |
| services → Postgres | `localhost:5433` | `postgres:5432` |
| services → Kafka | `localhost:9092` | `kafka:29092` |
| services → Keycloak (keys, tokens) | `localhost:8180` | `keycloak:8180`; issuer still `localhost:8180` |
| services → Jaeger | `localhost:4318` | `jaeger:4318` |
| Prometheus → services | `host.docker.internal:808x` | `<service>:808x` |
| logs | console | stdout (JSON) → Alloy → Loki |
| browser → app | `ng serve` :4200 | nginx :4200, gateway :8080 |

Direct calls to a service (e.g. `localhost:8083/actuator/...` in earlier `.http` files) only work when that service runs on the host. In containers, go through the gateway, or use `docker exec`.

## 8. Run it

```bash
cp .env.example .env            # once; fill in values
docker compose --profile app up -d --build    # everything: build 7 images, start 15 containers
docker compose --profile app ps               # all "healthy" after ~1 min
./infrastructure/docker/smoke-test.sh         # 38 checks
open http://localhost:4200                    # log in as alice

docker compose logs -f payment-service        # JSON log lines
docker compose --profile app up -d --build payment-service   # rebuild + replace one service
docker compose --profile app down             # stop (volumes stay: data survives)
```

Changed `prometheus.yml`? Prometheus doesn't notice by itself: `docker kill -s HUP banking-prometheus`. Changed Grafana provisioning: `docker compose restart grafana`.

The infrastructure alone, for running services from the IDE: `docker compose up -d`.

## 9. Real test results

| Check | Result |
|---|---|
| First build (empty Maven cache) | ~5 min for the first image; then all seven built in one `up --build` |
| Rebuild, nothing changed | **7 s** for all seven images (layer cache) |
| Image sizes (compressed) | user/account/transaction 186 MB, payment 188 MB, notification 158 MB, gateway 149 MB, **frontend 23 MB**. Layers (uncompressed): Ubuntu 110 MB + JRE 244 MB (shared by all six), dependencies ~93 MB, our classes **~120 KB** |
| Start-up | 15 containers healthy; service start-up ~3.5 s |
| Memory | limit 512 MiB → heap **384 MiB** (75 %); in use 220–390 MiB; frontend 10 MiB of 64 |
| Secrets | `docker inspect` shows no password or secret; `/run/secrets/PAYMENT_DB_PASSWORD`, `…CLIENT_SECRET` (read-only files) |
| User | services `uid=10001(spring)`, frontend `uid=101(nginx)` |
| Exposure | `localhost:8081–8085` refused; gateway 401 without token; frontend serves `/payments` (SPA fallback) with CSP |
| **e2e (Playwright, real Chrome) against the containers** | **9/9 passed**: login (PKCE via Keycloak), roles, transfers, Bob sees the money, all under the strict CSP |
| Graceful shutdown | `docker stop payment-service` during a transfer that was waiting on a row lock: the transfer **completed (201)**; "Commencing graceful shutdown… Graceful shutdown complete" in 0.8 s; `docker stop` took 1.4 s; restarted and healthy in 3.4 s |
| Prometheus | 6/6 container targets up (after `SIGHUP`: found that it kept the old host targets until reloaded) |
| Loki | logs of all 6 services; **one trace ID → the log lines of account-, payment- and notification-service** for that transfer; Jaeger shows the same trace across 5 services |
| Grafana | Prometheus, Jaeger and Loki data sources healthy (after restart: provisioning is read at start-up) |
| Smoke test | **38/38** |
| Backend tests (`mvn verify`, with the whole stack running) | 272/272 |

### Found along the way

| Finding | Fix |
|---|---|
| Building six images in parallel: `DependencyResolutionException` | Shared Maven cache needs `sharing=locked` |
| `node:24.11.1`: "Angular CLI requires Node.js v24.15.0" | `node:24.18.0-alpine` |
| Angular's critical-CSS inlining adds an inline script, which the CSP blocks | `inlineCritical: false` |
| Frontend `unhealthy` although the site worked | Alpine resolves `localhost` to `::1`; nginx listens on IPv4. Health check uses `127.0.0.1` |
| Prometheus kept scraping `host.docker.internal` after the config changed | Reload with `SIGHUP` (or restart) |
| Old host targets kept `ServiceDown` firing | They resolved by themselves once those targets were gone |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| 401 `invalid_token` for every request in containers | `KEYCLOAK_ISSUER_URI` set to `http://keycloak:8180/...`: the token says `localhost:8180`. Keep the issuer, change only the JWK/token *fetch* URLs. |
| `Connection refused: localhost:5433` (or `:9092`) in a container | `localhost` is the container itself. Use `DB_HOST=postgres`, `kafka:29092`. |
| Kafka client connects, then hangs / `localhost:9092` in logs | The client used the HOST listener and got `localhost:9092` advertised. Use `kafka:29092`. |
| `Could not resolve placeholder 'PAYMENT_DB_PASSWORD'` | Secret not mounted, wrong `target` name (must equal the placeholder), or `configtree` import missing. |
| `DependencyResolutionException` during a parallel build | Cache mount without `sharing=locked`. |
| Container restarts every few minutes, `OutOfMemoryError` | Heap + metaspace + threads > `mem_limit`. Raise the limit or lower `MaxRAMPercentage`; check `jvm_memory_used_bytes` in Grafana. |
| `docker stop` always takes 30 s | Signals don't reach Java (shell-form ENTRYPOINT), or graceful shutdown longer than the grace period. |
| Container `unhealthy` but works | Check the health check itself: `docker inspect --format '{{json .State.Health}}' <c>` (here: IPv6 localhost). |
| Code change not in the container | `up -d` without `--build` reuses the old image. |
| Blank page / "Refused to execute inline script" in the browser console | CSP vs. an inline script (see `inlineCritical`). |
| Login redirects to an unknown URL | The frontend must stay on `localhost:4200` (registered redirect URI) or the realm needs the new one. |
| Build context huge / slow | `.dockerignore` missing `node_modules`, `target`. |
| Prometheus targets DOWN with `host.docker.internal` | Old config still loaded: `docker kill -s HUP banking-prometheus`. |

## 11. Production notes

- **Tag images immutably** (git SHA, e.g. `banking/payment-service:3fd3efb`) and push to a registry. Never deploy `latest` or `local`.
- **Scan images** (Trivy, Grype, Docker Scout) in CI and rebuild regularly for base-image security fixes. Pin base images by digest (`@sha256:…`) for reproducible builds.
- **Smaller, harder runtime images:** distroless or Chainguard JRE images (no shell, no package manager, no curl). Health checks then move to Kubernetes probes. Or a custom runtime with `jlink`.
- **Faster start-up:** Class Data Sharing (Spring Boot's `-XX:SharedArchiveFile` training run), Spring AOT, or GraalVM native images (~0.1 s start, less memory, longer builds).
- **Read-only root filesystem** (`read_only: true` + a `tmpfs` for `/tmp`) and dropped capabilities (`cap_drop: [ALL]`): next phase, as a Kubernetes `securityContext`.
- **Secrets:** Compose secrets come from `.env` on the machine. In production: Kubernetes Secrets (encrypted at rest), External Secrets Operator with Vault or a cloud secret manager, and rotation.
- **SBOM and provenance:** `docker buildx build --sbom=true --provenance=true` records what's inside an image and how it was built (supply-chain security).
- **Builds in CI:** the same Dockerfile with `docker buildx` and a registry cache (`--cache-to/--cache-from type=registry`), so CI builds also reuse layers.
- **Compose is for one machine.** Scheduling across machines, rolling updates, self-healing and autoscaling: Kubernetes (Phase 16).

## 12. Interview questions

1. What's the difference between an image, a container and a layer? Why does layer order matter?
2. Why use a multi-stage build? What does the runtime image NOT contain, and why does that matter?
3. How does Spring Boot's layered jar speed up builds and deployments?
4. Why must a container not run as root? What else would you restrict?
5. How does the JVM decide its heap size in a container? What happens without `MaxRAMPercentage` or a memory limit?
6. Exec form vs shell form `ENTRYPOINT`: what goes wrong with the shell form on `docker stop`?
7. What is graceful shutdown, and how do `server.shutdown`, the shutdown timeout and `stop_grace_period` relate?
8. Secrets as environment variables vs files: what are the risks of each? How does Spring read files as properties?
9. Why do the services use `keycloak:8180` to fetch keys but still expect `localhost:8180` in the token?
10. What does `localhost` mean inside a container? How do containers find each other in Compose?
11. Liveness vs readiness: what does each mean, and what should NOT be part of liveness?
12. Why are only the gateway and the frontend published? What does that protect?
13. How do container logs get into Loki, and why is the trace ID not a Loki label?
14. Why aren't tests run inside `docker build` here? Where do they run instead?
15. What would you change before using these images in production?
