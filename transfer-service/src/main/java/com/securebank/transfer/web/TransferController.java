package com.securebank.transfer.web;

import com.securebank.transfer.domain.Channel;
import com.securebank.transfer.dto.TransferDtos.CreateTransferRequest;
import com.securebank.transfer.dto.TransferDtos.TransferAudit;
import com.securebank.transfer.dto.TransferDtos.TransferResponse;
import com.securebank.transfer.security.Caller;
import com.securebank.transfer.service.TransferService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Initiate a transfer (Idempotency-Key header required)",
            description = "201: finished (COMPLETED or FAILED, see status). 202: still PENDING/PROCESSING, "
                    + "poll GET /api/transfers/{reference}. 200: replay of an earlier request with the same key.")
    public ResponseEntity<TransferResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @Valid @RequestBody CreateTransferRequest request,
                                                   Authentication auth) {
        var result = service.create(request, idempotencyKey, Caller.from(auth), Channel.API, null);
        TransferResponse body = result.transfer();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{reference}")
                .buildAndExpand(body.reference()).toUri();
        HttpStatus status = result.replayed() ? HttpStatus.OK
                : body.status().isTerminal() ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).location(location).body(body);
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Transfer status (customers: own transfers only)")
    public TransferResponse get(@PathVariable String reference, Authentication auth) {
        return service.get(reference, Caller.from(auth));
    }

    @GetMapping("/{reference}/audit")
    @Operation(summary = "Saga internals and full state history (SUPPORT/ADMIN)")
    public TransferAudit audit(@PathVariable String reference) {
        return service.audit(reference);
    }
}
