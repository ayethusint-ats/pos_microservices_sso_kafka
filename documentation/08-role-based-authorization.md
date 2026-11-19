# Phase 8 — Role-Based Authorization

## 1. Concepts

**Authentication** (Phases 4–5) answers *who are you?* **Authorization** answers *what may you do with this specific thing?*

Two kinds of rules are needed, and confusing them is the most common security bug in APIs:

| Rule type | Question | Example | Where in code |
|---|---|---|---|
| **Role-based (RBAC)** | What *kind* of user are you? | "Only ADMIN may freeze accounts" | `@PreAuthorize` (via `@AdminOnly` …) and URL rules |
| **Ownership / object-level** | Is *this object* yours? | "Alice may withdraw only from **her** accounts" | Service layer: `findByIdAndOwnerId(id, sub)` |

Roles alone are **not enough**. Bob and Alice are both `CUSTOMER`, and a role check can't tell their accounts apart. Missing object-level checks are called **IDOR** (*Insecure Direct Object Reference*) or **BOLA** (*Broken Object Level Authorization*), and BOLA is **#1 in the OWASP API Security Top 10**.

### The authorization matrix we implemented

| Capability | CUSTOMER | SUPPORT | ADMIN |
|---|---|---|---|
| Own profile: view / create / edit (`/api/customers/me`) | ✅ | ❌ | ❌ |
| Own accounts: list, view, balance, open (`/api/accounts`) | ✅ own only | ❌ | ❌ |
| Deposit / withdraw (`/api/accounts/{id}/…`) | ✅ own only | ❌ | ❌ |
| View any customer / their accounts (`GET /api/admin/**`) | ❌ | ✅ | ✅ |
| Create / edit / delete customer profiles | ❌ | ❌ | ✅ |
| Open account for a customer, freeze / unfreeze / close | ❌ | ❌ | ✅ |
| Move money for someone else | ❌ | ❌ | ❌ |

**Segregation of duties:** ADMIN manages, but **can't move customers' money**. An admin account is a high-value target; if it's compromised, the attacker can freeze accounts but not empty them. (Transfers come in Phase 9, still CUSTOMER only.)

## 2. Why each design decision

| Decision | Why |
|---|---|
| **Identity from the token, never from the request.** `@CurrentUserId` = validated `sub`. Self-service APIs have no `ownerId` parameter or field. | The client controls URLs and bodies. Phase 7's `GET /api/accounts?ownerId=…` let anyone read anyone's accounts by changing one parameter. |
| **`/me` style URLs** for the own profile | No ID in the URL means nothing to tamper with |
| **Ownership in the repository query** (`WHERE id = ? AND owner_id = ?`) inside the **service** | Can't be forgotten by a future controller. One query, no race between "check" and "use". |
| **404, not 403, for other people's objects** | 403 would confirm "this account ID exists". 404 reveals nothing (no enumeration). |
| **403 for wrong role** | The user may know the endpoint exists. 403 tells the UI "this isn't for you". |
| **Separate URL space `/api/admin/**`** for staff | Simple, reviewable rule at the gateway and in each service. Staff tools can get extra controls later (IP allow-list, MFA step-up, separate rate limits). |
| **Meta-annotations** `@CustomerOnly`, `@StaffReadAccess`, `@AdminOnly` | A typo in `@PreAuthorize("hasRole('ADMN')")` silently denies everyone (or, with `!`, allows everyone). One definition, used everywhere. |
| **Audit log** (`AUDIT` logger: actor, action, target, reason) | Banks must answer "who froze this account, and why?" Separate logger, so it can be shipped to tamper-resistant storage (Phase 14). |

## 3. Architecture: three layers of authorization

