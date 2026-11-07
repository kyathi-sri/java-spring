# SecureBank API Testing Guide: accounts, ledger and transfers

Every response below was captured from a real run of the Docker Compose stack (three services,
PostgreSQL 16, Kafka 3.8) on 2026-11-07. JSON is pretty-printed for reading; field values are
unchanged. IDs, references and timestamps will differ on your machine. All customers are fictional.

- [0. Setup](#0-setup)
- [1. Accounts](#1-accounts)
- [2. Transfer that completes](#2-transfer-that-completes)
- [3. Idempotency](#3-idempotency)
- [4. Rejections and failed transfers](#4-rejections-and-failed-transfers)
- [5. Reading transfers](#5-reading-transfers)
- [6. Balances and the ledger](#6-balances-and-the-ledger)
- [7. Kafka outbox](#7-kafka-outbox)
- [8. Outage and recovery](#8-outage-and-recovery)
- [9. Internal service-to-service APIs](#9-internal-service-to-service-apis)
- [10. Status and error code reference](#10-status-and-error-code-reference)

---

## 0. Setup

```powershell
.\mvnw.cmd clean verify                  # build + all tests (Docker Desktop must be running)
.\scripts\new-local-env.ps1              # once: .env with random local secrets (git-ignored)
docker compose up -d --build             # postgres, kafka, account/transaction/transfer services
```

| Service | Base URL | Swagger UI |
|---|---|---|
| account-service | http://localhost:8081 | http://localhost:8081/swagger-ui.html |
| transaction-service | http://localhost:8082 | http://localhost:8082/swagger-ui.html |
| transfer-service | http://localhost:8083 | http://localhost:8083/swagger-ui.html |

**Tokens (local only).** The services validate HS256 JWTs signed with `JWT_SECRET`. The token
needs a `sub` (customer id) and a `roles` claim. Mint tokens with:

```powershell
$alice = .\scripts\mint-jwt.ps1 -Subject alice   -Roles CUSTOMER
$bob   = .\scripts\mint-jwt.ps1 -Subject bob     -Roles CUSTOMER
$agent = .\scripts\mint-jwt.ps1 -Subject agent-7 -Roles SUPPORT
$admin = .\scripts\mint-jwt.ps1 -Subject ops-admin -Roles ADMIN
```

The `curl` examples below use bash syntax (Git Bash / WSL) with `$ALICE` etc. holding those tokens.
To run every scenario in this guide automatically from PowerShell:

```powershell
.\scripts\e2e-transfer-demo.ps1          # add -SkipOutage to leave transaction-service running
```

Every response carries an `X-Correlation-Id` header. Send your own value to trace a request
through all three services' logs.

---

## 1. Accounts

### 1a. Open an account. `POST /api/accounts` (CUSTOMER, ADMIN)

```bash
curl -s -X POST http://localhost:8081/api/accounts \
  -H "Authorization: Bearer $ALICE" -H "Content-Type: application/json" \
  -d '{"customerName":"Alice Tan"}'
```

`201 Created`, with `Location: /api/accounts/{id}`

```json
{
  "id": "5e8934ce-a78c-4d1c-b52d-18d85101568a",
  "accountNumber": "SB108721293665",
  "customerName": "Alice Tan",
  "currency": "SGD",
  "status": "ACTIVE",
  "balance": 0.00,
  "createdAt": "2026-11-07T09:03:48.789984190Z",
  "availableBalance": 0.00,
  "reservedAmount": 0.00
}
```

Bob does the same and gets account `59223196-03e7-4f47-a784-032a13494af2`.

### 1b. Deposit. `POST /api/accounts/{id}/deposit`

```bash
curl -s -X POST http://localhost:8081/api/accounts/$A/deposit \
  -H "Authorization: Bearer $ALICE" -H "Content-Type: application/json" \
  -d '{"amount":1000.00,"reference":"PAYROLL-NOV"}'
```

`200 OK`

```json
{
  "accountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a",
  "accountNumber": "SB108721293665",
  "currency": "SGD",
  "availableBalance": 1000.00,
  "status": "ACTIVE",
  "asOf": "2026-11-07T09:03:49.279723777Z",
  "currentBalance": 1000.00,
  "reservedAmount": 0.00
}
```

`availableBalance` = `currentBalance` − `reservedAmount` (funds held for in-flight transfers).
`currentBalance` and `reservedAmount` are new fields; existing fields are unchanged.

---

## 2. Transfer that completes

### `POST /api/transfers` (CUSTOMER for their own source account, ADMIN)

`Idempotency-Key` is required: 8–64 characters `[A-Za-z0-9_-]`. Generate a new one per logical
transfer, and reuse it when retrying that same transfer.

```bash
curl -s -X POST http://localhost:8083/api/transfers \
  -H "Authorization: Bearer $ALICE" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-5f1c2a9e7b3d4c60" \
  -d '{"sourceAccountId":"5e8934ce-a78c-4d1c-b52d-18d85101568a",
       "beneficiaryAccountId":"59223196-03e7-4f47-a784-032a13494af2",
       "amount":250.75,"description":"Rent November"}'
```

`201 Created`, with `Location: /api/transfers/TRF-9cac3372-…`

```json
{
  "reference": "TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b",
  "status": "COMPLETED",
  "sourceAccountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a",
  "beneficiaryAccountId": "59223196-03e7-4f47-a784-032a13494af2",
  "amount": 250.75,
  "currency": "SGD",
  "description": "Rent November",
  "failureCode": null,
  "failureReason": null,
  "createdAt": "2026-11-07T09:03:49.616245Z",
  "updatedAt": "2026-11-07T09:03:50.682886Z",
  "completedAt": "2026-11-07T09:03:50.682886Z"
}
```

| HTTP | Meaning |
|---|---|
| `201` | finished: `status` is `COMPLETED` **or** `FAILED` (always read `status`) |
| `202` | accepted, still `PENDING`/`PROCESSING`; poll `GET /api/transfers/{reference}` |
| `200` | replay of an earlier request with the same Idempotency-Key |

---

## 3. Idempotency

### 3a. Same key, same body (for example after a client timeout) → `200 OK`, same transfer, no second debit

```json
{
  "reference": "TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b",
  "status": "COMPLETED",
  "amount": 250.75,
  "...": "identical to the original response"
}
```

`10`, `10.0` and `10.00` count as the same amount.

### 3b. Same key, different amount → `409 Conflict`

```json
{
  "timestamp": "2026-11-07T09:03:50.863252559Z",
  "status": 409,
  "error": "IDEMPOTENCY_CONFLICT",
  "message": "This Idempotency-Key was already used for a different transfer request",
  "path": "/api/transfers",
  "correlationId": "6b21913f-7510-4033-830c-998f02bfba2a"
}
```

### 3c. No `Idempotency-Key` header → `400 Bad Request`

```json
{
  "timestamp": "2026-11-07T09:03:50.946332521Z",
  "status": 400,
  "error": "MISSING_HEADER",
  "message": "Required header Idempotency-Key is missing",
  "path": "/api/transfers",
  "correlationId": "a634c61a-e0a2-446d-b1d1-bc36c97f3983"
}
```

---

## 4. Rejections and failed transfers

### 4a. Insufficient funds → `201 Created` with `status: FAILED`

The transfer is recorded for audit, nothing is reserved, and nothing is posted to the ledger.

```json
{
  "reference": "TRF-d8a72fee-a7bc-4a8a-96b1-a15eec0949a8",
  "status": "FAILED",
  "sourceAccountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a",
  "beneficiaryAccountId": "59223196-03e7-4f47-a784-032a13494af2",
  "amount": 5000.00,
  "currency": "SGD",
  "description": "Rent November",
  "failureCode": "INSUFFICIENT_FUNDS",
  "failureReason": "Insufficient funds in account SB108721293665",
  "createdAt": "2026-11-07T09:03:51.045428Z",
  "updatedAt": "2026-11-07T09:03:51.125131Z",
  "completedAt": "2026-11-07T09:03:51.125131Z"
}
```

### 4b. Source account owned by someone else (Bob uses Alice's account) → `404`

The response is deliberately the same as for an account that does not exist.

```json
{ "status": 404, "error": "ACCOUNT_NOT_FOUND", "message": "Source account not found", "path": "/api/transfers",
  "timestamp": "2026-11-07T09:03:51.253955339Z", "correlationId": "6eaa720d-891f-425d-8cf4-024355ea6392" }
```

### 4c. Unknown beneficiary → `422`

```json
{ "status": 422, "error": "BENEFICIARY_NOT_FOUND", "message": "Beneficiary account not found", "path": "/api/transfers",
  "timestamp": "2026-11-07T09:03:51.391801551Z", "correlationId": "a74cc742-fe3f-4edd-8a22-e9f4d69f6994" }
```

### 4d. Invalid amount (`-5`) → `400`

```json
{
  "timestamp": "2026-11-07T09:03:51.488345425Z",
  "status": 400,
  "error": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/transfers",
  "correlationId": "e02b407d-64ae-4e93-8246-d4b616787b5f",
  "fieldErrors": [ { "field": "amount", "message": "must be at least 0.01" } ]
}
```

Rejections 4b to 4d (and `SAME_ACCOUNT`, `CURRENCY_MISMATCH`, `DEPENDENCY_UNAVAILABLE`) create no
transfer and move no money.

---

## 5. Reading transfers

### 5a. `GET /api/transfers/{reference}`. Alice reads her own transfer → `200` (same body as section 2)

### 5b. Bob reads Alice's transfer → `404`

```json
{ "status": 404, "error": "TRANSFER_NOT_FOUND",
  "message": "Transfer TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b not found",
  "path": "/api/transfers/TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b",
  "timestamp": "2026-11-07T09:03:51.650114070Z", "correlationId": "d1663f7c-8a93-4c0b-bbfd-9b172a68ac48" }
```

### 5c. `GET /api/transfers/{reference}/audit` (SUPPORT, ADMIN): saga internals and history

```bash
curl -s http://localhost:8083/api/transfers/TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b/audit \
  -H "Authorization: Bearer $AGENT"
```

`200 OK`

```json
{
  "reference": "TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b",
  "status": "COMPLETED",
  "step": "COMPLETED",
  "channel": "API",
  "initiatedBy": "alice",
  "ledgerEntryId": "0e7b0b5b-be31-4b1f-9605-e24e5d61dfcd",
  "attempts": 0,
  "lastError": null,
  "nextAttemptAt": null,
  "correlationId": "eebc0b1c-c43f-4d61-b979-b6f6653c9a15",
  "history": [
    { "fromStatus": null,         "toStatus": "PENDING",    "fromStep": null,                   "toStep": "CREATED",              "detail": "Transfer accepted via API",                                  "at": "2026-11-07T09:03:49.662171Z" },
    { "fromStatus": "PENDING",    "toStatus": "PROCESSING", "fromStep": "CREATED",              "toStep": "CREATED",              "detail": "Processing started",                                         "at": "2026-11-07T09:03:49.744073Z" },
    { "fromStatus": "PROCESSING", "toStatus": "PROCESSING", "fromStep": "CREATED",              "toStep": "FUNDS_RESERVED",       "detail": "Funds reserved",                                             "at": "2026-11-07T09:03:49.813367Z" },
    { "fromStatus": "PROCESSING", "toStatus": "PROCESSING", "fromStep": "FUNDS_RESERVED",       "toStep": "LEDGER_POSTED",        "detail": "Ledger entry posted 0e7b0b5b-be31-4b1f-9605-e24e5d61dfcd", "at": "2026-11-07T09:03:50.583047Z" },
    { "fromStatus": "PROCESSING", "toStatus": "PROCESSING", "fromStep": "LEDGER_POSTED",        "toStep": "BENEFICIARY_CREDITED", "detail": "Beneficiary credited",                                       "at": "2026-11-07T09:03:50.642630Z" },
    { "fromStatus": "PROCESSING", "toStatus": "COMPLETED",  "fromStep": "BENEFICIARY_CREDITED", "toStep": "COMPLETED",            "detail": "Reservation captured; transfer complete",                    "at": "2026-11-07T09:03:50.685959Z" }
  ]
}
```

---

## 6. Balances and the ledger

### 6a and 6b. `GET /api/accounts/{id}/balance` after the 250.75 transfer

Alice:

```json
{ "accountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a", "accountNumber": "SB108721293665", "currency": "SGD",
  "availableBalance": 749.25, "status": "ACTIVE", "asOf": "2026-11-07T09:03:51.774749469Z",
  "currentBalance": 749.25, "reservedAmount": 0.00 }
```

Bob:

```json
{ "accountId": "59223196-03e7-4f47-a784-032a13494af2", "accountNumber": "SB011929557968", "currency": "SGD",
  "availableBalance": 250.75, "status": "ACTIVE", "asOf": "2026-11-07T09:03:51.836300092Z",
  "currentBalance": 250.75, "reservedAmount": 0.00 }
```

### 6c. `GET /api/transactions/account/{id}`. Bob's ledger history (he sees only his own lines)

```bash
curl -s "http://localhost:8082/api/transactions/account/59223196-03e7-4f47-a784-032a13494af2?page=0&size=20&sort=desc" \
  -H "Authorization: Bearer $BOB"
```

```json
{
  "content": [
    {
      "lineId": 4,
      "entryId": "0e7b0b5b-be31-4b1f-9605-e24e5d61dfcd",
      "reference": "TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b",
      "entryType": "TRANSFER",
      "direction": "CREDIT",
      "amount": 250.75,
      "currency": "SGD",
      "postedAt": "2026-11-07T09:03:50.412192Z",
      "description": "Transfer TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

### 6d. `GET /api/transactions/reconciliation/trial-balance` (ADMIN)

```json
{
  "generatedAt": "2026-11-07T09:03:52.116891198Z",
  "currencies": [ { "currency": "SGD", "totalDebits": 501.50, "totalCredits": 501.50, "lineCount": 4, "balanced": true } ],
  "unbalancedEntries": 0,
  "healthy": true
}
```

(501.50 = this run's 250.75 transfer plus one from an earlier run on the same database volume.)

> Current limitation: deposits and withdrawals made directly through account-service are not yet
> posted to the ledger. Only transfers are. Until that integration lands, ledger balances match
> account balances only for money moved by transfers.

---

## 7. Kafka outbox

Each transfer writes `TRANSFER_INITIATED` and then `TRANSFER_COMPLETED` or `TRANSFER_FAILED` to
`outbox_events`, in the same transaction as the state change. The relay publishes them to
`securebank.transfer.events`, keyed by transfer reference, with `eventId` and `eventType` headers.

```powershell
docker compose exec -T postgres psql -U postgres -d transfer_db -c `
  "select o.event_type, o.published_at is not null as published, o.attempts from outbox_events o join transfers t on t.id = o.aggregate_id where t.reference = 'TRF-9cac3372-a501-4e7b-90d3-b1da1b246d0b' order by o.id;"
```

```
     event_type     | published | attempts
--------------------+-----------+----------
 TRANSFER_INITIATED | t         |        1
 TRANSFER_COMPLETED | t         |        1
```

To watch the topic itself (not run during this capture; it pulls the full `apache/kafka` image,
which contains the CLI tools):

```powershell
docker run --rm --network securebank_default apache/kafka:3.8.0 /opt/kafka/bin/kafka-console-consumer.sh `
  --bootstrap-server kafka:9092 --topic securebank.transfer.events --from-beginning --property print.key=true
```

---

## 8. Outage and recovery

This shows that a timeout is not treated as a failure: the transfer waits and then completes.

```powershell
docker compose stop transaction-service      # the ledger goes down
```

### 8a. Transfer 100.00 while the ledger is down → `202 Accepted`

```json
{
  "reference": "TRF-e297259e-46b7-4e34-96fe-0545e34775d0",
  "status": "PROCESSING",
  "sourceAccountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a",
  "beneficiaryAccountId": "59223196-03e7-4f47-a784-032a13494af2",
  "amount": 100.00,
  "currency": "SGD",
  "description": "Groceries split",
  "failureCode": null,
  "failureReason": null,
  "createdAt": "2026-11-07T09:03:55.394001Z",
  "updatedAt": "2026-11-07T09:03:57.564127Z",
  "completedAt": null
}
```

### 8b. Alice's balance during the outage: the 100.00 is reserved and cannot be spent elsewhere

```json
{ "availableBalance": 649.25, "currentBalance": 749.25, "reservedAmount": 100.00, "status": "ACTIVE",
  "accountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a", "accountNumber": "SB108721293665", "currency": "SGD",
  "asOf": "2026-11-07T09:03:57.635539012Z" }
```

```powershell
docker compose start transaction-service     # the ledger comes back; no manual action needed
```

### 8c. Polling `GET /api/transfers/TRF-e297259e-…` → `COMPLETED` (about 26 s after creation)

```json
{ "reference": "TRF-e297259e-46b7-4e34-96fe-0545e34775d0", "status": "COMPLETED", "amount": 100.00,
  "createdAt": "2026-11-07T09:03:55.394001Z", "completedAt": "2026-11-07T09:04:21.683044Z", "...": "..." }
```

### 8d. Audit trail: four retries with backoff (1 s, 2 s, 4 s, 8 s), then the remaining steps

```json
"history": [
  { "toStep": "CREATED",              "detail": "Transfer accepted via API" },
  { "toStep": "CREATED",              "detail": "Processing started" },
  { "toStep": "FUNDS_RESERVED",       "detail": "Funds reserved" },
  { "toStep": "FUNDS_RESERVED",       "detail": "Retry in 1000ms: Posting outcome unknown; status check failed: ConnectException: HTTP connect timed out" },
  { "toStep": "FUNDS_RESERVED",       "detail": "Retry in 2000ms: Posting outcome unknown; status check failed: ClosedChannelException" },
  { "toStep": "FUNDS_RESERVED",       "detail": "Retry in 4000ms: Posting outcome unknown; status check failed: ClosedChannelException" },
  { "toStep": "FUNDS_RESERVED",       "detail": "Retry in 8000ms: Posting outcome unknown; status check failed: ClosedChannelException" },
  { "toStep": "LEDGER_POSTED",        "detail": "Ledger entry posted 491bbc3b-253b-485f-9543-ded496d30a9e" },
  { "toStep": "BENEFICIARY_CREDITED", "detail": "Beneficiary credited" },
  { "toStep": "COMPLETED",            "detail": "Reservation captured; transfer complete" }
]
```

Before each retry the saga checked the ledger by reference (`GET /api/transactions/reference/{ref}`),
so it never posted blindly. Final state: Alice `currentBalance` 649.25, `reservedAmount` 0.00, and
exactly one ledger entry:

```json
{
  "entryId": "491bbc3b-253b-485f-9543-ded496d30a9e",
  "reference": "TRF-e297259e-46b7-4e34-96fe-0545e34775d0",
  "entryType": "TRANSFER",
  "description": "Transfer TRF-e297259e-46b7-4e34-96fe-0545e34775d0",
  "reversesEntryId": null,
  "postedAt": "2026-11-07T09:04:21.462023Z",
  "lines": [
    { "accountRef": "5e8934ce-a78c-4d1c-b52d-18d85101568a", "direction": "DEBIT",  "amount": 100.00, "currency": "SGD" },
    { "accountRef": "59223196-03e7-4f47-a784-032a13494af2", "direction": "CREDIT", "amount": 100.00, "currency": "SGD" }
  ],
  "replayed": false
}
```

Compensation paths (beneficiary rejects the credit, so the ledger entry is reversed and the hold
released; ledger rejects, so the hold is released) and crash scenarios are hard to trigger by hand.
They are covered by `TransferSagaIT`; see `transfer-service/README.md`.

---

## 9. Internal service-to-service APIs

These need a token with the `SERVICE` role. Customer tokens get `403`.

```powershell
$svc = .\scripts\mint-jwt.ps1 -Subject transfer-service -Roles SERVICE
```

### account-service holds and credits (`/api/internal/accounts/{id}/…`)

**Reserve.** `POST /holds` `{"reference":"DEMO-HOLD-0001","amount":50.00,"currency":"SGD"}` → `201`

```json
{ "reference": "DEMO-HOLD-0001", "accountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a", "amount": 50.00,
  "currency": "SGD", "status": "HELD", "tombstone": false, "replayed": false,
  "createdAt": "2026-11-07T09:07:10.809953614Z", "updatedAt": "2026-11-07T09:07:10.809953614Z" }
```

**Same request again** → `200` with `"replayed": true` (funds reserved only once).

**Status check.** `GET /holds/DEMO-HOLD-0001` → `200`, `"status": "HELD"`.

**Release.** `POST /holds/DEMO-HOLD-0001/release` → `200`, `"status": "RELEASED"`.

**Capture after release** → `409`

```json
{ "status": 409, "error": "HOLD_STATE_CONFLICT", "message": "Hold DEMO-HOLD-0001 was released and cannot be captured",
  "path": "/api/internal/accounts/5e8934ce-a78c-4d1c-b52d-18d85101568a/holds/DEMO-HOLD-0001/capture",
  "timestamp": "2026-11-07T09:07:11.243120381Z", "correlationId": "a4ecc6c3-1038-493e-9820-494dd1cfc792" }
```

**Release of a hold that never existed** writes a tombstone → `200`

```json
{ "reference": "DEMO-NEVER-RESERVED", "accountId": "5e8934ce-a78c-4d1c-b52d-18d85101568a", "amount": 0.00,
  "currency": "SGD", "status": "RELEASED", "tombstone": true, "replayed": false,
  "createdAt": "2026-11-07T09:07:11.333683865Z", "updatedAt": "2026-11-07T09:07:11.333683865Z" }
```

**A delayed reserve with that reference is then refused** → `409`

```json
{ "status": 409, "error": "HOLD_STATE_CONFLICT", "message": "Hold DEMO-NEVER-RESERVED has already been released",
  "path": "/api/internal/accounts/5e8934ce-a78c-4d1c-b52d-18d85101568a/holds",
  "timestamp": "2026-11-07T09:07:11.498003669Z", "correlationId": "d966540f-f87b-46dc-a82f-9ab4ce684a91" }
```

**Summary.** `GET /api/internal/accounts/{id}` → `200`

```json
{ "id": "5e8934ce-a78c-4d1c-b52d-18d85101568a", "accountNumber": "SB108721293665", "ownerId": "alice",
  "currency": "SGD", "status": "ACTIVE" }
```

Also available: `POST /holds/{ref}/capture`, `POST /credits` (idempotent on `reference`, `201`
then `200` on replay), and `GET /credits/{ref}`.

### transaction-service lookup by reference

`GET /api/transactions/reference/{reference}` (SERVICE, SUPPORT, ADMIN): `200` with the entry
(section 8d), or `404`:

```json
{ "status": 404, "error": "ENTRY_NOT_FOUND", "message": "No journal entry with reference TRF-does-not-exist",
  "path": "/api/transactions/reference/TRF-does-not-exist",
  "timestamp": "2026-11-07T09:07:11.725262808Z", "correlationId": "c1f648c8-ade8-4f66-bc68-af131f8b76b4" }
```

---

## 10. Status and error code reference

### Transfer `status` and `failureCode`

| status | Meaning | Money |
|---|---|---|
| `PENDING` | accepted, saga not started | untouched |
| `PROCESSING` | saga running, waiting on a retry, or compensating | possibly reserved (not spendable) |
| `COMPLETED` | payer debited, payee credited, one TRANSFER ledger entry | moved exactly once |
| `FAILED` | see `failureCode`; anything partially done has been compensated | nothing moved; any hold released; any ledger entry reversed |

| failureCode | Cause |
|---|---|
| `INSUFFICIENT_FUNDS` | available balance below amount at reservation time |
| `ACCOUNT_NOT_ACTIVE` | source not ACTIVE at reservation, or payee closed before the credit (ledger reversed) |
| `RESERVATION_TIMEOUT` | funds could not be confirmed as reserved within `reservation-timeout` (10 min); any late hold is cancelled |
| `UNBALANCED_ENTRY`, `LEDGER_INTEGRITY_VIOLATION` | ledger refused the posting; hold released |

### HTTP errors (all services return the same error shape)

| HTTP | `error` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation; see `fieldErrors` |
| 400 | `MALFORMED_REQUEST` | bad JSON or a non-UUID id |
| 400 | `MISSING_HEADER`, `INVALID_IDEMPOTENCY_KEY`, `SAME_ACCOUNT` | transfer request problems |
| 401 | (none) | missing, expired or invalid token |
| 403 | (none) | role not allowed (for example a customer calling `/audit` or `/api/internal/**`) |
| 404 | `ACCOUNT_NOT_FOUND`, `TRANSFER_NOT_FOUND`, `HOLD_NOT_FOUND`, `ENTRY_NOT_FOUND` | not found **or not yours** |
| 409 | `IDEMPOTENCY_CONFLICT` | key or reference reused with a different payload |
| 409 | `ACCOUNT_NOT_ACTIVE`, `HOLD_STATE_CONFLICT` | state does not allow the operation |
| 422 | `INSUFFICIENT_FUNDS`, `BENEFICIARY_NOT_FOUND`, `BENEFICIARY_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `UNBALANCED_ENTRY` | business rule |
| 503 | `DEPENDENCY_UNAVAILABLE` | account-service unreachable while validating; nothing created, retry with the same key |
| 500 | `INTERNAL_ERROR` | unexpected; details only in server logs (search by `correlationId`) |
