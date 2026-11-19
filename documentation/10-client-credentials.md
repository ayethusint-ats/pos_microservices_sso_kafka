# Phase 10: Client Credentials, a Service's Own Identity

## 1. Concepts

In Phase 9, payment-service could only act **as the customer** (token relay). Alice's token can't touch Bob's account, so Alice couldn't pay Bob. Phase 10 gives payment-service **its own identity**.

| Concept | Meaning |
|---|---|
| **Client Credentials grant** (RFC 6749 §4.4) | An application authenticates to Keycloak with **its own** client ID + secret and gets an access token. No user, no browser, no refresh token. |
| **Confidential client** | A client that can keep a secret (a server). `payment-service` is one; the Angular app (public client) is not. |
| **Service account** | The Keycloak "user" behind a client's own identity: `service-account-payment-service`, `sub = 2b5bcd1a-…`. |
| **Client role** | A role that belongs to one Keycloak client. Here: `account-service` defines **`internal-transfers`**: a permission *of account-service* that it can grant to callers. |
| **`azp`** (authorized party) | The client ID the token was issued to: `banking-frontend` for Alice's token, `payment-service` for the service token. |
| **OAuth2 Client support in Spring** | `OAuth2AuthorizedClientManager` fetches, **caches** and renews tokens; `OAuth2ClientHttpRequestInterceptor` puts them on `RestClient` requests. |

### User token vs service token (real tokens, decoded)

| Claim | Alice (Authorization Code + PKCE) | payment-service (Client Credentials) |
|---|---|---|
| `sub` | `7f3c9a2e-…` (alice) | `2b5bcd1a-…` (service-account-payment-service) |
| `azp` | `banking-frontend` | `payment-service` |
| `aud` | `banking-api`, `account` | `banking-api`, `account-service` |
| `realm_access.roles` | `CUSTOMER`, … | *(none)* |
| `resource_access` | `account: manage-account …` | **`account-service: [internal-transfers]`** |
| refresh token | yes (rotated) | **no**: just ask again with the secret |

## 2. Why each design decision

