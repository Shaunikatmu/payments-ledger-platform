package com.paymentsledger.paymentservice.api;

import com.paymentsledger.paymentservice.api.dto.AccountResponse;
import com.paymentsledger.paymentservice.api.dto.CreateAccountRequest;
import com.paymentsledger.paymentservice.domain.Account;
import com.paymentsledger.paymentservice.service.AccountService;
import com.paymentsledger.paymentservice.service.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;
    private final IdempotencyService idempotencyService;

    public AccountController(AccountService accountService, IdempotencyService idempotencyService) {
        this.accountService = accountService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreateAccountRequest request) {

        return idempotencyService.execute(idempotencyKey, "/accounts", request, AccountResponse.class,
                () -> {
                    Account account = accountService.createAccount(request);
                    return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
                });
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable("accountId") UUID accountId) {
        Account account = accountService.getAccount(accountId);
        return ResponseEntity.ok(AccountResponse.from(account));
    }
}
