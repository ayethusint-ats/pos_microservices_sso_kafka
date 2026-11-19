# Banking Platform

A learning project: a banking system built as microservices with Spring Boot 3.5, Keycloak, Spring Cloud Gateway, Kafka and Angular.

## Documentation

| Phase | Topic | Doc |
|---|---|---|
| 1 | System architecture and project setup | [documentation/01-system-architecture.md](documentation/01-system-architecture.md) |
| 2 | User Service and Account Service | [documentation/02-user-and-account-services.md](documentation/02-user-and-account-services.md) |
| 3 | PostgreSQL and database-per-service | [documentation/03-postgresql-database-per-service.md](documentation/03-postgresql-database-per-service.md) |
| 4 | Keycloak and centralized authentication | [documentation/04-keycloak-centralized-authentication.md](documentation/04-keycloak-centralized-authentication.md) |
| 5 | Spring Security and JWT validation | [documentation/05-spring-security-jwt-validation.md](documentation/05-spring-security-jwt-validation.md) |
| 6 | Spring Cloud Gateway | [documentation/06-spring-cloud-gateway.md](documentation/06-spring-cloud-gateway.md) |
| 7 | Angular login, logout and token management | [documentation/07-angular-authentication.md](documentation/07-angular-authentication.md) |
| 8 | Role-based authorization and ownership | [documentation/08-role-based-authorization.md](documentation/08-role-based-authorization.md) |
| 9 | Service-to-service communication over REST | [documentation/09-service-to-service-rest.md](documentation/09-service-to-service-rest.md) |

## Layout

```
banking-platform/
├── pom.xml                  # Root aggregator: pins Spring Boot + Spring Cloud versions
├── docker-compose.yml       # Local infrastructure (PostgreSQL now; more each phase)
├── .env.example             # Template for local secrets (copy to .env; never commit .env)
├── frontend/                # Angular 22 SPA :4200, OIDC login via Keycloak (Phase 7)
├── api-gateway/             # Single entry point :8080: routing, JWT, CORS, rate limit (Phase 6)
├── user-service/            # Customer profiles (Phase 2)
├── account-service/         # Accounts and balances (Phase 2)
├── payment-service/         # Transfers :8083, calls account-service over REST (Phase 9); Saga, idempotency (Phase 13)
├── transaction-service/     # Transaction history from Kafka (Phase 11)
├── notification-service/    # Simulated e-mails from Kafka (Phase 11)
├── infrastructure/
│   ├── keycloak/            # Realm as code + PKCE login script (Phase 4)
│   ├── postgres/init/       # Creates one database + role per service (Phase 3)
│   └── kafka/               # Topic setup (Phase 11)
└── documentation/
```

## Prerequisites

- JDK 21 or newer (code targets Java 21)
- Maven 3.9+
- Docker with Docker Compose v2
- Node.js 22.22+, 24.15+ or 26+ (Angular 22)

## Run

```bash
cp .env.example .env                    # first time: then set real passwords in .env
docker compose up -d                    # PostgreSQL :5433, Keycloak :8180
TOKEN=$(./infrastructure/keycloak/pkce-login.sh alice 2>/dev/null)   # log in as alice
curl -s localhost:8080/api/whoami -H "Authorization: Bearer $TOKEN"  # through the gateway
mvn verify                              # build all services and run tests (needs Docker)
mvn -pl user-service spring-boot:run    # :8081
mvn -pl account-service spring-boot:run # :8082
mvn -pl api-gateway spring-boot:run     # :8080  ← clients use this one
cd frontend && npm install && npm start # http://localhost:4200
```