| Decision | Why |
|---|---|
| **Two identities in one transfer**: customer's token for the **debit**, service token for the **credit** | Taking money *out* requires the owner's consent, so it uses Alice's token and account-service checks ownership. Putting money *in* to any account is a bank operation, so it uses payment-service's permission. A compromised payment-service still **can't empty accounts** on its own. |
| **A separate, narrow internal API** (`/internal/accounts`: find by number, credit) instead of giving the service a powerful role on `/api/**` | Least privilege. The service gets exactly two operations, and no withdraw, list, balance or owner data. |
| **No owner, no balance in the recipient lookup** (`AccountSummary`) | Data minimization: payment-service needs to know "exists, currency, can receive", not who owns it or how much is in it. |
| **Client role on an `account-service` client**, not a realm role and not a client scope | Realm roles describe *people* (CUSTOMER/SUPPORT/ADMIN). The permission is defined *by account-service* and granted to a caller: that's what client roles are. (A custom client scope was tried: listing `clientScopes` in the realm import makes Keycloak skip its built-in scopes, including `basic`, which provides `sub`.) |
| **Authority `PERMISSION_internal-transfers`**, mapped only from `resource_access.account-service` | Client roles of other clients (Keycloak's `account`) mean nothing here and are ignored. A distinct prefix avoids confusion with `ROLE_*`. |
| **Service account has no realm roles** (not even `default-roles-banking`) | Least privilege: no `offline_access`, no `manage-account`. |
| **Recipient addressed by account number** | What a customer actually knows about someone else's account. The UUID stays internal. |
| **Cached service token, single cache key** (`SERVICE_PRINCIPAL`) | One token for the whole application, reused until shortly before expiry. Without the fixed principal, Spring would cache per *user* and fetch a new service token for every customer. |
| **Evict the cached token on 401** | A revoked or rotated token would otherwise fail every call until it expires. |
| **`token-uri` only, no `issuer-uri`** | No OIDC discovery at startup: payment-service starts even if Keycloak is down. |
| **`/internal/**` is not routed by the gateway** | Defense in depth: unreachable from the internet even with a stolen service token. A NetworkPolicy adds a network-level guard in Phase 16. |

## 3. Architecture

```
                       Keycloak (realm banking)
                ┌──────────────────────────────────────────────┐
                │ client banking-frontend (public, PKCE)       │
                │ client payment-service (confidential)        │
                │   └ service account ── has ──┐               │
                │ client account-service (bearer-only)         │
                │   └ client role internal-transfers ◄──┘      │
                └──────────▲──────────────────▲────────────────┘
          login (PKCE)     │                  │ POST /token  grant_type=client_credentials
                           │                  │ Authorization: Basic payment-service:<secret>
  Browser ─ Bearer(alice) ─► gateway ─► payment-service ──────────────────────────────┐
                                          │  Bearer(alice)                Bearer(svc) │
                                          ▼                                   ▼       │
                              account-service                      account-service    │
                              /api/accounts/{id}        /internal/accounts/by-number/{n}
                              /api/accounts/{id}/withdraw  /internal/accounts/{id}/credit
                              owner == sub ?            PERMISSION_internal-transfers ?
```

## 4. Request flow: Alice pays Bob 120 THB

```
Alice ─POST /api/payments {sourceAccountId, targetAccountNumber: "7742470356", amount: 120}─► gateway ─► payment-service
 1. INSERT payment PENDING
 2. GET  /api/accounts/{source}                 Bearer alice   → hers? (else SOURCE_ACCOUNT_NOT_FOUND)
 3. (first time only) POST keycloak/token  client_credentials → service token, cached ~5 min
 4. GET  /internal/accounts/by-number/7742…     Bearer svc     → {id, number, currency, status}
 5. same account? same currency? recipient ACTIVE?
 6. POST /api/accounts/{source}/withdraw        Bearer alice   → her consent; INSUFFICIENT_FUNDS?
 7. UPDATE payment DEBITED
 8. POST /internal/accounts/{bob}/credit        Bearer svc     {amount, paymentId}
    (refused → POST /internal/accounts/{source}/credit = refund, also with the service token)
 9. UPDATE payment COMPLETED
◄─ 201 Created
```

account-service audit log for exactly this transfer:

```
action=WITHDRAW actor=7f3c9a2e-… account=07b53bf3-… amount=120.00
action=CREDIT   actor=client:payment-service account=92a6c3a8-… amount=120.00 payment=2a6dd696-…
```

## 5. Project structure (changes)

```
infrastructure/keycloak/realm/banking-realm.json
    + client "account-service" (bearer-only) with client role "internal-transfers"
    + service-account-payment-service: fixed id, clientRoles {account-service: [internal-transfers]}, no realm roles

account-service/
    security/KeycloakRealmRoleConverter.java   + resource_access.account-service.roles → PERMISSION_*
    security/SecurityConfig.java               + /internal/** requires PERMISSION_internal-transfers
    security/Authorization.java                + @InternalTransfers, @CallingClient (azp)
    controller/InternalAccountController.java  NEW  GET by-number, POST credit
    dto/AccountSummary.java, CreditRequest.java NEW
    service/AccountService.java                + findRecipient, creditForPayment (audit with client + payment ID)
    repository/AccountRepository.java          + findByAccountNumber

payment-service/
    pom.xml                                    + spring-boot-starter-oauth2-client
    application.yml                            + spring.security.oauth2.client.registration.account-service
    client/InternalAccountApi.java             NEW  @HttpExchange("/internal/accounts")
    client/AccountClientConfig.java            two RestClients: relay interceptor vs OAuth2 interceptor
    client/AccountServiceClient.java           getOwnAccount/debit (customer) · findRecipient/credit (service)
    service/PaymentService.java                recipient by number, SAME_ACCOUNT, refund via service token
    entity/Payment.java                        + targetAccountNumber, accountsResolved(...)
    db/migration/V2__address_recipient_by_account_number.sql

frontend/src/app/pages/payments/               "To account number" (with own accounts suggested)
frontend/e2e/payments.e2e.ts                   + Alice pays Bob, in two browser sessions
```

## 6. Important code

### payment-service: the client registration

```yaml
spring.security.oauth2.client:
  registration:
    account-service:
      provider: keycloak
      client-id: payment-service
      client-secret: ${PAYMENT_SERVICE_CLIENT_SECRET}    # .env locally; a Secret in Kubernetes
      authorization-grant-type: client_credentials
      client-authentication-method: client_secret_basic # secret in the Authorization header
  provider:
    keycloak:
      token-uri: ${KEYCLOAK_TOKEN_URI:http://localhost:8180/realms/banking/protocol/openid-connect/token}
```

### payment-service: the token-carrying client

```java
@Bean
OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
                                                      OAuth2AuthorizedClientService authorizedClientService) {
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClientService);
    manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    return manager;
}

OAuth2ClientHttpRequestInterceptor serviceToken = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
serviceToken.setClientRegistrationIdResolver(request -> "account-service");
serviceToken.setPrincipalResolver(request -> SERVICE_PRINCIPAL);   // one token for the app, not per user
serviceToken.setAuthorizationFailureHandler(
        OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClientService)); // evict on 401
```

| Class | Role |
|---|---|
| `AuthorizedClientServiceOAuth2AuthorizedClientManager` | The manager for code outside a browser session (the default one stores tokens in the HTTP session) |
| `OAuth2AuthorizedClientService` (in-memory) | The token cache |
| `ClientCredentialsOAuth2AuthorizedClientProvider` | Requests a new token when none is cached or it expires within 60 s |
| `OAuth2ClientHttpRequestInterceptor` | Spring Security 6.4+: adds `Authorization: Bearer <token>` to `RestClient` calls |

### account-service: permission from a client role

```java
Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
if (resourceAccess != null && resourceAccess.get(resourceClientId) instanceof Map<?, ?> ownClient) {
    addRoles(ownClient.get("roles"), "PERMISSION_", authorities);   // → PERMISSION_internal-transfers
}
```

```java
.requestMatchers("/internal/**").hasAuthority("PERMISSION_internal-transfers")   // URL rule
...
@RestController @RequestMapping("/internal/accounts") @InternalTransfers         // method rule
public class InternalAccountController {
    @PostMapping("/{id}/credit")
    public AccountSummary credit(@CallingClient String clientId, @PathVariable UUID id,
                                 @Valid @RequestBody CreditRequest request) { ... }
}
```

`@CallingClient` = `@AuthenticationPrincipal(expression = "getClaimAsString('azp')")`: for machine calls, the useful audit identity is the **application**, not a service-account UUID.

### Failure classification additions

| Situation | Result |
|---|---|
| Keycloak refuses the client (wrong secret, disabled) or is unreachable *and no token is cached* | `OAuth2AuthorizationException` before any request is sent → **not executed** → 503 FAILED |
| account-service answers 401 to the service token | not executed; the cached token is **evicted**, the next call fetches a new one |
| Keycloak down *but token cached* | Calls keep working until the token expires (≤ 5 min) |

## 7. How services communicate

| Call | Identity | Checked by account-service |
|---|---|---|
| payment → `GET /api/accounts/{id}` | **Alice** (relay) | owner == `sub` |
| payment → `POST /api/accounts/{id}/withdraw` | **Alice** (relay) | owner == `sub`, balance, status |
| payment → `GET /internal/accounts/by-number/{n}` | **payment-service** | `PERMISSION_internal-transfers` |
| payment → `POST /internal/accounts/{id}/credit` | **payment-service** | `PERMISSION_internal-transfers`, status |
| payment → Keycloak `/token` | client ID + secret (HTTP Basic) | Keycloak |

When would *everything* use the service token? When there is no user request: Kafka consumers, schedulers, retries in a saga (Phases 11–13). Then the user's intent must travel as **data** (who requested what, signed into the payment record), and the trust boundary moves to payment-service (the "trusted subsystem" model). Alternatives: **token exchange** (RFC 8693) to get a downscoped token "payment-service on behalf of Alice".

## 8. Run it

```bash
docker compose up -d
# Realm changed: Keycloak imports only when the realm doesn't exist yet. Re-import:
#   delete realm "banking" in the admin console (or via the admin API), then
docker compose restart keycloak
mvn verify                                   # 169 backend tests
mvn -pl user-service spring-boot:run         # :8081
mvn -pl account-service spring-boot:run      # :8082
mvn -pl payment-service spring-boot:run      # :8083
mvn -pl api-gateway spring-boot:run          # :8080
cd frontend && npm start                     # :4200
export DEMO_USER_PASSWORD=$(grep -E '^DEMO_USER_PASSWORD=' ../.env | cut -d= -f2-)
npm run e2e                                  # 9 e2e tests
```

Browser: **bob** opens an account and copies its number. **alice** (private window): *Transfers* → paste Bob's number → send. Bob reloads *Accounts*: the money is there.

Get a service token yourself:

```bash
set -a; . ./.env; set +a
curl -s -u "payment-service:$PAYMENT_SERVICE_CLIENT_SECRET" -d grant_type=client_credentials \
  http://localhost:8180/realms/banking/protocol/openid-connect/token | jq -r .access_token | cut -d. -f2 | base64 -d 2>/dev/null | jq
```

Request file: [http/phase-10.http](http/phase-10.http).

## 9. Real test results

### Live (real Keycloak tokens)

| Scenario | Result |
|---|---|
| Alice → Bob's account number, 120 THB | **201 COMPLETED**; Alice 300 → 180, Bob 0 → 120 |
| Alice → unknown number `0000000000` | **422** `TARGET_ACCOUNT_NOT_FOUND` |
| Bob → pays *from* Alice's account to himself | **422** `SOURCE_ACCOUNT_NOT_FOUND` (debit still needs the owner's token) |
| Alice's token → account-service `/internal/…/credit` | **403** |
| Any token → gateway `/internal/**` | **403** (never routed) |
| Service token → `/api/accounts`, withdraw, `/api/admin/accounts` | **403**, **403**, **403** |
| Service token → `POST /api/payments` via gateway | **403** (not a customer) |
| Service token → `/internal/accounts/by-number/{n}` | **200** `{id, accountNumber, currency, status}` (no owner, no balance) |
| payment-service started with a **wrong client secret** | **503** FAILED, nothing moved; log: `401 Unauthorized` from Keycloak's token endpoint |
| **Keycloak stopped**, Alice transfers | **201 COMPLETED**: gateway and services have Keycloak's public keys cached (JWKS), and payment-service has its service token cached |

