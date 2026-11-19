# Phase 6 — Spring Cloud Gateway

## 1. Concept: one front door

An **API Gateway** is a reverse proxy that is the **single entry point** for external clients. The browser knows exactly one URL (`http://localhost:8080`). The gateway knows where everything lives.

What belongs in a gateway (**cross-cutting edge concerns**):

| Concern | Here |
|---|---|
| Routing by path | `/api/customers/**` → user-service, `/api/accounts/**` → account-service |
| Authentication at the edge | JWT validated before any service is touched |
| CORS | Configured once, for the browser app's origin |
| Rate limiting | Per-user token bucket |
| Timeouts and upstream error mapping | 502 / 504 as problem+json |
| Correlation ID | `X-Request-Id` on every request and response |

What does **not** belong in a gateway: **business logic and business authorization** ("may alice withdraw from account X?"). The gateway doesn't own the data to decide that, and putting it there turns the gateway into a distributed monolith that every team has to change.

### Gateway vs Backend-for-Frontend (BFF)

A *gateway* is generic. A *BFF* is a gateway shaped for one client (e.g. one for the web app, one for mobile), often aggregating calls and holding tokens server-side in a cookie session. We use a plain gateway now. Phase 7 discusses when a BFF is the better choice for token security.

## 2. Why we need it

Without it, Angular would need five service URLs, five CORS configs, and five rate limits; internal topology would leak to the internet; and a bad token would be processed by every service separately. With it:

- **One** public origin and **one** place for edge policy.
- Services can move, split, or scale without the client noticing.
- Invalid traffic (no token, forged token, floods) is rejected before it costs a service anything.

**Trade-offs:** an extra network hop (~1 ms on a LAN), and a component that must be **highly available** (run ≥ 2 replicas). It can also become a bottleneck if it gets too clever.

## 3. Architecture

```
   Browser (Angular :4200)                                                curl / Postman
           │  Origin: http://localhost:4200                                      │
           └───────────────┬─────────────────────────────────────────────────────┘
                           ▼
 ┌──────────────────────── API Gateway :8080 (Spring Cloud Gateway, WebFlux/Netty) ───────────────┐
 │ WebFilters (every request)                                                                     │
 │   RequestIdFilter      X-Request-Id: reuse safe client ID or generate a UUID                   │
 │   Spring Security      CORS ─► JWT (signature, exp, iss, aud) ─► allow-list rules              │
 │ Gateway (matched routes only)                                                                  │
 │   RoutePredicateHandlerMapping   Path=/api/customers/**,/api/whoami ─► user-service             │
 │                                  Path=/api/accounts/**             ─► account-service          │
 │   default-filters: RequestRateLimiter (token bucket per user) ─► 429                           │
 │   NettyRoutingFilter: connect 2 s, response 5 s ─► 502 / 504 via GatewayErrorHandler           │
 └──────────────┬──────────────────────────────────────┬─────────────────────────────────────────┘
                │ same Authorization header (relayed)   │
                ▼                                       ▼
        user-service :8081                      account-service :8082
        (validates the JWT AGAIN)               (validates the JWT AGAIN)
```

## 4. Request flow: `GET http://localhost:8080/api/whoami`

1. **Netty** accepts the connection (non-blocking event loop: a few threads serve thousands of connections).
2. **RequestIdFilter** (highest precedence) sets `X-Request-Id` on the request, the exchange attributes, and later the response.
3. **Spring Security WebFilterChain:**
   - `CorsWebFilter`: a preflight is answered right here; a request from a disallowed origin gets 403.
   - `AuthenticationWebFilter`: `NimbusReactiveJwtDecoder` checks signature, `exp`, `iss`, `aud` → 401/503 on failure.
   - `AuthorizationWebFilter`: `/api/**` authenticated; `/actuator/**` (except health/info) needs ADMIN; anything else is **denied**.
4. **RoutePredicateHandlerMapping** finds the first route whose predicates match (`Path=/api/customers/**,/api/whoami` → `user-service`).
5. **Gateway filter chain** for that route: default filter `RequestRateLimiter` → `KeyResolver` returns `user:<sub>` → `InMemoryRateLimiter` takes a token, or gives 429 + `Retry-After`.
6. **NettyRoutingFilter** forwards the request to `http://localhost:8081/api/whoami`, path unchanged, **`Authorization` header included** (token relay).
7. user-service validates the token **again**, runs the controller, and responds.
8. **NettyWriteResponseFilter** streams the response back. Rate-limit and request-ID headers are added.

