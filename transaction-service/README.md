# transaction-service

Immutable double-entry ledger. Port `8082`. Database: `transaction_db` (PostgreSQL, Flyway-managed).

## Accounting model
Customer deposit accounts are **liabilities** of the bank, so `balance = SUM(CREDIT) - SUM(DEBIT)`.

| Event | Debit | Credit |
|---|---|---|
| Deposit | bank cash (`CASH-SGD`) | customer account |
| Withdrawal | customer account | bank cash |
| Transfer | source account | beneficiary account |
| Reversal | every line of the original, direction flipped | |

A journal entry is a set of lines that must net to zero **per currency**.

## Integrity guarantees (and where each one is enforced)
| Guarantee | Application | Database |
|---|---|---|
| Debits = credits | `LedgerService.assertBalanced` | deferred constraint trigger `trg_entry_balanced`, checked at COMMIT |
| Posted rows never change | Hibernate `@Immutable`, `updatable=false` | triggers reject UPDATE / DELETE / TRUNCATE |
| No double posting | replay lookup by `reference` | `UNIQUE (reference)` |
| An entry is reversed at most once | replay lookup | `UNIQUE (reverses_entry_id)` |
| Positive amounts | Bean Validation | `CHECK (amount > 0)` |

**Idempotency:** `reference` is the idempotency key. A retry with the same reference and the same lines returns the original entry (`200`, `replayed: true`). The same reference with different lines returns `409 IDEMPOTENCY_CONFLICT`. When two identical requests race, the loser hits the unique constraint. Its transaction rolls back, and the service re-reads the winner in a fresh transaction, which is why it uses `TransactionTemplate` rather than `@Transactional`.

**Corrections:** nothing is ever edited. A wrong entry is fixed by `POST /{id}/reversal`, which posts a new, linked `REVERSAL` entry. A reversal cannot itself be reversed; post an `ADJUSTMENT` instead.

**Ownership:** with database-per-service, the ledger cannot look up who owns an account. Each line stores the `ownerId` supplied by the trusted posting service, and customers only ever see lines carrying their own id.

## Endpoints
| Method | Path | Roles |
|---|---|---|
| POST | `/api/transactions` | SERVICE, ADMIN |
| GET | `/api/transactions/{entryId}` | SUPPORT, ADMIN, SERVICE |
| GET | `/api/transactions/reference/{reference}` | SUPPORT, ADMIN, SERVICE. Lets a caller whose posting timed out check whether it was applied (`404 ENTRY_NOT_FOUND` if not) |
| POST | `/api/transactions/{entryId}/reversal` | SERVICE, ADMIN |
| GET | `/api/transactions/account/{accountRef}?from&to&page&size&sort=asc\|desc` | CUSTOMER (own lines), SUPPORT, ADMIN, SERVICE |
| GET | `/api/transactions/account/{accountRef}/balance?asOf` | same as above. Balance strictly before `asOf`, i.e. the opening balance at that instant |
| GET | `/api/transactions/reconciliation/trial-balance` | ADMIN |
| POST | `/api/transactions/reconciliation/accounts` | ADMIN. Compares balances held elsewhere (e.g. account-service) with the ledger |

`from` is inclusive and `to` is exclusive (ISO-8601 instants). `size` is capped at 100.

`SERVICE` is a machine role for other SecureBank services. It is never issued to people.

## Configuration
`TRANSACTION_DB_URL`, `TRANSACTION_DB_USER`, `TRANSACTION_DB_PASSWORD`, `JWT_SECRET` (≥ 32 bytes). Nothing is hard-coded.

## Tests
- `LedgerServiceTest` (Mockito): balance rules per currency, replay vs conflict, reversal line flipping, REVERSAL type rejected.
- `LedgerApiIT` (Testcontainers PostgreSQL): idempotent 201/200, 409 conflict, 422 unbalanced, **20 concurrent identical posts give exactly one entry**, reversal restores balances and is at-most-once, **DB refuses UPDATE/DELETE/TRUNCATE**, **DB refuses an unbalanced entry even when the app is bypassed**, pagination, date windows, customer isolation, role checks, trial balance and account reconciliation.

## Docker
```
.\mvnw.cmd clean verify
docker build -f transaction-service/Dockerfile -t securebank/transaction-service:dev .
```
