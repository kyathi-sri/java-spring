# SecureBank — Enterprise Digital Banking Microservices Platform

An educational, production-oriented banking platform built to demonstrate senior-level
(5–9 yrs) Java engineering: Java 21, Spring Boot 3.x, Spring Cloud microservices, ACID
transactions, saga/outbox patterns for cross-service consistency, double-entry ledgers,
security, testing, Docker, and AWS deployment.

> ⚠️ Educational project. Uses **fictional customers and synthetic data only**. This is
> built with standard banking-industry engineering patterns and is **not** anyone's
> proprietary architecture.

---

## Architecture

A Maven multi-module (monorepo) project. Each service owns its own database
(database-per-service) and never reads another service's tables directly — services
integrate over REST and asynchronous events.

| Module                | Responsibility                                                        | Status |
|-----------------------|-----------------------------------------------------------------------|--------|
| `common-library`      | Shared **technical** utilities only (money, errors, correlation, event contracts) | ✅ built |
| `account-service`     | Accounts, balances, deposits, withdrawals                             | ✅ built, tested |
| `transaction-service` | Immutable double-entry ledger & history                               | ✅ built, tested |
| `transfer-service`    | Fund transfers: orchestrated saga, transactional outbox, Kafka, idempotency, crash recovery | ✅ built, tested |
| `statement-service`   | Statements, date-range history, CSV export                            | ⏳ planned |
| `notification-service`| Simulated transfer notifications (event consumer)                     | ⏳ planned |
| `api-gateway`         | Spring Cloud Gateway + JWT resource server + RBAC                     | ⏳ planned |

### Version baseline (verified against official compatibility matrix)

- **Spring Boot**: 3.3.5
- **Spring Cloud**: 2023.0.3 ("Leyton" — supports Boot 3.3.x/3.2.x)
- **Java language level**: 21

Source: [Spring Cloud Supported Versions](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions).

---

## Prerequisites

- **JDK 21+** (this workspace was validated on Temurin JDK 25 — Java 21 language level)
- **Docker** (for Postgres, Kafka, and Testcontainers integration tests)
- Git

You do **not** need a global Maven install — a committed **Maven Wrapper** is included.

---

## Building

From the repository root:

```bash
# Linux / macOS / WSL
./mvnw clean verify

# Windows PowerShell / cmd
.\mvnw.cmd clean verify
```

`clean verify` is the standard validation command (compiles, runs unit tests, and runs
Testcontainers-backed integration tests via the failsafe plugin).

### Environment notes (Windows, first-time setup)

This repo was bootstrapped on a Windows machine where two environment quirks were
handled so builds "just work":

1. **Workspace path contains spaces.** The stock `mvnw.cmd` mishandles spaced paths;
   the committed wrapper quotes the final Maven invocation to fix this.
2. **Corporate/JDK TLS truststore.** The JDK could not validate Maven Central's
   certificate chain even though the OS trusted it (typical of SSL-inspecting
   networks). `.mvn/jvm.config` sets `-Djavax.net.ssl.trustStoreType=Windows-ROOT`
   so Maven reuses the Windows certificate store. This is Windows-specific: on
   Linux/macOS/WSL (not tested here) delete `.mvn/jvm.config`.
3. **Docker Engine 29+** rejects old Docker API versions, so Testcontainers is pinned to
   1.21.4 or newer. Docker Desktop must be running for `mvn verify`.
4. **Docker images** copy a prebuilt jar (build with `mvn verify` first), because Maven
   inside a container does not trust a TLS-inspecting network's certificate.

---

## Running the stack locally

```powershell
.\mvnw.cmd clean verify             # build and test the jars the images copy
.\scripts\new-local-env.ps1         # once: .env with random local secrets (git-ignored)
docker compose up -d --build        # PostgreSQL (one DB per service), Kafka, 3 services
.\scripts\e2e-transfer-demo.ps1     # end-to-end scenarios, including a ledger outage
docker compose down                 # stop (add -v to also delete the database volume)
```

Ports: account 8081, transaction 8082, transfer 8083, PostgreSQL **55432** (avoids a local
5432), Kafka 9094. Local test tokens: `.\scripts\mint-jwt.ps1 -Subject alice -Roles CUSTOMER`.

**API testing guide** with real sample requests and responses: [`docs/API-TESTING-GUIDE.md`](docs/API-TESTING-GUIDE.md).

---

## Project conventions

- **Money** is always `BigDecimal` via `com.securebank.common.money.Money`
  (scale 2, banker's rounding). Never `double`/`float`.
- **Errors** use the shared `ApiError` shape and `BusinessException` hierarchy.
- **Correlation IDs** flow via the `X-Correlation-Id` header and SLF4J MDC.
- **Database-per-service**; cross-service writes use **saga + transactional outbox**,
  never distributed 2PC. (Rationale documented in `transfer-service`.)

---

## Repository layout

```
securebank-platform/
├─ pom.xml                 # parent / BOM / module aggregator
├─ mvnw, mvnw.cmd, .mvn/   # Maven Wrapper + jvm.config
├─ common-library/         # shared technical utilities (no business entities)
├─ account-service/        # accounts, balances, holds, transfer credits
├─ transaction-service/    # double-entry ledger
├─ transfer-service/       # transfer saga, outbox, Kafka
├─ docker-compose.yml      # local stack; docker/postgres/ holds the DB init script
├─ scripts/                # local env, JWT minting, end-to-end demo
└─ docs/                   # API testing guide
```

## Known limitations (planned work)

- Deposits and withdrawals made directly in account-service are **not yet posted to the
  ledger**; only transfers are. Ledger and account balances therefore reconcile only for
  money moved by transfers. The fix is for account-service to publish deposit/withdrawal
  events through its own outbox, for the ledger to post.
- Service-to-service auth uses a shared HMAC secret (local only). AWS should use OAuth2
  client-credentials from an identity provider.

## Git branch strategy

- `main` — released, deployable
- `develop` — integration branch
- `feature/*` — individual features, branched from `develop`
- `hotfix/*` — urgent fixes branched from `main`

> No pushes to any remote until the user has authenticated and explicitly approved the
> target GitHub repository.
