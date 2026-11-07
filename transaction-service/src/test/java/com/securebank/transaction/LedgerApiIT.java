package com.securebank.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transaction.domain.Direction;
import com.securebank.transaction.domain.EntryType;
import com.securebank.transaction.dto.EntryResponse;
import com.securebank.transaction.dto.PostEntryRequest;
import com.securebank.transaction.security.Caller;
import com.securebank.transaction.service.LedgerService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "securebank.security.jwt-secret=test-only-secret-test-only-secret-1234567890")
@AutoConfigureMockMvc
@Testcontainers
class LedgerApiIT {

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
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    static final Caller SERVICE = new Caller("transfer-service", false, false, true);

    static RequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** Deposit into a customer account: DEBIT bank cash, CREDIT the customer (liability). */
    static String depositJson(String reference, String account, String owner, String amount) {
        return """
                {"reference":"%s","entryType":"DEPOSIT","description":"Cash deposit","lines":[
                  {"accountRef":"CASH-SGD","direction":"DEBIT","amount":%s,"currency":"SGD"},
                  {"accountRef":"%s","ownerId":"%s","direction":"CREDIT","amount":%s,"currency":"SGD"}]}
                """.formatted(reference, amount, account, owner, amount);
    }

    static PostEntryRequest transfer(String reference, String from, String fromOwner, String to, String toOwner,
                                     String amount) {
        return new PostEntryRequest(reference, EntryType.TRANSFER, "Transfer", List.of(
                new PostEntryRequest.Line(from, fromOwner, Direction.DEBIT, new BigDecimal(amount), "SGD"),
                new PostEntryRequest.Line(to, toOwner, Direction.CREDIT, new BigDecimal(amount), "SGD")));
    }