Real output:

```
HTTP/1.1 200 OK
X-RateLimit-Remaining: 19
X-Request-Id: 7032326c-1ea4-4e58-90ad-0bd6f45403f8
{"subject":"7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c","username":"alice","clientId":"banking-frontend",...}
```

## 5. Project structure

```
api-gateway/
├── pom.xml                                  spring-cloud-starter-gateway-server-webflux (NO spring-boot-starter-web!)
└── src/main/java/com/banking/gateway/
    ├── ApiGatewayApplication.java
    ├── config/GatewayProperties.java        banking.gateway.cors / rate-limit (validated)
    ├── security/
    │   ├── GatewaySecurityConfig.java       reactive filter chain, JWT decoder, CORS
    │   ├── ReactiveSecurityErrorHandlers.java  401 / 403 / 503
    │   ├── KeycloakRealmRoleConverter.java  (same as services)
    │   └── JwtProperties.java
    ├── ratelimit/
    │   ├── TokenBucket.java                 the algorithm
    │   ├── InMemoryRateLimiter.java         RateLimiter for RequestRateLimiter filter
    │   └── RateLimitKeyConfig.java          KeyResolver: user (sub) or client IP
    └── web/
        ├── RequestIdFilter.java             X-Request-Id
        ├── GatewayErrorHandler.java         502 / 504 / 404 / 500 as problem+json
        └── ProblemResponses.java            shared problem+json writer
src/main/resources/application.yml           routes, timeouts, default filters
```

**Also changed in this phase:** user-service and account-service now return a **relative** `Location` header. See the "forwarded headers" finding in section 6.

## 6. Important configuration and decisions

### Routes (`application.yml`)

```yaml
spring.cloud.gateway.server.webflux:      # 2025.0 prefix; "spring.cloud.gateway.*" is deprecated
  httpclient:
    connect-timeout: 2000                 # ms
    response-timeout: 5s
  default-filters:
    - name: RequestRateLimiter
      args:
        key-resolver: "#{@userOrIpKeyResolver}"   # SpEL bean reference
        rate-limiter: "#{@inMemoryRateLimiter}"
  routes:
    - id: account-service
      uri: ${ACCOUNT_SERVICE_URL:http://localhost:8082}
      predicates:
        - Path=/api/accounts/**             # ** also matches /api/accounts itself
```

| Piece | Meaning |
|---|---|
| **Route** | id + destination `uri` + predicates + filters |
| **Predicate** | When does this route apply? `Path`, `Method`, `Header`, `Host`, `Query`, `Weight` (canary), … |
| **Filter** | Changes request/response: `RequestRateLimiter`, `StripPrefix`, `RewritePath`, `AddRequestHeader`, `Retry`, `CircuitBreaker` (Phase 12), … |
| `uri` from env var | Same jar locally (`localhost:8082`), in Compose (`http://account-service:8082`), in Kubernetes (`http://account-service`) |

### Security at the edge: why validate here **and** in services

| Gateway | Services |
|---|---|
| Rejects junk early (cheap), one consistent 401 format | Zero trust: protect themselves even if reached directly |
| Coarse rules: authenticated? admin-only actuator? allow-list | Fine-grained rules: roles + **ownership** (Phase 8) |

**Allow-list:** `anyExchange().denyAll()`. A new route isn't public until someone adds it under `/api/**`, which is safer than forgetting to protect it.

### CORS

| Setting | Value | Why |
|---|---|---|
| `allowedOrigins` | `http://localhost:4200` (env `CORS_ALLOWED_ORIGINS`) | Exact origins. Never `*` for an authenticated API. |
| `allowedHeaders` | `Authorization, Content-Type, Idempotency-Key, X-Request-Id` | What Angular may send |
| `exposedHeaders` | `Location, X-Request-Id, Retry-After, X-RateLimit-*` | What Angular JavaScript may **read** (others are hidden by the browser) |
| `allowCredentials` | `false` | Tokens travel in headers, not cookies |
| `maxAge` | 1 h | The browser caches the preflight answer |
| Where | **Only** the gateway | If services also added CORS headers, browsers would see duplicates and fail |

CORS is a **browser** rule protecting users, not a server firewall. curl ignores it completely.

### Rate limiting: token bucket

```
bucket capacity (burst) = 20     refill = 10 tokens/second     each request = 1 token
```
We measured this live: 30 rapid requests as alice gave **24 × 200 and 6 × 429** (20 burst + ~4 refilled during the run). A request from ada at the same moment got 200, because budgets are per user.

