package com.securebank.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AmountRequest;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.exception.InsufficientFundsException;
import com.securebank.account.repository.AccountAuditRepository;
import com.securebank.account.security.Caller;
import com.securebank.account.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the real app against a real PostgreSQL (Flyway migrations included). */
@SpringBootTest(properties = {
        // Test-only dummy secret; not a real credential.
        "securebank.security.jwt-secret=test-only-secret-test-only-secret-1234567890"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountService service;
    @Autowired AccountAuditRepository audits;

    static RequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String createAccountVia(String subject) throws Exception {
        String body = mvc.perform(post("/api/accounts").with(as(subject, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Alice Tan\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    @Test
    @DisplayName("full flow: create, deposit, withdraw, read balance")
    void fullFlow() throws Exception {
        String id = createAccountVia("alice");

        mvc.perform(post("/api/accounts/{id}/deposit", id).with(as("alice", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.50,\"reference\":\"PAYROLL-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(150.50));

        mvc.perform(post("/api/accounts/{id}/withdraw", id).with(as("alice", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.25}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/accounts/{id}/balance", id).with(as("alice", "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(100.25))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // audit trail reconciles with the balance
        BigDecimal net = audits.findByAccountIdOrderByCreatedAtAsc(UUID.fromString(id)).stream()
                .map(a -> a.getOperation().name().equals("DEPOSIT") ? a.getAmount() : a.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("100.25");
    }

    @Test
    @DisplayName("insufficient funds returns 422 with a safe error body")
    void insufficientFunds() throws Exception {
        String id = createAccountVia("alice");

        mvc.perform(post("/api/accounts/{id}/withdraw", id).with(as("alice", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("rejects zero, negative and over-precise amounts with 400")
    void validation() throws Exception {
        String id = createAccountVia("alice");
        for (String amount : List.of("0", "-5.00", "1.005")) {
            mvc.perform(post("/api/accounts/{id}/deposit", id).with(as("alice", "CUSTOMER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":" + amount + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("401 without token; 403 for SUPPORT writes; 404 for another customer's account")
    void security() throws Exception {
        String id = createAccountVia("alice");

        mvc.perform(get("/api/accounts/{id}", id)).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/accounts/{id}/deposit", id).with(as("agent", "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/accounts/{id}", id).with(as("agent", "SUPPORT")))
                .andExpect(status().isOk());

        mvc.perform(get("/api/accounts/{id}", id).with(as("bob", "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("concurrent withdrawals never overdraw: exactly 10 of 25 succeed")
    void concurrentWithdrawals() throws Exception {
        Caller alice = new Caller("alice-concurrent", false, false);
        AccountResponse account = service.createAccount(new CreateAccountRequest("Alice Tan", null, null), alice);
        service.deposit(account.id(), new AmountRequest(new BigDecimal("100.00"), "SEED"), alice);

        int threads = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                startGate.await();
                try {
                    service.withdraw(account.id(), new AmountRequest(new BigDecimal("10.00"), null), alice);
                    return true;
                } catch (InsufficientFundsException e) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        startGate.countDown(); // release all threads at once to maximise contention

        int succeeded = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) succeeded++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(service.getBalance(account.id(), alice).availableBalance()).isEqualByComparingTo("0.00");

        // seed deposit + 10 successful withdrawals, nothing else
        assertThat(audits.findByAccountIdOrderByCreatedAtAsc(account.id())).hasSize(11);
    }

    @Test
    @DisplayName("error responses never leak internals")
    void safeErrors() throws Exception {
        String body = mvc.perform(get("/api/accounts/{id}", "not-a-uuid").with(as("alice", "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertThat(json.get("error").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(body).doesNotContain("Exception").doesNotContain("java.");
    }
}
