# transfer-service

Fund transfers between accounts. Port `8083`. Database: `transfer_db` (PostgreSQL, Flyway-managed).
Kafka: publishes `securebank.transfer.events`, consumes `securebank.transfer.commands`.

## Why a saga, and not one database transaction

A transfer changes three things owned by three services: the payer's balance (account-service),
the payee's balance (account-service), and the ledger (transaction-service). They live in separate
databases, so no single ACID transaction can cover them. Distributed 2PC/XA would make every
transfer depend on all participants being up at once, and would hold locks across the network.

Instead the transfer is an **orchestrated saga**. Each step is a local ACID transaction inside the
service that owns the data. Each step is idempotent on the transfer reference. Failures are undone
by **compensating** steps (release the hold, post a ledger reversal), never by deleting data.

## Saga steps

| Step (persisted) | Call | Definitive rejection | Outcome unknown (timeout / 5xx / connection error) |
|---|---|---|---|
| `CREATED` | reserve funds: `POST /api/internal/accounts/{src}/holds` | → `FAILED` (nothing to undo) | check `GET …/holds/{ref}`: HELD → continue, missing → retry. Past the reservation deadline → `RELEASING_FUNDS` |
| `FUNDS_RESERVED` | post ledger entry: `POST /api/transactions` (TRANSFER: DEBIT payer, CREDIT payee) | → `RELEASING_FUNDS` | check `GET /api/transactions/reference/{ref}` |
| `LEDGER_POSTED` | credit the payee: `POST /api/internal/accounts/{dst}/credits` | → `REVERSING_LEDGER` | check `GET …/credits/{ref}` |
| `BENEFICIARY_CREDITED` | confirm the reservation: `POST …/holds/{ref}/capture` | alert and retry (invariant breach) | check the hold: CAPTURED → `COMPLETED` |
| `REVERSING_LEDGER` | `POST /api/transactions/{entryId}/reversal` | alert and retry | check `GET …/reference/REV-{ref}` |
| `RELEASING_FUNDS` | `POST …/holds/{ref}/release` (writes a tombstone if no hold exists) | alert and retry | check the hold: RELEASED → `FAILED` |

Customer-facing status: `PENDING` (accepted), then `PROCESSING` (any step above, including
compensation), then `COMPLETED` or `FAILED`.

Ordering choices that keep compensation simple and safe:
- The hold is **captured last**: money leaves the payer only after the ledger entry exists and the
  payee has been credited. So nothing ever has to be clawed back from a payee.
- The ledger is posted **before** the payee is credited. If the credit is refused (for example the
  account was closed), the entry is cancelled by a REVERSAL. Committed entries are never edited.

## Failure handling rules

**A timeout is never treated as a failure.** Every call returns one of three results:
- `Success`: 2xx, including an idempotent replay.
- `Rejected`: 4xx, meaning the downstream service guarantees nothing was applied.
- `Unknown`: timeout, connection error, 5xx, or 401/403/408/429.

On `Unknown`, the saga checks the persisted status downstream before doing anything else. If that
check is also inconclusive, the step is retried later with exponential backoff (1s, 2s, 4s, …, capped
at 60s). Retrying is always safe because of idempotency. An ERROR-level `ALERT` is logged once a step
has been retried `stuck-alert-attempts` times.

| Hazard | Protection |
|---|---|
| Client retries a POST | `Idempotency-Key` header, unique per customer. Same payload → `200` replay. Different payload → `409` |
| Concurrent duplicate requests | DB unique constraint. The loser re-reads the winner and returns it as a replay |
| Double spending | Holds ring-fence funds under a row lock; withdrawals and new holds check *available* = balance − reserved |
| Downstream applied the request but the response was lost | status check by reference before any retry or compensation |
| Reserve still in flight when we give up | release writes a **tombstone**, so the delayed reserve is refused |
| Worker crash mid-saga | progress is persisted after every confirmed step. The lease expires and the recovery job resumes from the last step |
| Two workers on one transfer | lease (`lease_owner`, `lease_until`). A claim bumps `version`, so a stale worker's next write fails |
| Event lost between DB commit and Kafka | **transactional outbox**: the event row commits with the state change, and a relay publishes it |
| Duplicate Kafka messages | inbox table `processed_messages` (by `commandId`) plus the per-customer Idempotency-Key |
| Duplicate events delivered to consumers | at-least-once delivery. Every event has a unique `eventId` (payload and Kafka header) to de-duplicate on |

## Transactional outbox