```
 Request: PATCH /api/admin/accounts/{id}/status   Authorization: Bearer <sam's token>

 ┌─ API Gateway ─────────────────────────────────────────────────────────────────────┐
 │ Layer 0  /api/admin/** → hasAnyRole(SUPPORT, ADMIN)              sam ✅ (SUPPORT)  │
 └──────────────────────────────────────────────────────────────────────────┬────────┘
 ┌─ account-service ────────────────────────────────────────────────────────▼────────┐
 │ Layer 1  SecurityFilterChain (URL)   /api/admin/** → SUPPORT|ADMIN        ✅       │
 │ Layer 2  @AdminOnly on changeStatus() (method)  hasRole('ADMIN')         ❌ → 403 │
 │ Layer 3  Service: ownership / business rules (not reached)                        │
 └───────────────────────────────────────────────────────────────────────────────────┘

 Request: POST /api/accounts/{alice's id}/withdraw   Bearer <bob's token>
   Gateway /api/** authenticated ✅ → Filter ✅ → @CustomerOnly ✅ (bob is CUSTOMER)
   → Service: findByIdAndOwnerId(id, "b0b5…") → empty → 404
```

Layers 0–1 are coarse and cheap. Layer 2 holds the precise role rules. Layer 3 holds the rules that need **data** (who owns what, account status). Each layer would stop a whole class of mistakes in another layer.

## 4. Request flow: Bob tries to withdraw from Alice's account

1. **Gateway** validates Bob's JWT and checks `/api/**` → authenticated ✅. The request is routed with the token.
2. **account-service filter chain** validates the JWT again (Phase 5), and `/api/**` → authenticated ✅.
3. **Method security:** `AccountController` is `@CustomerOnly`. Bob has `ROLE_CUSTOMER` ✅.
4. `@CurrentUserId String userId` is resolved from the validated token: `b0b5e1c2-…`. The URL contains Alice's account ID.
5. `AccountService.withdraw(id, "b0b5…", 50)` → `findByIdAndOwnerId(id, "b0b5…")` → **empty**.
6. `ResourceNotFoundException` → **404** problem+json. Nothing is withdrawn and nothing about Alice's account is revealed.

## 5. Project structure (changes)

```
account-service/src/main/java/.../
├── security/Authorization.java          NEW  @CustomerOnly @StaffReadAccess @AdminOnly @CurrentUserId
├── security/SecurityConfig.java              @EnableMethodSecurity, /api/admin/** rule, denyAll allow-list
├── controller/AccountController.java         /api/accounts: self-service, @CustomerOnly, no ownerId
├── controller/AdminAccountController.java NEW /api/admin/accounts: list/get (staff), open/status (admin)
├── service/AccountService.java               owner operations (ownerId) vs back-office operations, AUDIT log
├── repository/AccountRepository.java         + findByIdAndOwnerId
├── entity/Account.java                       + changeStatus(): ACTIVE⇄FROZEN, →CLOSED only at zero balance
├── dto/OpenAccountRequest, AdminOpenAccountRequest, ChangeStatusRequest   (CreateAccountRequest removed)
└── exception/GlobalExceptionHandler.java     + AccessDeniedException → 403 (see section 6)

user-service/src/main/java/.../
├── security/Authorization.java          NEW
├── controller/MyProfileController.java  NEW  /api/customers/me  GET/POST/PUT
├── controller/AdminCustomerController.java NEW /api/admin/customers  list/get (staff), create/update/delete (admin)
└── service/CustomerService.java              self-service by identity vs back office by id, AUDIT log

api-gateway: routes include /api/admin/customers/** and /api/admin/accounts/**; /api/admin/** staff-only

frontend/src/app/pages/  profile (create/edit /me) · accounts (deposit/withdraw) · backoffice (staff)
```

## 6. Important code

### `@CurrentUserId`: the only source of "who am I"

```java
@Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal(expression = "subject")      // Jwt#getSubject() of the validated token
public @interface CurrentUserId { }

@PostMapping("/{id}/withdraw")
public AccountResponse withdraw(@CurrentUserId String userId, @PathVariable UUID id, @Valid @RequestBody MoneyRequest r) {
    return accountService.withdraw(id, userId, r.amount());
}
```

### Meta-annotations over raw `@PreAuthorize`

```java
@Target({METHOD, TYPE}) @Retention(RUNTIME)
@PreAuthorize("hasAnyRole('SUPPORT', 'ADMIN')")
public @interface StaffReadAccess { }
```
`@EnableMethodSecurity` makes Spring wrap the controller in a proxy that evaluates the expression **before** the method runs.