| Decision | Choice | Trade-off |
|---|---|---|
| Key | `user:<sub>`, fallback `ip:<addr>` | Fair per user even behind shared NAT. Unauthenticated floods get a 401 from security first (cheap) and never reach the limiter. |
| Storage | **In memory**, per gateway instance | No extra infrastructure. With N replicas the effective limit is N× the configured one. For exact global limits use `RedisRateLimiter` (a config change + a Redis server). |
| Scope | one budget across all routes | Simple. Per-route budgets are possible with route-specific filter args. |

### Timeouts and upstream errors

| Failure | Status | Example |
|---|---|---|
| Connection refused, connect timeout, DNS can't resolve host | **502 Bad Gateway** | service stopped; Kubernetes Service misspelled |
| Connected, but no response within 5 s | **504 Gateway Timeout** | slow DB query, deadlock |
| No route matches | **404** | `/api/does-not-exist` |
| Unknown error | **500**, generic message, details only in the log | |

Every gateway error body includes **`requestId`**: give that to support and they can find the matching log lines.

### Finding: forwarded headers and the `Location` header

Testing revealed that Spring Cloud Gateway **2025.0 sends no `X-Forwarded-*` headers by default**. They're only added for proxies listed in `spring.cloud.gateway.server.webflux.trusted-proxies` (a hardening against spoofed headers). The services were building `Location` from the request *they* saw, so behind the gateway a client would have been told `Location: http://localhost:8082/api/accounts/…`, an internal address that bypasses the gateway.

**Fix:** services return a **relative** `Location: /api/accounts/{id}` (allowed by RFC 9110). The client resolves it against the URL it called. Nothing needs to trust forwarded headers. Verified live: `Location: /api/accounts/d6dea544-…`.

## 7. How services communicate: service discovery

The gateway reaches services by **host name**. Which mechanism resolves that name is *service discovery*:

| Option | How it works | When appropriate |
|---|---|---|
| **Static config / env vars** (now) | `ACCOUNT_SERVICE_URL=http://localhost:8082` | Local development |
| **Docker Compose DNS** (Phase 15) | Compose service name = DNS name: `http://account-service:8082` | Single-host Compose setups |
| **Kubernetes DNS + Service** (Phase 16) | `http://account-service` → ClusterIP → kube-proxy load-balances across healthy pods (readiness probes) | **Default choice on Kubernetes**: discovery, health and load balancing come from the platform |
| **Discovery server** (Eureka, Consul) + client-side LB (`lb://account-service`) | Instances register themselves; the gateway gets the instance list and balances | VMs or bare metal **without** an orchestrator, multiple data centers, or when you need client-side LB features (zone affinity, custom weighting) |

**Our decision: no Eureka.** In Kubernetes it would duplicate what Services and DNS already provide, adding a stateful component that must be highly available, plus registration lag. The cost of our choice: load balancing is per-connection at the Service level, not per-request in the client, which is fine for this workload.

## 8. Run it

```bash
cd banking-platform
docker compose up -d                       # postgres + keycloak
mvn verify                                 # 81 tests
mvn -pl user-service spring-boot:run       # terminal 1  :8081
mvn -pl account-service spring-boot:run    # terminal 2  :8082
mvn -pl api-gateway spring-boot:run        # terminal 3  :8080

TOKEN=$(./infrastructure/keycloak/pkce-login.sh alice 2>/dev/null)
curl -s -i localhost:8080/api/whoami -H "Authorization: Bearer $TOKEN"
```

From now on, **clients use port 8080 only**. The service ports stay open locally for debugging; in Kubernetes (Phase 16) services won't be exposed externally at all, and NetworkPolicies can restrict who may call them.

## 9. Real test results (live, through :8080)