Each state change runs in one local transaction that writes the transfer row, a `transfer_history`
row (append-only, enforced by a trigger) and, for INITIATED / COMPLETED / FAILED, an `outbox_events`
row. `OutboxRelay` polls the oldest unpublished rows with `FOR UPDATE SKIP LOCKED`, so several
instances can run safely. It sends each row with an idempotent producer (`acks=all`), keyed by the
transfer reference so one transfer's events stay ordered, and marks the row published. It stops at
the first failure to preserve order.

Event payload (`TransferEvent`): `eventId, eventType, occurredAt, transferId, reference, status,
sourceAccountId, sourceOwnerId, beneficiaryAccountId, beneficiaryOwnerId, amount, currency,
failureCode, correlationId`.

## Kafka transfer commands

Asynchronous channels (for example a scheduled-payments service) can publish to `securebank.transfer.commands`:

```json
{"commandId":"cmd-2026-11-0001","customerId":"alice","idempotencyKey":"standing-order-0001",
 "sourceAccountId":"<uuid>","beneficiaryAccountId":"<uuid>","amount":12.00,"description":"Standing order"}
```

- Business rejections are recorded in the inbox as `REJECTED:<code>` and skipped.
- If account-service is unavailable, the listener rethrows and the container retries with
  exponential backoff (no limit), so a command is never dropped.

## Endpoints

| Method | Path | Roles | Responses |
|---|---|---|---|
| POST | `/api/transfers` (header `Idempotency-Key`, 8–64 chars `[A-Za-z0-9_-]`) | CUSTOMER (own source), ADMIN | `201` finished (COMPLETED or FAILED, see `status`), `202` still PROCESSING, `200` replay, `400/404/409/422/503` rejected before creation |
| GET | `/api/transfers/{reference}` | CUSTOMER (own), SUPPORT, ADMIN | `200`, `404` |
| GET | `/api/transfers/{reference}/audit` | SUPPORT, ADMIN | saga step, retries, last error, full history |

A failed transfer (for example `INSUFFICIENT_FUNDS`) is still created and returned with `201` and
`status: FAILED`, so the attempt is auditable. Requests rejected before creation (validation,
ownership, unknown beneficiary, currency mismatch) create nothing.

## Configuration

| Variable / property | Default | Meaning |
|---|---|---|
| `TRANSFER_DB_URL`, `TRANSFER_DB_USER`, `TRANSFER_DB_PASSWORD` | | database |
| `JWT_SECRET` | | validates caller JWTs and signs this service's own short-lived `SERVICE` token (local dev only; use client-credentials with an IdP in AWS) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `ACCOUNT_SERVICE_URL`, `TRANSACTION_SERVICE_URL` | `localhost:8081/8082` | |
| `securebank.services.read-timeout` | `3s` | |
| `securebank.transfer.lease-duration` | `30s` | |
| `securebank.transfer.reservation-timeout` | `10m` | after this, an unconfirmed reservation is cancelled |
| `securebank.transfer.retry-base-delay` / `retry-max-delay` | `1s` / `60s` | |
| `securebank.transfer.recovery.interval-ms` | `5000` | |
| `securebank.outbox.interval-ms` | `500` | |

## Tests

- `TransferDomainTest`: status/step rules, first failure wins, backoff.
- `HttpClientsTest` (WireMock): 2xx → Success, 4xx → Rejected with the downstream code, 5xx / 401 /
  403 / slow response / connection refused → Unknown, lookups (200/404/503). Also checks the
  SERVICE token claims, correlation-id propagation and the posting shape.
- `TransferSagaIT` (Testcontainers PostgreSQL + Kafka, fault-injecting fakes of the two downstream
  services). The fakes follow the same contract that `HoldsApiIT` and `LedgerApiIT` verify against
  the real services.
  - Lifecycle: happy path, idempotent replay / 409 / missing key, insufficient funds, validation, 503 at validation, visibility.
  - Concurrency: 20 identical requests → 1 transfer; 10 × 30 from a balance of 100 → exactly 3 complete.
  - Timeouts: reserve / ledger / credit applied despite a timeout; ledger outage → 202 then recovered; reservation deadline → tombstone, then a late reserve is refused.
  - Crashes: crash after the credit was applied but before it was recorded; crash right after creation.
  - Compensation: payee rejects → reversal + release; ledger rejects → release only; compensation steps that time out.
  - Kafka: outbox events published in order and not re-sent; the same command delivered 3× → 1 transfer; a rejected command is recorded.

End-to-end against the real services: `scripts/e2e-transfer-demo.ps1` (see `docs/API-TESTING-GUIDE.md`).
