package com.securebank.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.account.domain.HoldStatus;
import com.securebank.account.dto.AccountResponse;
import com.securebank.account.dto.AmountRequest;
import com.securebank.account.dto.CreateAccountRequest;
import com.securebank.account.dto.internal.InternalDtos.CreditRequest;
import com.securebank.account.dto.internal.InternalDtos.HoldRequest;
import com.securebank.account.domain.AccountStatus;
import com.securebank.account.repository.AccountRepository;
import com.securebank.account.security.Caller;
import com.securebank.account.service.AccountService;
import com.securebank.account.service.TransferSupportService;
import com.securebank.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Internal hold/credit API against a real PostgreSQL: idempotency, state rules and double-spend safety. */
@SpringBootTest(properties = "securebank.security.jwt-secret=test-only-secret-test-only-secret-1234567890")
@AutoConfigureMockMvc
@Testcontainers
class HoldsApiIT {

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
    @Autowired AccountService accountService;
    @Autowired TransferSupportService support;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    static final Caller SERVICE = new Caller("transfer-service", false, false, true);

    static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("transfer-service")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private UUID openAccount(String owner, String opening) {
        Caller customer = new Caller(owner, false, false);
        AccountResponse a = accountService.createAccount(new CreateAccountRequest("Test Customer", null, null), customer);
        if (new BigDecimal(opening).signum() > 0) {
            accountService.deposit(a.id(), new AmountRequest(new BigDecimal(opening), "SEED"), customer);
        }
        return a.id();
    }

    private static String holdJson(String ref, String amount) {
        return "{\"reference\":\"%s\",\"amount\":%s,\"currency\":\"SGD\"}".formatted(ref, amount);
    }

