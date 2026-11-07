package com.securebank.account.web;

import com.securebank.account.dto.internal.InternalDtos.AccountSummary;
import com.securebank.account.dto.internal.InternalDtos.CreditRequest;
import com.securebank.account.dto.internal.InternalDtos.CreditResponse;
import com.securebank.account.dto.internal.InternalDtos.HoldRequest;
import com.securebank.account.dto.internal.InternalDtos.HoldResponse;
import com.securebank.account.security.Caller;
import com.securebank.account.service.TransferSupportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Service-to-service API for the transfer saga. Restricted to the SERVICE role. */
@RestController
@RequestMapping("/api/internal/accounts")
@Tag(name = "Internal: transfer support (SERVICE role)")
public class InternalAccountController {

    private final TransferSupportService service;

    public InternalAccountController(TransferSupportService service) {
        this.service = service;
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Account summary incl. owner, for transfer validation")
    public AccountSummary summary(@PathVariable UUID accountId) {
        return service.summary(accountId);
    }

    @PostMapping("/{accountId}/holds")
    @Operation(summary = "Reserve funds (idempotent on reference): 201 new, 200 replay")
    public ResponseEntity<HoldResponse> placeHold(@PathVariable UUID accountId,
                                                  @Valid @RequestBody HoldRequest request, Authentication auth) {
        var result = service.placeHold(accountId, request, Caller.from(auth));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping("/{accountId}/holds/{reference}")
    @Operation(summary = "Persisted status of a hold (used after a timeout)")
    public HoldResponse getHold(@PathVariable UUID accountId, @PathVariable String reference) {
        return service.getHold(accountId, reference);
    }

    @PostMapping("/{accountId}/holds/{reference}/capture")
    @Operation(summary = "Capture a hold: debit the reserved funds (idempotent)")
    public HoldResponse capture(@PathVariable UUID accountId, @PathVariable String reference, Authentication auth) {
        return service.captureHold(accountId, reference, Caller.from(auth));
    }

    @PostMapping("/{accountId}/holds/{reference}/release")
    @Operation(summary = "Release a hold (idempotent; writes a tombstone if the hold does not exist)")
    public HoldResponse release(@PathVariable UUID accountId, @PathVariable String reference, Authentication auth) {
        return service.releaseHold(accountId, reference, Caller.from(auth));
    }

    @PostMapping("/{accountId}/credits")
    @Operation(summary = "Credit a transfer to the beneficiary (idempotent on reference): 201 new, 200 replay")
    public ResponseEntity<CreditResponse> credit(@PathVariable UUID accountId,
                                                 @Valid @RequestBody CreditRequest request, Authentication auth) {
        var result = service.credit(accountId, request, Caller.from(auth));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping("/{accountId}/credits/{reference}")
    @Operation(summary = "Persisted status of a credit (used after a timeout)")
    public CreditResponse getCredit(@PathVariable UUID accountId, @PathVariable String reference) {
        return service.getCredit(accountId, reference);
    }
}