    static String uniqueAccount() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("post returns 201, identical retry returns 200 with the same entry, balance counted once")
    void idempotentPosting() throws Exception {
        String account = uniqueAccount();
        String ref = "DEP-" + UUID.randomUUID();

        String first = mvc.perform(post("/api/transactions").with(as("transfer-service", "SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content(depositJson(ref, account, "alice", "250.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(post("/api/transactions").with(as("transfer-service", "SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content(depositJson(ref, account, "alice", "250.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readTree(second).get("entryId")).isEqualTo(mapper.readTree(first).get("entryId"));
        assertThat(ledger.balance(account, null, SERVICE).balance()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("same reference with a different payload is rejected with 409")
    void idempotencyConflict() throws Exception {
        String account = uniqueAccount();
        String ref = "DEP-" + UUID.randomUUID();
        mvc.perform(post("/api/transactions").with(as("svc", "SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content(depositJson(ref, account, "alice", "10.00")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/transactions").with(as("svc", "SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content(depositJson(ref, account, "alice", "99.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("unbalanced posting is rejected with 422")
    void unbalanced() throws Exception {
        String body = """
                {"reference":"BAD-%s","entryType":"ADJUSTMENT","lines":[
                  {"accountRef":"CASH-SGD","direction":"DEBIT","amount":10.00,"currency":"SGD"},
                  {"accountRef":"X1","direction":"CREDIT","amount":9.99,"currency":"SGD"}]}
                """.formatted(UUID.randomUUID());
        mvc.perform(post("/api/transactions").with(as("svc", "SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UNBALANCED_ENTRY"));
    }

    @Test
    @DisplayName("20 concurrent retries of the same transfer produce exactly one journal entry")
    void concurrentDuplicates() throws Exception {
        String from = uniqueAccount();
        String to = uniqueAccount();
        String ref = "TRF-" + UUID.randomUUID();
        PostEntryRequest request = transfer(ref, from, "alice", to, "bob", "75.00");

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<EntryResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return ledger.post(request, SERVICE);
            }));
        }
        gate.countDown();

        Set<UUID> ids = new HashSet<>();
        int fresh = 0;
        for (Future<EntryResponse> f : futures) {
            EntryResponse r = f.get();
            ids.add(r.entryId());
            if (!r.replayed()) fresh++;
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(fresh).isEqualTo(1);
        Integer stored = jdbc.queryForObject("select count(*) from journal_entries where reference = ?",
                Integer.class, ref);
        assertThat(stored).isEqualTo(1);
        assertThat(ledger.balance(to, null, SERVICE).balance()).isEqualByComparingTo("75.00");
        assertThat(ledger.balance(from, null, SERVICE).balance()).isEqualByComparingTo("-75.00");
    }

    @Test
    @DisplayName("reversal restores balances, keeps the original, and happens at most once")
    void reversal() throws Exception {
        String from = uniqueAccount();
        String to = uniqueAccount();
        EntryResponse original = ledger.post(transfer("TRF-" + UUID.randomUUID(), from, "alice", to, "bob", "40.00"),
                SERVICE);

        mvc.perform(post("/api/transactions/{id}/reversal", original.entryId()).with(as("ops-admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Sent in error\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesEntryId").value(original.entryId().toString()));

        // Second reversal request is an idempotent replay, not a second reversal.
        mvc.perform(post("/api/transactions/{id}/reversal", original.entryId()).with(as("ops-admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        assertThat(ledger.balance(to, null, SERVICE).balance()).isEqualByComparingTo("0.00");
        assertThat(ledger.balance(from, null, SERVICE).balance()).isEqualByComparingTo("0.00");
        // Original entry is still there, unchanged: history shows the transfer and its reversal.
        assertThat(ledger.history(to, null, null, 0, 10, "asc", SERVICE).content())
                .extracting(v -> v.entryType().name())
                .containsExactly("TRANSFER", "REVERSAL");
        assertThat(ledger.getEntry(original.entryId()).lines()).hasSize(2);
    }

    @Test
    @DisplayName("database refuses UPDATE, DELETE and TRUNCATE on posted ledger rows")
    void appendOnly() {
        EntryResponse e = ledger.post(transfer("TRF-" + UUID.randomUUID(), uniqueAccount(), "alice",
                uniqueAccount(), "bob", "5.00"), SERVICE);

        assertThatThrownBy(() -> jdbc.update("update ledger_lines set amount = 1 where journal_entry_id = ?",
                e.entryId())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from journal_entries where id = ?", e.entryId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate ledger_lines")).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("database rejects an unbalanced entry at commit even if the application is bypassed")
    void balanceTrigger() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update("insert into journal_entries (id, reference, entry_type, created_by, posted_at) "
                    + "values (?, ?, 'ADJUSTMENT', 'rogue', ?)", id, "ROGUE-" + id, now);
            jdbc.update("insert into ledger_lines (journal_entry_id, account_ref, direction, amount, currency, posted_at) "
                    + "values (?, 'X', 'CREDIT', 1000000.00, 'SGD', ?)", id, now);
        })).rootCause().hasMessageContaining("unbalanced"); // deferred trigger fires at COMMIT

        Integer stored = jdbc.queryForObject("select count(*) from journal_entries where id = ?", Integer.class, id);
        assertThat(stored).isZero();
    }

    @Test
    @DisplayName("history paginates, filters by date, and customers only see their own lines")
    void historyAndOwnership() throws Exception {
        String account = uniqueAccount();
        for (int i = 1; i <= 5; i++) {
            mvc.perform(post("/api/transactions").with(as("svc", "SERVICE")).contentType(MediaType.APPLICATION_JSON)
                            .content(depositJson("DEP-" + UUID.randomUUID(), account, "carol", i + "0.00")))
                    .andExpect(status().isCreated());
        }

        String page = mvc.perform(get("/api/transactions/account/{ref}", account).with(as("carol", "CUSTOMER"))
                        .param("page", "0").param("size", "2").param("sort", "asc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(page);
        assertThat(json.get("totalElements").asInt()).isEqualTo(5);
        assertThat(json.get("totalPages").asInt()).isEqualTo(3);
        assertThat(json.get("content")).hasSize(2);
        assertThat(json.get("content").get(0).get("amount").decimalValue()).isEqualByComparingTo("10.00");

        // Another customer sees nothing for Carol's account and a zero balance.
        mvc.perform(get("/api/transactions/account/{ref}", account).with(as("mallory", "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/transactions/account/{ref}/balance", account).with(as("mallory", "CUSTOMER")))
                .andExpect(jsonPath("$.balance").value(0));
        mvc.perform(get("/api/transactions/account/{ref}/balance", account).with(as("carol", "CUSTOMER")))
                .andExpect(jsonPath("$.balance").value(150.00));

        // A window that ends in the past contains nothing.
        mvc.perform(get("/api/transactions/account/{ref}", account).with(as("carol", "CUSTOMER"))
                        .param("to", "2020-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/transactions/account/{ref}", account).with(as("carol", "CUSTOMER"))
                        .param("from", "2030-01-01T00:00:00Z").param("to", "2020-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("lookup by reference finds posted entries and reversals, 404 otherwise, never for customers")
    void lookupByReference() throws Exception {
        String ref = "TRF-" + UUID.randomUUID();
        EntryResponse posted = ledger.post(transfer(ref, uniqueAccount(), "alice", uniqueAccount(), "bob", "12.00"),
                SERVICE);

        mvc.perform(get("/api/transactions/reference/{ref}", ref).with(as("transfer-service", "SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value(posted.entryId().toString()));
        mvc.perform(get("/api/transactions/reference/{ref}", "TRF-missing").with(as("transfer-service", "SERVICE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTRY_NOT_FOUND"));

        ledger.reverse(posted.entryId(), "test", SERVICE);
        mvc.perform(get("/api/transactions/reference/{ref}", "REV-" + ref).with(as("transfer-service", "SERVICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"));

        mvc.perform(get("/api/transactions/reference/{ref}", ref).with(as("alice", "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("role checks: customers cannot post, reverse, read raw entries or run reconciliation")
    void authorization() throws Exception {
        String account = uniqueAccount();
        mvc.perform(post("/api/transactions").with(as("alice", "CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content(depositJson("DEP-" + UUID.randomUUID(), account, "alice", "1000000.00")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/transactions/{id}", UUID.randomUUID()).with(as("alice", "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/transactions/reconciliation/trial-balance").with(as("agent", "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/transactions/account/{ref}", account)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reconciliation: trial balance is healthy and account mismatches are reported")
    void reconciliation() throws Exception {
        String account = uniqueAccount();
        ledger.post(transfer("TRF-" + UUID.randomUUID(), "CASH-SGD", null, account, "dave", "300.00"), SERVICE);

        mvc.perform(get("/api/transactions/reconciliation/trial-balance").with(as("ops-admin", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.unbalancedEntries").value(0));

        String body = """
                {"items":[{"accountRef":"%s","expectedBalance":300.00},
                          {"accountRef":"%s","expectedBalance":310.00}]}
                """.formatted(account, account);
        mvc.perform(post("/api/transactions/reconciliation/accounts").with(as("ops-admin", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checked").value(2))
                .andExpect(jsonPath("$.mismatched").value(1))
                .andExpect(jsonPath("$.results[1].difference").value(10.00));
    }
}