### Automated

| Suite | Count | Highlights |
|---|---|---|
| account-service | 51 | internal API: only `PERMISSION_internal-transfers`; customers, SUPPORT, ADMIN, no-role → 403; service token → 403 on `/api/**`; frozen → 422; minimal lookup data; converter ignores other clients' roles |
| payment-service | 63 | per-call identity (alice/service/alice/service); token request is `client_credentials` with HTTP Basic; **one** token across customers and payments; refused client → not executed; 401 → token evicted and refetched; Keycloak down with cached token → still works; stub enforces ownership like the real service |
| user-service | 31 | unchanged |
| api-gateway | 24 | unchanged |
| Angular unit | 14 | |
| Playwright e2e | 9 | + Alice pays Bob by account number in two browser sessions; Bob's balance rises; unknown number → `TARGET_ACCOUNT_NOT_FOUND` |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| 503 `no service token (invalid_token_response)`; Keycloak log `invalid_client_credentials` | Wrong `PAYMENT_SERVICE_CLIENT_SECRET`, or the realm wasn't re-imported after the secret changed. |
| Service token has no `resource_access.account-service` | Realm not re-imported (`--import-realm` skips existing realms), or the role isn't assigned to `service-account-payment-service`. |
| 403 on `/internal/**` with a valid service token | Converter not mapping `resource_access.<client>.roles`, or the client ID in the converter ≠ Keycloak client ID. Check `/api/whoami` authorities with that token. |
| Tokens have no `sub` after editing the realm | You added `clientScopes` to the import: Keycloak then skips its built-in scopes (`basic` provides `sub`). |
| A token request for every customer | `PrincipalResolver` not fixed: tokens are cached per current user. |
| Service keeps failing with 401 after a key rotation | No authorization failure handler: the bad token stays cached until expiry. |
| payment-service doesn't start while Keycloak is down | `issuer-uri` on the provider triggers discovery at startup. Use `token-uri`. |
| `OAuth2AuthorizedClientManager` complains about missing request context | You used `DefaultOAuth2AuthorizedClientManager` (needs `HttpServletRequest`). Use `AuthorizedClientService…Manager` for service-to-service. |
| Secret in a stack trace or log | Spring never logs it. Don't log `ClientRegistration` objects or request bodies to the token endpoint. |

