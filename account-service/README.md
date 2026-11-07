# account-service

Owns accounts, balances, deposits and withdrawals. Port `8081`. Database: `account_db` (PostgreSQL, Flyway-managed).

## Endpoints

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/api/accounts` | CUSTOMER, ADMIN | 201 + `Location`. ADMIN may pass `ownerId`. |
| GET | `/api/accounts/{id}` | CUSTOMER (own), SUPPORT, ADMIN | |
| GET | `/api/accounts/{id}/balance` | CUSTOMER (own), SUPPORT, ADMIN | |
| POST | `/api/accounts/{id}/deposit` | CUSTOMER (own), ADMIN | amount 0.01 to 1,000,000.00, max 2 decimals |
| POST | `/api/accounts/{id}/withdraw` | CUSTOMER (own), ADMIN | 422 `INSUFFICIENT_FUNDS`, 409 `ACCOUNT_NOT_ACTIVE` |

Balance and account responses now also include `currentBalance`/`balance`, `availableBalance`
and `reservedAmount`. `availableBalance` = current − reserved. These fields were added; no
existing field was removed or renamed.

### Internal API for the transfer saga (`SERVICE` role only)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/internal/accounts/{id}` | summary incl. `ownerId` and status, for transfer validation |
| POST | `/api/internal/accounts/{id}/holds` | reserve funds; idempotent on `reference` (201 new, 200 replay) |
| GET | `/api/internal/accounts/{id}/holds/{ref}` | persisted hold status (used after a timeout) |
| POST | `/api/internal/accounts/{id}/holds/{ref}/capture` | debit the reserved funds; idempotent |
| POST | `/api/internal/accounts/{id}/holds/{ref}/release` | release; idempotent; writes a **tombstone** if the hold does not exist, so a delayed reserve is refused |
| POST | `/api/internal/accounts/{id}/credits` | credit a transfer; idempotent on `reference` |
| GET | `/api/internal/accounts/{id}/credits/{ref}` | persisted credit status |

Hold states: `HELD → CAPTURED` or `HELD → RELEASED`. Any error response from these endpoints
means "not applied", which is what allows the saga to compensate safely.

Swagger UI: `/swagger-ui.html` (open for local learning, lock down in production).

## Business rules
- Money is `BigDecimal`, scale 2, banker's rounding (`common-library` `Money`).
- Debits need an `ACTIVE` account and sufficient funds. Credits are refused only on `CLOSED` accounts.
- Balance changes run under `SELECT ... FOR UPDATE` (pessimistic lock), so concurrent withdrawals serialize. `@Version` is a second guard and `CHECK (balance >= 0)` is the last line of defence.
- Every balance movement writes an append-only `account_audit` row (amount, balance after, reference, correlation id, actor).
- A customer asking for someone else's account gets `404`, not `403`, so account ids cannot be probed.
- Withdrawals and new holds are checked against the **available** balance, so funds reserved for an in-flight transfer cannot be spent twice. The constraint `CHECK (reserved_balance <= balance)` backs this up in the database.
- Every internal operation locks the account row first, then looks up its reference, then applies the business rules. So a retry of an operation that already succeeded always returns the original result (`replayed: true`).

## Configuration (environment variables, nothing hard-coded)
| Variable | Purpose |
|---|---|
| `ACCOUNT_DB_URL` | JDBC URL (default `jdbc:postgresql://localhost:5432/account_db`) |
| `ACCOUNT_DB_USER` / `ACCOUNT_DB_PASSWORD` | DB credentials |
| `JWT_SECRET` | HMAC secret, at least 32 bytes, for validating bearer JWTs locally |
| `SPRING_PROFILES_ACTIVE=local` | Human-readable logs instead of JSON |

JWTs need a `sub` claim (the customer id) and a `roles` claim, e.g. `["CUSTOMER"]`.

## Tests
- `AccountTest`, `AccountServiceImplTest`: unit tests (JUnit 5, Mockito).
- `AccountApiIT`: Testcontainers PostgreSQL + MockMvc. Covers the full flow, validation, 401/403/404, safe error bodies, audit reconciliation, and 25 concurrent withdrawals against a balance of 100 (exactly 10 succeed, final balance 0).
- `HoldsApiIT`: hold/capture/release/credit idempotency and state rules, the tombstone blocking a late reserve, SERVICE-only access, 25 concurrent holds against 100 (exactly 10), concurrent holds mixed with withdrawals (never over-spent), and 20 concurrent retries of one reference (reserved once).

Run from the repo root: `.\mvnw.cmd clean verify` (needs Docker running).

## Docker
```
.\mvnw.cmd clean verify
docker build -f account-service/Dockerfile -t securebank/account-service:dev .
```
The image copies the jar that just passed the tests. It runs as a non-root user.
