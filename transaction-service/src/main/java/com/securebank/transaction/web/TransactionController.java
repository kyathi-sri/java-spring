package com.securebank.transaction.web;

import com.securebank.common.web.PageResponse;
import com.securebank.transaction.dto.BalanceView;
import com.securebank.transaction.dto.EntryResponse;
import com.securebank.transaction.dto.LedgerLineView;
import com.securebank.transaction.dto.PostEntryRequest;
import com.securebank.transaction.dto.ReconciliationDtos.AccountCheckReport;
import com.securebank.transaction.dto.ReconciliationDtos.AccountCheckRequest;
import com.securebank.transaction.dto.ReconciliationDtos.TrialBalance;
import com.securebank.transaction.dto.ReversalRequest;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@Tag(name = "Ledger")
public class TransactionController {

    private final LedgerService ledger;

    public TransactionController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    @Operation(summary = "Post a balanced journal entry (idempotent on reference)",
            description = "201 when posted, 200 when the same reference and payload was already posted.")
    public ResponseEntity<EntryResponse> post(@Valid @RequestBody PostEntryRequest request, Authentication auth) {
        EntryResponse result = ledger.post(request, Caller.from(auth));
        return created(result);
    }

    @GetMapping("/reference/{reference}")
    @Operation(summary = "Find a journal entry by its idempotency reference (status check after a timeout)")
    public EntryResponse getByReference(@PathVariable String reference) {
        return ledger.getEntryByReference(reference);
    }

    @GetMapping("/{entryId}")
    @Operation(summary = "Get a journal entry with its lines")
    public EntryResponse get(@PathVariable UUID entryId) {
        return ledger.getEntry(entryId);
    }

    @PostMapping("/{entryId}/reversal")
    @Operation(summary = "Reverse an entry by posting an opposite entry (original is never changed)")
    public ResponseEntity<EntryResponse> reverse(@PathVariable UUID entryId,
                                                 @Valid @RequestBody ReversalRequest request,
                                                 Authentication auth) {
        return created(ledger.reverse(entryId, request.reason(), Caller.from(auth)));
    }

    @GetMapping("/account/{accountRef}")
    @Operation(summary = "Paginated ledger history for an account, optionally within [from, to)")
    public PageResponse<LedgerLineView> history(@PathVariable String accountRef,
                                                @RequestParam(required = false) Instant from,
                                                @RequestParam(required = false) Instant to,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(defaultValue = "desc") String sort,
                                                Authentication auth) {
        return ledger.history(accountRef, from, to, page, size, sort, Caller.from(auth));
    }

    @GetMapping("/account/{accountRef}/balance")
    @Operation(summary = "Ledger balance strictly before asOf (default: now)")
    public BalanceView balance(@PathVariable String accountRef,
                               @RequestParam(required = false) Instant asOf,
                               Authentication auth) {
        return ledger.balance(accountRef, asOf, Caller.from(auth));
    }

    @GetMapping("/reconciliation/trial-balance")
    @Operation(summary = "Ledger-wide integrity check (debits = credits per currency)")
    public TrialBalance trialBalance() {
        return ledger.trialBalance();
    }

    @PostMapping("/reconciliation/accounts")
    @Operation(summary = "Compare externally held balances with the ledger")
    public AccountCheckReport checkAccounts(@Valid @RequestBody AccountCheckRequest request) {
        return ledger.checkAccounts(request);
    }

    private static ResponseEntity<EntryResponse> created(EntryResponse result) {
        if (result.replayed()) {
            return ResponseEntity.ok(result);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/transactions/{id}").buildAndExpand(result.entryId()).toUri();
        return ResponseEntity.created(location).body(result);
    }
}