```text
# Create an account (relative Location)
HTTP/1.1 201 Created
Location: /api/accounts/d6dea544-8653-44ec-9c1a-bc21a2e7ae55

# No token: stopped at the gateway (the service never sees it)
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
X-Request-Id: 9f64a45e-...
{"title":"Unauthorized","status":401,"detail":"An access token is required","instance":"/api/accounts","requestId":"9f64a45e-..."}

# Rate limit (30 rapid requests as alice): 24 × 200, 6 × 429
HTTP/1.1 429 Too Many Requests
X-RateLimit-Remaining: 0
X-RateLimit-Burst-Capacity: 20
X-RateLimit-Replenish-Rate: 10
Retry-After: 1

# CORS preflight from http://localhost:4200
HTTP/1.1 200 OK
Access-Control-Allow-Origin: http://localhost:4200
Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS
Access-Control-Allow-Headers: authorization, content-type
Access-Control-Max-Age: 3600
# ... from https://evil.example → HTTP/1.1 403 Forbidden

# Routes (ada/ADMIN → 200; alice → 403)
user-service    -> http://localhost:8081 | Paths: [/api/customers/**, /api/whoami]
account-service -> http://localhost:8082 | Paths: [/api/accounts/**]

# account-service stopped
HTTP/1.1 502 Bad Gateway
{"title":"Bad gateway","status":502,"detail":"The downstream service is unavailable. Please retry later.","requestId":"8f1a2285-..."}
gateway log: WARN GatewayErrorHandler : Downstream unreachable for /api/accounts: ConnectException: Connection refused
# ... and /api/whoami (user-service) still 200: one failing service doesn't take down the others
```

Automated tests (`GatewayIntegrationTest`) run the full gateway against a real HTTP stub backend on a random port: 401/503, routing and token relay, request ID, 404, deny-list, 504 (slow stub + 1 s timeout), 502 (closed port), 429, CORS allowed and denied, admin-only actuator. `GatewayErrorHandlerTest` covers DNS failure and the other mappings deterministically.

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| App fails: *Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway* | `spring-boot-starter-web` was added to the gateway. Remove it. |
| Routes ignored / `404` for everything | Old property prefix `spring.cloud.gateway.routes`. In 2025.0 use `spring.cloud.gateway.server.webflux.routes`. Check `/actuator/gateway/routes`. |
| Browser: *No 'Access-Control-Allow-Origin' header* | Origin not in `CORS_ALLOWED_ORIGINS` (scheme + host + port must match exactly), or the request hit a service directly instead of the gateway. |
| Browser: duplicate `Access-Control-Allow-Origin` values | CORS configured in the gateway **and** a service. Keep it only in the gateway. |
| `429` during normal use | Burst too small for the page (Angular may load several resources at once). Tune `burst-capacity`. |
| `502` | Service down, wrong `*_SERVICE_URL`, or DNS name doesn't exist. Gateway log: `Downstream unreachable`. |
| `504` | Service too slow (> 5 s). Find the slow endpoint; don't just raise the timeout. |
| `Location` points to `:8082` | Service builds absolute URLs from its own request. Use relative URIs (as here), or configure `trusted-proxies` + `server.forward-headers-strategy=framework`. |
| `401` with token in the test harness: *Bearer token is malformed* | Token contains characters outside RFC 6750 `b64token` (`:` etc.). |
| Blocking call inside a gateway filter freezes it | Never call JDBC / `RestTemplate` / `Thread.sleep` in WebFlux code. It blocks the few Netty event-loop threads. |

## 11. Known gaps (next phases)

- **No business authorization yet:** any authenticated user can call every route. → Phase 8.
- **No retries or circuit breaker** for flaky services. → Phase 12 (Resilience4j: `CircuitBreaker` + `Retry` filters).
- **The `429` body is empty** (headers only), set by the built-in filter.
- **Tracing** beyond `X-Request-Id` → Phase 14 (OpenTelemetry, `traceparent`).

## 12. Interview questions

1. What is an API Gateway? What should and shouldn't it do?
2. Why is Spring Cloud Gateway reactive? What happens if you block inside a gateway filter?
3. Explain routes, predicates, and filters with an example.
4. If the gateway validates JWTs, why do services validate again? Which authorization belongs where?
5. How does CORS work (simple vs preflighted requests)? Why configure it only at the gateway? Why never `*` with credentials?
6. Explain the token-bucket algorithm. Token bucket vs fixed window vs sliding window?
7. In-memory vs Redis-backed rate limiting across multiple gateway replicas: trade-offs?
8. Which key would you rate-limit on, and what goes wrong with IP-based limits?
9. 502 vs 503 vs 504: what does each mean at a gateway?
10. Why should the `Location` header be relative (or forwarded headers be configured) behind a proxy? What is the risk of trusting `X-Forwarded-*` blindly?
11. Service discovery: Kubernetes DNS vs Eureka. When would you choose each?
12. How does a correlation ID help in a microservices system? Why validate a client-provided one?
13. API Gateway vs BFF: when would you add a BFF?
