package com.securebank.account.web;

import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AmountRequest;
import com.securebank.account.dto.BalanceResponse;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.security.Caller;
import com.securebank.account.service.AccountService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Open a new account")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                  Authentication auth) {
        AccountResponse created = service.createAccount(request, Caller.from(auth));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get account details")
    public AccountResponse get(@PathVariable UUID id, Authentication auth) {
        return service.getAccount(id, Caller.from(auth));
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "Get available balance")
    public BalanceResponse balance(@PathVariable UUID id, Authentication auth) {
        return service.getBalance(id, Caller.from(auth));
    }

    @PostMapping("/{id}/deposit")
    @Operation(summary = "Deposit funds")
    public BalanceResponse deposit(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                   Authentication auth) {
        return service.deposit(id, request, Caller.from(auth));
    }

    @PostMapping("/{id}/withdraw")
    @Operation(summary = "Withdraw funds (fails with 422 on insufficient funds)")
    public BalanceResponse withdraw(@PathVariable UUID id, @Valid @RequestBody AmountRequest request,
                                    Authentication auth) {
        return service.withdraw(id, request, Caller.from(auth));
    }
}
