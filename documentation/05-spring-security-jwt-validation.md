# Phase 5 — Spring Security and JWT Validation

## 1. Concept: every service is a Resource Server

In OAuth 2.0 terms:

| Role | In our system |
|---|---|
| Resource owner | the customer (alice) |
| Client | Angular SPA, or payment-service acting as itself |
| Authorization server | Keycloak |
| **Resource server** | **user-service, account-service** (and later every other API) |

A resource server **doesn't log anyone in**. It receives `Authorization: Bearer <jwt>` and decides whether to trust it. For a JWT that decision is **local and offline**: verify the signature with Keycloak's public key and check the claims. There's no call to Keycloak per request.

### Why each service validates (and not only the gateway)

**Zero trust / defense in depth.** The gateway (Phase 6) also checks tokens, but if anything reaches a service another way (a misconfigured route, a compromised pod in the cluster, a developer's port-forward), the service must still protect itself. Each service also needs the token's *contents* (`sub`, roles) for its own authorization decisions (Phase 8).

### Local validation (JWT) vs introspection

| | Local JWT validation (we use this) | Token introspection (RFC 7662) |
|---|---|---|
| How | Verify signature with cached public key + check claims | POST each token to Keycloak `/introspect` |
| Latency | microseconds | one network call per request (cacheable) |
| Keycloak down | keeps working with cached keys | every request fails |
| Revocation | not seen until `exp` (5 min) | seen immediately |
| Fits | high-volume APIs with short-lived tokens | opaque tokens, high-risk operations |

## 2. Why we need it

Until now anyone could call `POST /api/accounts/{id}/withdraw`. Now:

- no token, a forged token, a tampered token, an expired token, or a token from another realm or for another API → **401**
- a valid token → the request runs, and the code knows **who** is calling (`sub`) and **what roles** they have.

## 3. Architecture

```
            Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IlQ5b0Jq...
                                    │
 ┌──────────────────────────────────▼───────────────────── user-service / account-service ──┐
 │  Spring Security filter chain                                                           │
 │   BearerTokenAuthenticationFilter ── extracts token                                    │
 │        │                                                                                │
 │        ▼                                                                                │
 │   JwtAuthenticationProvider                                                             │
 │        │  NimbusJwtDecoder:                                                             │
 │        │    1. parse header → kid=T9oBj...                                              │
 │        │    2. public key for kid ◄──── JWKS cache ◄─(first use / unknown kid)── Keycloak /certs
 │        │    3. verify RS256 signature                                                   │
 │        │    4. validators: exp/nbf (±60 s), iss == banking realm, aud ∋ banking-api      │
 │        ▼                                                                                │
 │   JwtAuthenticationConverter + KeycloakRealmRoleConverter                               │
 │        │  principal = sub, authorities = SCOPE_* + ROLE_* (from realm_access.roles)     │
 │        ▼                                                                                │
 │   SecurityContext ──► authorizeHttpRequests ──► Controller (@AuthenticationPrincipal Jwt)
 │                                                                                         │
 │   failures: bad token → 401 (entry point) · can't check → 503 · no permission → 403     │
 └─────────────────────────────────────────────────────────────────────────────────────────┘
```

## 4. Request flow: `GET /api/whoami` with alice's token

1. `BearerTokenAuthenticationFilter` finds the `Authorization: Bearer …` header.
2. `NimbusJwtDecoder` splits the JWT, reads `kid` from the header, and takes the matching RSA public key from its cache. On the first request it downloads `/certs` from Keycloak. On an unknown `kid` (key rotation) it downloads again.
3. It verifies the RS256 signature. A single changed character in the payload → `Signed JWT rejected: Invalid signature`.
4. Validators run: `exp`/`nbf` with 60 s clock skew, `iss` equals `http://localhost:8180/realms/banking`, `aud` contains `banking-api`.
5. `KeycloakRealmRoleConverter` turns `realm_access.roles: [CUSTOMER, …]` into `ROLE_CUSTOMER`, … and `scope: "openid email"` into `SCOPE_openid`, ….
6. The `JwtAuthenticationToken` goes into the `SecurityContext`. The principal name is `sub`.
7. `anyRequest().authenticated()` passes, and the controller receives the verified `Jwt`.

Real response:

```json
{
  "subject": "7f3c9a2e-1b4d-4e8a-9c6f-2d5b8e1a0f3c",
  "username": "alice",
  "clientId": "banking-frontend",
  "audience": ["banking-api", "account"],
  "authorities": ["ROLE_CUSTOMER", "ROLE_default-roles-banking", "ROLE_offline_access",
                  "ROLE_uma_authorization", "SCOPE_email", "SCOPE_openid", "SCOPE_profile"],
  "issuedAt": "2026-11-19T07:30:04Z",
  "expiresAt": "2026-11-19T07:35:04Z"
}
```

## 5. Project structure (per service)

```
src/main/java/com/banking/<service>/security/
├── SecurityConfig.java                          filter chain, JwtDecoder, validators, converter
├── JwtProperties.java                           banking.security.jwt.* (issuer, JWKS URL, audience)
├── KeycloakRealmRoleConverter.java              realm_access.roles → ROLE_*
├── ProblemDetailsAuthenticationEntryPoint.java  401 as problem+json (+ WWW-Authenticate)
├── ProblemDetailsAccessDeniedHandler.java       403 as problem+json
└── TokenValidationFailureHandler.java           bad token → 401, IdP unreachable → 503
user-service only: controller/WhoAmIController.java, dto/WhoAmIResponse.java
src/test/java/.../security/
├── TokenValidatorTest.java                      iss / aud / exp rules
├── KeycloakRealmRoleConverterTest.java          role mapping
├── SecurityIntegrationTest.java                 401/403/health through the real filter chain
└── IdentityProviderUnavailableTest.java         503 vs 401
```

The security classes are **copied** into each service, not shared through a library. That keeps services independent at the cost of duplication. Many companies later extract a small internal "platform starter" for this kind of *technical* code (never for *domain* code). With six services, that trade-off is worth revisiting.

## 6. Important code and configuration

### `application.yml`

```yaml
banking:
  security:
    jwt:
      issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/banking}
      jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:8180/realms/banking/protocol/openid-connect/certs}
      audience: banking-api
management.endpoint.health:
  show-details: when-authorized
  roles: ADMIN
```

**Why separate `issuer-uri` and `jwk-set-uri`?** In Docker (Phase 15) a service reaches Keycloak at `http://keycloak:8180/...`, but tokens still say `iss=http://localhost:8180/...` (fixed by `KC_HOSTNAME`). Keys are fetched from the internal URL, and the issuer is compared to the public one.

### `SecurityConfig` highlights

| Code | Why |
|---|---|
| `csrf.disable()` | CSRF abuses **cookies** that browsers send automatically. Bearer tokens in a header aren't sent automatically, so there's nothing to forge. (Keep CSRF on for cookie-based sessions.) |
| `SessionCreationPolicy.STATELESS` | No `JSESSIONID`. Every request is authenticated by its token. Any instance can serve any request (horizontal scaling). |
| `permitAll` for `/actuator/health/**` | Docker/Kubernetes probes have no token. Details stay hidden unless the caller is ADMIN. |
| `NimbusJwtDecoder.withJwkSetUri(...)` | Lazy: the service starts even if Keycloak is down, and keys are fetched on first use. |
| `JwtValidators.createDefaultWithIssuer` | `exp`, `nbf` (60 s skew), `iss`. |
| `JwtClaimValidator(aud ∋ banking-api)` | Stops a token minted for another API (another audience) being replayed here. |
| `setPrincipalClaimName("sub")` | `authentication.getName()` = Keycloak user ID = our `ownerId`. |
| `withObjectPostProcessor(...)` | Customizes the `BearerTokenAuthenticationFilter`, which the DSL doesn't expose, to set our failure handler. |
| `@AuthenticationPrincipal Jwt jwt` | Injects the **validated** token into a controller method. |

### 401 vs 403 vs 503

| Status | Meaning | Client should |
|---|---|---|
| **401** + `WWW-Authenticate: Bearer error="invalid_token"` | We don't know who you are (no/invalid/expired token) | refresh the token or log in again |
| **403** | We know who you are, but you may not do this | show "not allowed"; re-login won't help |
| **503** + `Retry-After: 10` | We couldn't check your token (IdP unreachable, no cached keys) | retry later; **don't** log the user out |

The 503 case came from testing: Spring's default turned an unreachable JWKS endpoint into a **401**. That would send every user to a login page that can't work either, and make an infrastructure outage look like "auth failures" on dashboards.

## 7. How services communicate (now)

Nothing new between services yet. What changed is the **contract**: every call must carry a Keycloak access token. For Phase 9, that means payment-service must **send a token** when it calls account-service. Phase 10 decides *which* token: the user's (relayed) or payment-service's own (Client Credentials). The `payment-service` token already passes validation today (see below), because it has the right `iss` and `aud`. It just has no business roles yet.

## 8. Run it

```bash
cd banking-platform
docker compose up -d                              # postgres + keycloak
mvn verify                                        # 59 tests
mvn -pl user-service spring-boot:run              # terminal 1
mvn -pl account-service spring-boot:run           # terminal 2

TOKEN=$(./infrastructure/keycloak/pkce-login.sh alice 2>/dev/null)
curl -s localhost:8081/api/whoami -H "Authorization: Bearer $TOKEN"
```

Request file: [http/phase-05.http](http/phase-05.http).

## 9. Real test results (curl against running services)

```text
# No token
HTTP/1.1 401
WWW-Authenticate: Bearer
{"title":"Unauthorized","status":401,"detail":"An access token is required","instance":"/api/accounts"}

# Alice's real token
GET /api/accounts?ownerId=7f3c...  → 200

# Tampered: payload changed (sub → bob), original signature kept
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting
  to decode the Jwt: Signed JWT rejected: Invalid signature"

# Token from the Keycloak *master* realm (a real, validly signed Keycloak token!)
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="... Signed JWT rejected:
  Another algorithm expected, or no matching key(s) found"
  ← each realm has its own keys, so it fails at the signature step. If the keys matched,
    the iss validator would reject it next (see TokenValidatorTest).

# payment-service Client Credentials token → 200 from /api/whoami
  "username":"service-account-payment-service", "clientId":"payment-service",
  "authorities":["ROLE_default-roles-banking", ..., "SCOPE_email","SCOPE_profile"]   ← no CUSTOMER/ADMIN

# Health
anonymous           → {"status":"UP"}
ada (ADMIN)         → UP with components [db, diskSpace, ping, ssl]
alice (CUSTOMER)    → {"status":"UP"}

# Keycloak stopped AFTER the service had cached the keys
alice token → 200          ← validation is local

# Keycloak stopped, service restarted (no cached keys)
HTTP/1.1 503
Retry-After: 10
{"title":"Service unavailable","detail":"Token validation is temporarily unavailable. Please retry shortly."}
log: WARN TokenValidationFailureHandler : Cannot validate access tokens, identity provider unavailable: ...

# Keycloak back
alice token → 200
```

### How the tests simulate tokens

- **`jwt()` post-processor** (spring-security-test) puts a ready-made `JwtAuthenticationToken` into the request, **skipping the decoder**. Used for business tests ("given an authenticated CUSTOMER…").
- **`TokenValidatorTest`** builds `Jwt` objects and runs our **real validator** against wrong `iss`, wrong `aud`, expired, and within-skew tokens.
- **`@MockitoBean JwtDecoder`** simulates Nimbus failures to test 401 vs 503.
- Signature verification itself is Nimbus' job and was verified live (tampered token above).

## 10. Common errors and troubleshooting

| Symptom (`WWW-Authenticate` / log) | Cause / fix |
|---|---|
| `401`, header just `Bearer` | No `Authorization` header, or not in the form `Bearer <token>` |
| `Jwt expired at …` | Tokens live 5 min. Get a new one (`pkce-login.sh`) or use the refresh token. |
| `The iss claim is not valid` | Token obtained via a different Keycloak URL (e.g. `127.0.0.1` instead of `localhost`). Must exactly match `issuer-uri`. |
| `The aud claim is not valid` | The client has no `banking-api` audience mapper (Phase 4 realm). |
| `Invalid signature` | Token modified, or signed by another Keycloak (old container, other realm). |
| `no matching key(s) found` | Token from another realm/instance. If you **recreated** Keycloak, its keys changed: get a new token. |
| `503 Token validation is temporarily unavailable` | Service can't reach `jwk-set-uri`. Is Keycloak up (`docker compose ps`)? Is the URL right for where the service runs? |
| Every test returns 401 after adding security | MockMvc built without `.apply(springSecurity())` or requests lack `.with(jwt())`. |
| `403` with a valid token | Expected from Phase 8 when the role is missing. Check `/api/whoami` authorities. |
| Health shows no details | By design. Use an ADMIN token. |

## 11. Known gaps (next phases)

- **No authorization yet:** any valid token (even payment-service's) can call every endpoint. → Phase 8 (roles + ownership).
- **The client still sends `ownerId`.** → Phase 8 takes it from `sub`.
- **No CORS on services.** Intentional: browsers will only talk to the gateway (Phase 6).
- **A revoked or logged-out user's access token works until `exp`** (≤ 5 min). That's the accepted trade-off of local validation.

## 12. Interview questions

1. What is a resource server? How does it differ from the authorization server and the client?
2. Walk through what Spring Security does with a bearer token, step by step.
3. How does a service verify a JWT signature without calling Keycloak? What happens during key rotation?
4. Which claims must you validate and why (`exp`, `iss`, `aud`, signature algorithm)?
5. Local JWT validation vs token introspection: trade-offs?
6. Why is CSRF protection disabled for this API? When must it stay on?
7. 401 vs 403 vs 503: which do you return when, and why does it matter for clients and monitoring?
8. How do you map Keycloak realm roles to Spring Security authorities? Why the `ROLE_` prefix?
9. If the gateway validates tokens, why validate again in each service?
10. A user logs out but their access token keeps working for a few minutes. Why, and how would you reduce that window?
11. How do you test secured endpoints without a running Keycloak?
12. Why might the JWKS URL differ from the issuer URL in a containerized deployment?