### Ownership in one query

```java
Optional<Account> findByIdAndOwnerId(UUID id, String ownerId);   // WHERE id = ? AND owner_id = ?
```

### Pitfall found and fixed: `@PreAuthorize` denial must be 403, not 500

URL rules fail **in the filter chain**, so Spring Security's `AccessDeniedHandler` answers 403. But `@PreAuthorize` fails **inside the controller call**, so the `AccessDeniedException` reaches our `@RestControllerAdvice`, whose catch-all `@ExceptionHandler(Exception.class)` would turn it into a **500**. Both services now have an explicit handler:

```java
@ExceptionHandler(AccessDeniedException.class)
public ProblemDetail handleAccessDenied(AccessDeniedException ex) { return problem(FORBIDDEN, "Forbidden", "..."); }
```
Tests assert `"$.title": "Forbidden"` for method-level denials (e.g. SUPPORT freezing an account).

### Filter-chain allow-list

```java
.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // let Spring render error responses
.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
.requestMatchers("/api/admin/**").hasAnyRole("SUPPORT", "ADMIN")
.requestMatchers("/api/**").authenticated()
.anyRequest().denyAll()                                     // unknown paths: refused even with a token
```

### Account lifecycle (entity)

```
ACTIVE ⇄ FROZEN          money can only move while ACTIVE (422 ACCOUNT_NOT_ACTIVE)
ACTIVE/FROZEN → CLOSED   only at zero balance (422 BALANCE_NOT_ZERO); CLOSED is final (422 INVALID_STATUS_TRANSITION)
```

## 7. How services communicate

Unchanged: the gateway routes `/api/admin/customers/**` to user-service and `/api/admin/accounts/**` to account-service. The back-office UI combines both: it lists customers (user-service), then loads accounts by the customer's `keycloakUserId` (account-service). That's **API composition in the client**, so neither service calls the other. Phase 9 introduces the first service-to-service call (payment → account).

One consequence worth noticing: **payment-service's Client Credentials token now gets 403** on `/api/accounts` (it has no business role). Phase 10 gives it a narrow, dedicated permission.

## 8. Run it

```bash
docker compose up -d
mvn verify                                   # 96 backend tests
mvn -pl user-service spring-boot:run         # :8081
mvn -pl account-service spring-boot:run      # :8082
mvn -pl api-gateway spring-boot:run          # :8080
cd frontend && npm start                     # :4200
npm test                                     # 14 unit tests
export DEMO_USER_PASSWORD=$(grep -E '^DEMO_USER_PASSWORD=' ../.env | cut -d= -f2-)
npm run e2e                                  # 7 e2e tests
```

Try it in the browser:
1. **alice:** *Profile* (first time: fill in the address, then save) → *Accounts* → open an account, deposit, withdraw.
2. **sam** (another browser, or a private window): *Back office* → *View accounts* of Alice. There are no Freeze buttons.
3. **ada:** *Back office* → freeze Alice's account. Alice's next withdrawal shows *422 … is FROZEN*.

Request file with every allowed and forbidden case: [http/phase-08.http](http/phase-08.http).

## 9. Real test results

### Attacks with real Keycloak tokens via the gateway (curl)

```text
Alice's account: 007d7b93-…  balance: 90.0
1. bob reads alice's account by ID                    404 Resource not found
2. bob withdraws from alice's account                 404 Resource not found
3. bob lists accounts with ?ownerId=alice             returned 0 accounts (parameter ignored, bob has none)
4. bob opens an account "for alice" (ownerId in body) created with ownerId b0b5e1c2 (bob's own)
5. sam (SUPPORT) withdraws from alice                 403 Forbidden
6. sam (SUPPORT) reads alice's accounts (back office) 200
7. sam (SUPPORT) freezes alice's account              403 Forbidden
8. alice browses the customer list                    403 Forbidden
9. payment-service token (no business role)           403 Forbidden
10. alice's balance afterwards                        90.0 (unchanged)

AUDIT action=CHANGE_STATUS actor=ada0c0de-… account=007d7b93-… from=ACTIVE to=FROZEN reason="Changed in back office by ada"
AUDIT action=CHANGE_STATUS actor=ada0c0de-… account=007d7b93-… from=FROZEN to=ACTIVE reason="Changed in back office by ada"
AUDIT action=WITHDRAW actor=7f3c9a2e-… account=007d7b93-… amount=10
```