## 11. Production notes

- **Stronger client authentication than a shared secret:** `private_key_jwt` (signed JWT assertion) or mTLS-bound tokens (RFC 8705), so a leaked config file isn't enough to impersonate the service.
- **Rotate the client secret** with an overlap window (Keycloak supports a secondary secret during rotation).
- **Workload identity** instead of static secrets in Kubernetes: SPIFFE/SPIRE, or the cloud provider's workload identity federated into Keycloak.
- **Token exchange (RFC 8693)** to call downstream "on behalf of" the user with a narrowed token, if both identities must be in one token.
- **Short service-token lifetime** (5 min here) limits the damage of a stolen token; caching keeps Keycloak load negligible.
- **Network policy:** `/internal/**` reachable only from payment-service pods (Phase 16).

## 12. Interview questions

1. What is the Client Credentials grant? When is it the right choice, and when is it wrong?
2. Token relay vs Client Credentials vs token exchange: compare what each can do and what each risks.
3. Why does the debit still use the customer's token while the credit uses the service's?
4. Why is there no refresh token in a Client Credentials response?
5. How does Spring cache client tokens? What goes wrong if the cache key is the current user?
6. Realm roles vs client roles vs scopes in Keycloak: which would you use for a service permission, and why?
7. What do `sub`, `azp` and `aud` tell a resource server? Which one belongs in the audit log for a machine call?
8. How do you stop a service token from being usable on the public API?
9. Why did the recipient lookup return no owner and no balance?
10. What happens to transfers when Keycloak goes down? Why?
11. How would you rotate the client secret without downtime?
12. What is `private_key_jwt`, and why is it better than `client_secret_basic`?
13. What breaks if payment-service is compromised, in the Phase 9 design vs the Phase 10 design?