    private static String ref() {
        return "TRF-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("hold reduces available but not current balance; retry is a 200 replay; different amount is 409")
    void holdLifecycleAndIdempotency() throws Exception {
        UUID id = openAccount("alice", "100.00");
        String ref = ref();

        mvc.perform(post("/api/internal/accounts/{id}/holds", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(holdJson(ref, "60.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.replayed").value(false));

        mvc.perform(post("/api/internal/accounts/{id}/holds", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(holdJson(ref, "60.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(post("/api/internal/accounts/{id}/holds", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(holdJson(ref, "61.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        // Public balance API (backward compatible) now reflects the hold.
        mvc.perform(get("/api/accounts/{id}/balance", id)
                        .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(jsonPath("$.availableBalance").value(40.00))
                .andExpect(jsonPath("$.currentBalance").value(100.00))
                .andExpect(jsonPath("$.reservedAmount").value(60.00));
    }

    @Test
    @DisplayName("held funds cannot be withdrawn or reserved again (no double spending)")
    void holdsBlockSpending() throws Exception {
        UUID id = openAccount("bob", "100.00");
        support.placeHold(id, new HoldRequest(ref(), new BigDecimal("80.00"), "SGD"), SERVICE);

        mvc.perform(post("/api/accounts/{id}/withdraw", id)
                        .with(jwt().jwt(j -> j.subject("bob")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        mvc.perform(post("/api/internal/accounts/{id}/holds", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(holdJson(ref(), "20.01")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("capture debits once; capture is idempotent; release after capture is refused")
    void capture() throws Exception {
        UUID id = openAccount("carol", "100.00");
        String ref = ref();
        support.placeHold(id, new HoldRequest(ref, new BigDecimal("25.00"), "SGD"), SERVICE);

        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/capture", id, ref).with(service()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/capture", id, ref).with(service()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/release", id, ref).with(service()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("HOLD_STATE_CONFLICT"));

        var account = accounts.findById(id).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo("75.00");
        assertThat(account.getReservedBalance()).isEqualByComparingTo("0.00");
        Integer debits = jdbc.queryForObject("select count(*) from account_audit where account_id = ? "
                + "and operation = 'TRANSFER_DEBIT'", Integer.class, id);
        assertThat(debits).isEqualTo(1);
    }

    @Test
    @DisplayName("release restores available funds; release is idempotent; capture after release is refused")
    void release() throws Exception {
        UUID id = openAccount("dave", "50.00");
        String ref = ref();
        support.placeHold(id, new HoldRequest(ref, new BigDecimal("50.00"), "SGD"), SERVICE);

        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/release", id, ref).with(service()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/release", id, ref).with(service()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/capture", id, ref).with(service()))
                .andExpect(status().isConflict());

        var account = accounts.findById(id).orElseThrow();
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("50.00");
        assertThat(account.getBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("release before reserve writes a tombstone; the delayed reserve is then refused")
    void tombstoneBlocksLateReserve() throws Exception {
        UUID id = openAccount("erin", "100.00");
        String ref = ref();

        mvc.perform(post("/api/internal/accounts/{id}/holds/{ref}/release", id, ref).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.tombstone").value(true));

        mvc.perform(post("/api/internal/accounts/{id}/holds", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(holdJson(ref, "10.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("HOLD_STATE_CONFLICT"));

        assertThat(accounts.findById(id).orElseThrow().getReservedBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("credits are idempotent and refused on CLOSED accounts")
    void credits() throws Exception {
        UUID id = openAccount("frank", "0");
        String ref = ref();
        String body = "{\"reference\":\"%s\",\"amount\":15.00,\"currency\":\"SGD\"}".formatted(ref);

        mvc.perform(post("/api/internal/accounts/{id}/credits", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/internal/accounts/{id}/credits", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(get("/api/internal/accounts/{id}/credits/{ref}", id, ref).with(service()))
                .andExpect(status().isOk());
        assertThat(accounts.findById(id).orElseThrow().getBalance()).isEqualByComparingTo("15.00");

        jdbc.update("update accounts set status = 'CLOSED' where id = ?", id);
        mvc.perform(post("/api/internal/accounts/{id}/credits", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace(ref, ref())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_ACTIVE"));
        // ...but the already-applied credit still replays successfully (replay precedes rule checks).
        mvc.perform(post("/api/internal/accounts/{id}/credits", id).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("summary exposes owner and status; internal API is SERVICE-only")
    void summaryAndSecurity() throws Exception {
        UUID id = openAccount("gina", "0");
        mvc.perform(get("/api/internal/accounts/{id}", id).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("gina"))
                .andExpect(jsonPath("$.status").value(AccountStatus.ACTIVE.name()));
        mvc.perform(get("/api/internal/accounts/{id}", id)
                        .with(jwt().jwt(j -> j.subject("gina")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/internal/accounts/{id}/holds/{ref}", id, "NOPE").with(service()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("HOLD_NOT_FOUND"));
    }

    @Test
    @DisplayName("25 concurrent holds of 10 against 100: exactly 10 succeed, reserved never exceeds balance")
    void concurrentHolds() throws Exception {
        UUID id = openAccount("henry", "100.00");
        int ok = runConcurrently(25, i -> () -> {
            support.placeHold(id, new HoldRequest(ref(), new BigDecimal("10.00"), "SGD"), SERVICE);
            return true;
        });

        assertThat(ok).isEqualTo(10);
        var account = accounts.findById(id).orElseThrow();
        assertThat(account.getReservedBalance()).isEqualByComparingTo("100.00");
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("concurrent holds and withdrawals together never spend more than the balance")
    void concurrentHoldsAndWithdrawals() throws Exception {
        UUID id = openAccount("ivy", "100.00");
        Caller ivy = new Caller("ivy", false, false);
        int ok = runConcurrently(30, i -> () -> {
            if (i % 2 == 0) {
                support.placeHold(id, new HoldRequest(ref(), new BigDecimal("10.00"), "SGD"), SERVICE);
            } else {
                accountService.withdraw(id, new AmountRequest(new BigDecimal("10.00"), null), ivy);
            }
            return true;
        });

        assertThat(ok).isEqualTo(10);
        var account = accounts.findById(id).orElseThrow();
        assertThat(account.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThat(account.getBalance().add(account.getReservedBalance().negate()).signum()).isZero();
    }

    @Test
    @DisplayName("20 concurrent retries of the same hold reference reserve the money exactly once")
    void concurrentSameReference() throws Exception {
        UUID id = openAccount("jack", "100.00");
        String ref = ref();
        int ok = runConcurrently(20, i -> () -> {
            support.placeHold(id, new HoldRequest(ref, new BigDecimal("40.00"), "SGD"), SERVICE);
            return true;
        });

        assertThat(ok).isEqualTo(20); // all succeed: one created, the rest are replays
        assertThat(accounts.findById(id).orElseThrow().getReservedBalance()).isEqualByComparingTo("40.00");
        Integer holds = jdbc.queryForObject("select count(*) from account_holds where reference = ?", Integer.class, ref);
        assertThat(holds).isEqualTo(1);
        assertThat(support.getHold(id, ref).status()).isEqualTo(HoldStatus.HELD);
    }

    /** Releases all tasks at once; returns how many completed without a business rejection. */
    private static int runConcurrently(int n, java.util.function.IntFunction<Callable<Boolean>> taskFactory)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Boolean> task = taskFactory.apply(i);
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    return task.call();
                } catch (BusinessException rejected) {
                    return false;
                }
            }));
        }
        gate.countDown();
        int ok = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) ok++;
        }
        pool.shutdown();
        return ok;
    }
}