### Automated

| Suite | Count | Highlights |
|---|---|---|
| user-service | 31 | identity from token not body; Bob can't edit Alice's profile; staff have no `/me`; SUPPORT can't write; customers can't browse |
| account-service | 42 | 404 for others' accounts (read, balance, deposit, withdraw); SUPPORT/ADMIN 403 on money; freeze → 422; close rules |
| api-gateway | 23 | `/api/admin/**` 403 for customers at the edge, 200 for SUPPORT |
| Angular unit | 14 | multi-role guard; menu by role |
| Playwright e2e | 7 | alice → sam → ada → alice scenario in **separate browser sessions**: deposit, support read-only (no freeze button), admin freeze, withdraw refused with 422 FROZEN, unfreeze, withdraw works |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| **500** instead of 403 for a method-level denial | `@ExceptionHandler(Exception.class)` swallowed `AccessDeniedException`. Add the explicit 403 handler (done). |
| `@PreAuthorize` has no effect | `@EnableMethodSecurity` missing, or the method is called from inside the same class (self-invocation bypasses the proxy). |
| Everyone gets 403 | Role name mismatch: `hasRole('ADMIN')` expects authority `ROLE_ADMIN`. Check `/api/whoami` authorities. |
| Customer gets 404 on their own account | Token `sub` ≠ `owner_id` in the DB (e.g. account created with a client-supplied ownerId before Phase 8, or Keycloak user recreated with a new ID). |
| Staff get 403 on `/api/customers/me` | By design: staff aren't customers. They use `/api/admin/**`. |
| UI shows a button but the API answers 403 | Expected if the UI and backend rules diverge. **The backend is the truth.** Align the UI. |
| Unknown path returns 403, not 404 | The allow-list `denyAll()` refuses paths outside `/api/**` before routing. |
| e2e test flaky when counting rows | Wait for loading to finish before counting (fixed in the tests). |

## 11. Production notes

- **Step-up authentication** for admin actions (Keycloak can require MFA via `acr` / ACR-to-LoA mapping), and short admin sessions.
- **Fine-grained permissions** instead of roles for big back offices (e.g. `accounts:freeze`, `customers:read`) using Keycloak client roles or scopes, or an external policy engine (OPA, Cedar, Keycloak Authorization Services).
- **Four-eyes principle** for sensitive actions (closing accounts, raising limits): one admin requests, another approves.
- **Audit log** in append-only, tamper-evident storage with retention (Phase 14 ships logs centrally).
- **Support access reasons and data masking:** SUPPORT sees masked data by default, and reveals full data only with a ticket reference (logged).

## 12. Interview questions

1. Authentication vs authorization vs ownership: give an example of each in this system.
2. What is IDOR/BOLA? Show a vulnerable endpoint from an earlier phase and how Phase 8 fixed it.
3. Why should a server never trust a user ID sent in the request body or URL for "who am I"?
4. 403 vs 404 for another user's resource: which do you return and why?
5. URL-based rules vs method-level `@PreAuthorize` vs service-layer checks: what belongs where? Why use several layers?
6. Why can an `@ExceptionHandler(Exception.class)` break Spring Security, and how do you fix it?
7. Why doesn't `@PreAuthorize` work on a method called from the same class?
8. How would you implement "SUPPORT can view but not change" and test it?
9. What is segregation of duties? Why can't ADMIN move money in this design?
10. Why are frontend role checks only a UX feature? What happens if they disagree with the backend?
11. Roles vs permissions (scopes): when do roles stop being enough?
12. What should an audit log entry contain, and where should it be stored?
13. How would you require MFA only for admin actions?
