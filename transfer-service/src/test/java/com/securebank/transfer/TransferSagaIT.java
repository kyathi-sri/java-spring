package com.securebank.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transfer.domain.Channel;
import com.securebank.transfer.domain.TransferStatus;
import com.securebank.transfer.dto.TransferDtos.CreateTransferRequest;
import com.securebank.transfer.dto.TransferDtos.TransferResponse;
import com.securebank.transfer.messaging.OutboxRelay;
import com.securebank.transfer.repository.OutboxEventRepository;
import com.securebank.transfer.repository.TransferRepository;
import com.securebank.transfer.security.Caller;
import com.securebank.transfer.service.TransferRecoveryJob;
import com.securebank.transfer.service.TransferService;
import com.securebank.transfer.support.FakeAccounts;
import com.securebank.transfer.support.FakeDownstreams;
import com.securebank.transfer.support.FakeLedger;
import com.securebank.transfer.support.Fault;
import com.securebank.transfer.support.SimulatedCrash;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saga behaviour against real PostgreSQL and Kafka, with fault-injecting fakes for
 * account-service and transaction-service. Schedulers are disabled: tests drive recovery and the
 * outbox relay explicitly, which makes crash/recovery scenarios deterministic.
 */
@SpringBootTest(properties = {
        "securebank.security.jwt-secret=test-only-secret-test-only-secret-1234567890",
        "securebank.transfer.recovery.enabled=false",
        "securebank.outbox.enabled=false",
        "securebank.transfer.retry-base-delay=0s",
        "securebank.transfer.lease-duration=30s"
})
@AutoConfigureMockMvc
@Testcontainers
@Import(FakeDownstreams.class)
class TransferSagaIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired TransferService transfers;
    @Autowired TransferRecoveryJob recovery;
    @Autowired OutboxRelay relay;
    @Autowired TransferRepository repository;
    @Autowired OutboxEventRepository outbox;
    @Autowired FakeAccounts accounts;
    @Autowired FakeLedger ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;

    @BeforeEach
    void resetFaults() {
        accounts.faults.clear();
        ledger.faults.clear();
    }

    static RequestPostProcessor customer(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    static String key() {
        return "key-" + UUID.randomUUID();
    }

    TransferResponse transfer(String owner, UUID from, UUID to, String amount) {
        return transfers.create(new CreateTransferRequest(from, to, new BigDecimal(amount), null, "Rent"), key(),
                Caller.customer(owner), Channel.API, null).transfer();
    }

    TransferResponse reload(String reference) {
        return TransferResponse.from(repository.findByReference(reference).orElseThrow());
    }

    /** Runs recovery until nothing is due (bounded), like the scheduler would over time. */
    void recoverUntilIdle() {
        for (int i = 0; i < 20 && recovery.runOnce() > 0; i++) {
            // keep going
        }
    }

    // ================================================================== happy path & API

    @Nested
    @DisplayName("Lifecycle and API")
    class Lifecycle {

        @Test
        @DisplayName("POST completes the transfer: 201, balances moved once, ledger balanced, full history")
        void happyPath() throws Exception {
            UUID from = accounts.open("alice", "500.00");
            UUID to = accounts.open("bob", "0.00");
            String body = """
                    {"sourceAccountId":"%s","beneficiaryAccountId":"%s","amount":120.50,"description":"Rent March"}
                    """.formatted(from, to);

            String json = mvc.perform(post("/api/transfers").with(customer("alice")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.currency").value("SGD"))
                    .andReturn().getResponse().getContentAsString();
            String reference = mapper.readTree(json).get("reference").asText();

            assertThat(accounts.balance(from)).isEqualByComparingTo("379.50");
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
            assertThat(accounts.balance(to)).isEqualByComparingTo("120.50");
            assertThat(accounts.holdStatus(reference)).isEqualTo("CAPTURED");
            assertThat(ledger.entriesFor(reference)).hasSize(1);
            assertThat(ledger.balance(from)).isEqualByComparingTo("-120.50");
            assertThat(ledger.balance(to)).isEqualByComparingTo("120.50");

            mvc.perform(get("/api/transfers/{ref}", reference).with(customer("alice")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
            mvc.perform(get("/api/transfers/{ref}/audit", reference)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPORT"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.step").value("COMPLETED"))
                    .andExpect(jsonPath("$.history[*].toStep").value(org.hamcrest.Matchers.contains(
                            "CREATED", "CREATED", "FUNDS_RESERVED", "LEDGER_POSTED", "BENEFICIARY_CREDITED",
                            "COMPLETED")));

            assertThat(outbox.findByAggregateIdOrderByIdAsc(repository.findByReference(reference).orElseThrow().getId()))
                    .extracting(e -> e.getEventType())
                    .containsExactly("TRANSFER_INITIATED", "TRANSFER_COMPLETED");
        }

        @Test
        @DisplayName("same Idempotency-Key + same body is a 200 replay; different body is 409; missing key is 400")
        void idempotency() throws Exception {
            UUID from = accounts.open("carol", "100.00");
            UUID to = accounts.open("dan", "0.00");
            String k = key();
            String body = "{\"sourceAccountId\":\"%s\",\"beneficiaryAccountId\":\"%s\",\"amount\":10}".formatted(from, to);

            String first = mvc.perform(post("/api/transfers").with(customer("carol")).header("Idempotency-Key", k)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String second = mvc.perform(post("/api/transfers").with(customer("carol")).header("Idempotency-Key", k)
                            .contentType(MediaType.APPLICATION_JSON).content(body.replace("10}", "10.00}")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(second).get("reference")).isEqualTo(mapper.readTree(first).get("reference"));
            assertThat(accounts.balance(from)).isEqualByComparingTo("90.00");

            mvc.perform(post("/api/transfers").with(customer("carol")).header("Idempotency-Key", k)
                            .contentType(MediaType.APPLICATION_JSON).content(body.replace("10}", "11}")))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
            mvc.perform(post("/api/transfers").with(customer("carol"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MISSING_HEADER"));
        }

        @Test
        @DisplayName("insufficient funds: 201 with status FAILED, nothing reserved, nothing posted")
        void insufficientFunds() throws Exception {
            UUID from = accounts.open("erin", "50.00");
            UUID to = accounts.open("fred", "0.00");
            String body = "{\"sourceAccountId\":\"%s\",\"beneficiaryAccountId\":\"%s\",\"amount\":50.01}".formatted(from, to);

            String json = mvc.perform(post("/api/transfers").with(customer("erin")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("FAILED"))
                    .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_FUNDS"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(ledger.entriesFor(mapper.readTree(json).get("reference").asText())).isEmpty();
            assertThat(accounts.balance(from)).isEqualByComparingTo("50.00");
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("validation: foreign source 404, unknown beneficiary 422, same account 400, bad amount 400")
        void validation() throws Exception {
            UUID alice = accounts.open("alice2", "100.00");
            UUID bob = accounts.open("bob2", "100.00");
            String tpl = "{\"sourceAccountId\":\"%s\",\"beneficiaryAccountId\":\"%s\",\"amount\":%s}";

            mvc.perform(post("/api/transfers").with(customer("mallory")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(tpl.formatted(alice, bob, "5")))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
            mvc.perform(post("/api/transfers").with(customer("alice2")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(tpl.formatted(alice, UUID.randomUUID(), "5")))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("BENEFICIARY_NOT_FOUND"));
            mvc.perform(post("/api/transfers").with(customer("alice2")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(tpl.formatted(alice, alice, "5")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("SAME_ACCOUNT"));
            mvc.perform(post("/api/transfers").with(customer("alice2")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(tpl.formatted(alice, bob, "0.001")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
            mvc.perform(get("/api/transfers/{ref}", "TRF-unknown").with(customer("alice2")))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/transfers").header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(tpl.formatted(alice, bob, "5")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("account-service down during validation: 503, no transfer created, retry with same key works")
        void dependencyDownAtValidation() {
            UUID from = accounts.open("gail", "100.00");
            UUID to = accounts.open("hank", "0.00");
            String k = key();
            CreateTransferRequest req = new CreateTransferRequest(from, to, new BigDecimal("5.00"), null, null);
            accounts.faults.inject(FakeAccounts.Op.GET_ACCOUNT, Fault.TIMEOUT_NOT_APPLIED, 1);

            assertThatThrownBy(() -> transfers.create(req, k, Caller.customer("gail"), Channel.API, null))
                    .hasMessageContaining("temporarily unavailable");
            assertThat(repository.findByInitiatedByAndIdempotencyKey("gail", k)).isEmpty();

            assertThat(transfers.create(req, k, Caller.customer("gail"), Channel.API, null).transfer().status())
                    .isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        @DisplayName("customers cannot see other customers' transfers")
        void visibility() throws Exception {
            UUID from = accounts.open("ivan", "100.00");
            UUID to = accounts.open("judy", "0.00");
            TransferResponse t = transfer("ivan", from, to, "1.00");

            mvc.perform(get("/api/transfers/{ref}", t.reference()).with(customer("judy")))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/transfers/{ref}/audit", t.reference()).with(customer("ivan")))
                    .andExpect(status().isForbidden());
        }
    }

    // ================================================================== concurrency

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("20 concurrent requests with the same Idempotency-Key create exactly one transfer")
        void sameKeyConcurrently() throws Exception {
            UUID from = accounts.open("kate", "1000.00");
            UUID to = accounts.open("leo", "0.00");
            String k = key();
            CreateTransferRequest req = new CreateTransferRequest(from, to, new BigDecimal("75.00"), null, null);

            List<TransferService.CreateResult> results = runConcurrently(20,
                    () -> transfers.create(req, k, Caller.customer("kate"), Channel.API, null));

            assertThat(results).extracting(r -> r.transfer().reference()).containsOnly(results.get(0).transfer().reference());
            assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
            String reference = results.get(0).transfer().reference();
            assertThat(reload(reference).status()).isEqualTo(TransferStatus.COMPLETED);
            Integer rows = jdbc.queryForObject("select count(*) from transfers where idempotency_key = ?",
                    Integer.class, k);
            assertThat(rows).isEqualTo(1);
            assertThat(accounts.balance(from)).isEqualByComparingTo("925.00");
            assertThat(accounts.balance(to)).isEqualByComparingTo("75.00");
            assertThat(ledger.entriesFor(reference)).hasSize(1);
        }

        @Test
        @DisplayName("10 concurrent transfers of 30 from a balance of 100: exactly 3 complete, no overdraft")
        void noDoubleSpend() throws Exception {
            UUID from = accounts.open("mia", "100.00");
            UUID to = accounts.open("ned", "0.00");

            List<TransferService.CreateResult> results = runConcurrently(10, () -> transfers.create(
                    new CreateTransferRequest(from, to, new BigDecimal("30.00"), null, null), key(),
                    Caller.customer("mia"), Channel.API, null));

            assertThat(results).filteredOn(r -> r.transfer().status() == TransferStatus.COMPLETED).hasSize(3);
            assertThat(results).filteredOn(r -> r.transfer().status() == TransferStatus.FAILED)
                    .hasSize(7)
                    .allSatisfy(r -> assertThat(r.transfer().failureCode()).isEqualTo("INSUFFICIENT_FUNDS"));
            assertThat(accounts.balance(from)).isEqualByComparingTo("10.00");
            assertThat(accounts.balance(to)).isEqualByComparingTo("90.00");
            assertThat(ledger.balance(to)).isEqualByComparingTo("90.00");
        }
    }

    // ================================================================== timeouts: never assume failure

    @Nested
    @DisplayName("Timeouts and unknown outcomes")
    class Timeouts {

        @Test
        @DisplayName("reserve timed out but was applied: status check finds the hold, transfer completes, one hold")
        void reserveAppliedDespiteTimeout() {
            UUID from = accounts.open("olga", "100.00");
            UUID to = accounts.open("pete", "0.00");
            accounts.faults.inject(FakeAccounts.Op.PLACE_HOLD, Fault.TIMEOUT_APPLIED, 1);

            TransferResponse t = transfer("olga", from, to, "40.00");

            assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(accounts.faults.calls(FakeAccounts.Op.FIND_HOLD)).isEqualTo(1);
            assertThat(accounts.balance(from)).isEqualByComparingTo("60.00");
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("ledger post timed out but was applied: entry found by reference, never posted twice")
        void ledgerAppliedDespiteTimeout() {
            UUID from = accounts.open("quinn", "100.00");
            UUID to = accounts.open("rosa", "0.00");
            ledger.faults.inject(FakeLedger.Op.POST, Fault.TIMEOUT_APPLIED, 1);

            TransferResponse t = transfer("quinn", from, to, "25.00");

            assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(ledger.faults.calls(FakeLedger.Op.POST)).isEqualTo(1); // no blind re-post
            assertThat(ledger.entriesFor(t.reference())).hasSize(1);
            assertThat(accounts.balance(to)).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("credit timed out but was applied: credit found by reference, beneficiary credited once")
        void creditAppliedDespiteTimeout() {
            UUID from = accounts.open("sam", "100.00");
            UUID to = accounts.open("tina", "0.00");
            accounts.faults.inject(FakeAccounts.Op.CREDIT, Fault.TIMEOUT_APPLIED, 1);

            TransferResponse t = transfer("sam", from, to, "33.00");

            assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(accounts.balance(to)).isEqualByComparingTo("33.00");
            assertThat(accounts.creditCount(t.reference())).isEqualTo(1);
        }

        @Test
        @DisplayName("ledger unreachable: transfer stays PROCESSING (202), funds held, completes once it recovers")
        void outageThenRecovery() throws Exception {
            UUID from = accounts.open("uma", "100.00");
            UUID to = accounts.open("vic", "0.00");
            ledger.faults.inject(FakeLedger.Op.POST, Fault.TIMEOUT_NOT_APPLIED, 3);
            ledger.faults.inject(FakeLedger.Op.FIND, Fault.TIMEOUT_NOT_APPLIED, 3);
            String body = "{\"sourceAccountId\":\"%s\",\"beneficiaryAccountId\":\"%s\",\"amount\":20}".formatted(from, to);

            String json = mvc.perform(post("/api/transfers").with(customer("uma")).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PROCESSING"))
                    .andReturn().getResponse().getContentAsString();
            String reference = mapper.readTree(json).get("reference").asText();
            assertThat(accounts.reserved(from)).isEqualByComparingTo("20.00"); // ring-fenced meanwhile

            recoverUntilIdle();

            assertThat(reload(reference).status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(ledger.entriesFor(reference)).hasSize(1);
            assertThat(accounts.balance(from)).isEqualByComparingTo("80.00");
            Integer retries = jdbc.queryForObject("select count(*) from transfer_history h join transfers t "
                    + "on t.id = h.transfer_id where t.reference = ? and h.detail like 'Retry%'", Integer.class, reference);
            assertThat(retries).isEqualTo(3);
        }

        @Test
        @DisplayName("reservation never confirmed before deadline: hold cancelled via tombstone, FAILED, late reserve refused")
        void reservationDeadline() {
            UUID from = accounts.open("wendy", "100.00");
            UUID to = accounts.open("xavier", "0.00");
            accounts.faults.inject(FakeAccounts.Op.PLACE_HOLD, Fault.TIMEOUT_NOT_APPLIED, 1);
            accounts.faults.inject(FakeAccounts.Op.FIND_HOLD, Fault.TIMEOUT_NOT_APPLIED, 1);

            TransferResponse t = transfer("wendy", from, to, "60.00");
            assertThat(t.status()).isEqualTo(TransferStatus.PROCESSING);

            jdbc.update("update transfers set reserve_deadline = now() - interval '1 second' where reference = ?",
                    t.reference());
            recoverUntilIdle();

            TransferResponse after = reload(t.reference());
            assertThat(after.status()).isEqualTo(TransferStatus.FAILED);
            assertThat(after.failureCode()).isEqualTo("RESERVATION_TIMEOUT");
            assertThat(accounts.holdStatus(t.reference())).isEqualTo("RELEASED");

            // The original reserve request was "still in flight" and now arrives: it must be refused.
            assertThat(accounts.lateReserve(from, t.reference(), new BigDecimal("60.00")))
                    .isInstanceOf(com.securebank.transfer.client.RemoteResult.Rejected.class);
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
            assertThat(accounts.balance(from)).isEqualByComparingTo("100.00");
        }
    }

    // ================================================================== crashes

    @Nested
    @DisplayName("Crash recovery")
    class Crashes {

        @Test
        @DisplayName("worker dies after the beneficiary was credited but before recording it: resumes, credits once")
        void crashAfterCredit() {
            UUID from = accounts.open("yara", "100.00");
            UUID to = accounts.open("zack", "0.00");
            accounts.faults.inject(FakeAccounts.Op.CREDIT, Fault.CRASH_AFTER_APPLY, 1);
            CreateTransferRequest req = new CreateTransferRequest(from, to, new BigDecimal("45.00"), null, null);
            String k = key();

            assertThatThrownBy(() -> transfers.create(req, k, Caller.customer("yara"), Channel.API, null))
                    .isInstanceOf(SimulatedCrash.class);
            var stuck = repository.findByInitiatedByAndIdempotencyKey("yara", k).orElseThrow();
            assertThat(stuck.getStep().name()).isEqualTo("LEDGER_POSTED");
            assertThat(stuck.getLeaseUntil()).isNotNull(); // the dead worker still "owns" it

            assertThat(recovery.runOnce()).isZero(); // lease still live: nobody may touch it

            jdbc.update("update transfers set lease_until = now() - interval '1 second' where id = ?", stuck.getId());
            recoverUntilIdle();

            assertThat(reload(stuck.getReference()).status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(accounts.balance(to)).isEqualByComparingTo("45.00");      // credited exactly once
            assertThat(accounts.balance(from)).isEqualByComparingTo("55.00");
            assertThat(ledger.entriesFor(stuck.getReference())).hasSize(1);
        }

        @Test
        @DisplayName("crash right after creation (before any step): recovery picks it up from PENDING")
        void crashAfterCreate() {
            UUID from = accounts.open("abe", "100.00");
            UUID to = accounts.open("bea", "0.00");
            accounts.faults.inject(FakeAccounts.Op.PLACE_HOLD, Fault.CRASH_AFTER_APPLY, 1);
            CreateTransferRequest req = new CreateTransferRequest(from, to, new BigDecimal("10.00"), null, null);
            String k = key();

            assertThatThrownBy(() -> transfers.create(req, k, Caller.customer("abe"), Channel.API, null))
                    .isInstanceOf(SimulatedCrash.class);
            var stuck = repository.findByInitiatedByAndIdempotencyKey("abe", k).orElseThrow();
            jdbc.update("update transfers set lease_until = null where id = ?", stuck.getId());

            recoverUntilIdle();

            assertThat(reload(stuck.getReference()).status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0"); // the hold placed before the crash was reused
            assertThat(accounts.balance(from)).isEqualByComparingTo("90.00");
        }
    }

    // ================================================================== compensation

    @Nested
    @DisplayName("Compensation")
    class Compensation {

        @Test
        @DisplayName("beneficiary rejects credit: ledger reversed (original kept), hold released, FAILED")
        void beneficiaryRejects() {
            UUID from = accounts.open("cleo", "100.00");
            UUID to = accounts.open("dave", "0.00");
            accounts.faults.inject(FakeAccounts.Op.CREDIT, Fault.REJECT, 1);

            TransferResponse t = transfer("cleo", from, to, "70.00");

            assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
            assertThat(t.failureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
            assertThat(ledger.entriesFor(t.reference())).extracting(FakeLedger.Entry::type)
                    .containsExactly("TRANSFER", "REVERSAL");
            assertThat(ledger.balance(from)).isEqualByComparingTo("0");
            assertThat(ledger.balance(to)).isEqualByComparingTo("0");
            assertThat(accounts.holdStatus(t.reference())).isEqualTo("RELEASED");
            assertThat(accounts.balance(from)).isEqualByComparingTo("100.00");
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
            assertThat(accounts.balance(to)).isEqualByComparingTo("0.00");

            var id = repository.findByReference(t.reference()).orElseThrow().getId();
            assertThat(outbox.findByAggregateIdOrderByIdAsc(id)).extracting(e -> e.getEventType())
                    .containsExactly("TRANSFER_INITIATED", "TRANSFER_FAILED");
        }

        @Test
        @DisplayName("ledger rejects posting: hold released, FAILED, nothing to reverse")
        void ledgerRejects() {
            UUID from = accounts.open("eve", "100.00");
            UUID to = accounts.open("finn", "0.00");
            ledger.faults.inject(FakeLedger.Op.POST, Fault.REJECT, 1);

            TransferResponse t = transfer("eve", from, to, "10.00");

            assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
            assertThat(ledger.entriesFor(t.reference())).isEmpty();
            assertThat(ledger.faults.calls(FakeLedger.Op.REVERSE)).isZero();
            assertThat(accounts.holdStatus(t.reference())).isEqualTo("RELEASED");
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("compensation steps that time out after applying still end FAILED with exactly one reversal")
        void compensationUnderTimeouts() {
            UUID from = accounts.open("gus", "100.00");
            UUID to = accounts.open("hal", "0.00");
            accounts.faults.inject(FakeAccounts.Op.CREDIT, Fault.REJECT, 1);
            ledger.faults.inject(FakeLedger.Op.REVERSE, Fault.TIMEOUT_APPLIED, 1);
            accounts.faults.inject(FakeAccounts.Op.RELEASE, Fault.TIMEOUT_APPLIED, 1);

            TransferResponse t = transfer("gus", from, to, "30.00");

            assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
            assertThat(ledger.entriesFor(t.reference())).hasSize(2);
            assertThat(ledger.faults.calls(FakeLedger.Op.REVERSE)).isEqualTo(1);
            assertThat(accounts.reserved(from)).isEqualByComparingTo("0");
            assertThat(accounts.balance(from)).isEqualByComparingTo("100.00");
        }
    }

    // ================================================================== Kafka: outbox and duplicate commands

    @Nested
    @DisplayName("Kafka")
    class Messaging {

        @Test
        @DisplayName("outbox events reach Kafka in order with eventId headers, and are not re-sent once published")
        void outboxPublishes() {
            UUID from = accounts.open("ian", "100.00");
            UUID to = accounts.open("jo", "0.00");
            TransferResponse t = transfer("ian", from, to, "5.00");

            while (relay.publishPending() > 0) {
                // drain everything pending (other tests also left events)
            }
            assertThat(outbox.countByPublishedAtIsNull()).isZero();
            assertThat(relay.publishPending()).isZero();

            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            try (KafkaConsumer<String, String> consumer = testConsumer()) {
                consumer.subscribe(List.of("securebank.transfer.events"));
                await().atMost(Duration.ofSeconds(30)).until(() -> {
                    consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                        if (t.reference().equals(r.key())) {
                            received.add(r);
                        }
                    });
                    return received.size() >= 2;
                });
            }
            assertThat(received).extracting(r -> header(r, "eventType"))
                    .containsExactly("TRANSFER_INITIATED", "TRANSFER_COMPLETED");
            assertThat(received).extracting(r -> header(r, "eventId")).doesNotHaveDuplicates();
            JsonNode completed = readJson(received.get(1).value());
            assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(completed.get("amount").decimalValue()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("the same transfer command delivered 3 times creates exactly one transfer")
        void duplicateCommands() throws Exception {
            UUID from = accounts.open("kim", "100.00");
            UUID to = accounts.open("lou", "0.00");
            String commandId = "cmd-" + UUID.randomUUID();
            String k = key();
            String command = mapper.writeValueAsString(Map.of(
                    "commandId", commandId, "customerId", "kim", "idempotencyKey", k,
                    "sourceAccountId", from.toString(), "beneficiaryAccountId", to.toString(),
                    "amount", new BigDecimal("12.00"), "description", "Standing order"));

            for (int i = 0; i < 3; i++) {
                kafka.send("securebank.transfer.commands", k, command).get();
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var t = repository.findByInitiatedByAndIdempotencyKey("kim", k);
                assertThat(t).isPresent();
                assertThat(t.get().getStatus()).isEqualTo(TransferStatus.COMPLETED);
            });
            Thread.sleep(1500); // give the duplicates time to be (not) processed
            Integer rows = jdbc.queryForObject("select count(*) from transfers where idempotency_key = ?",
                    Integer.class, k);
            assertThat(rows).isEqualTo(1);
            assertThat(accounts.balance(from)).isEqualByComparingTo("88.00");
            Integer inbox = jdbc.queryForObject("select count(*) from processed_messages where message_id = ?",
                    Integer.class, commandId);
            assertThat(inbox).isEqualTo(1);
        }

        @Test
        @DisplayName("a command for an account the customer does not own is rejected and recorded, not retried")
        void rejectedCommand() throws Exception {
            UUID from = accounts.open("max", "100.00");
            UUID to = accounts.open("nia", "0.00");
            String commandId = "cmd-" + UUID.randomUUID();
            String command = mapper.writeValueAsString(Map.of(
                    "commandId", commandId, "customerId", "intruder", "idempotencyKey", key(),
                    "sourceAccountId", from.toString(), "beneficiaryAccountId", to.toString(),
                    "amount", new BigDecimal("12.00")));

            kafka.send("securebank.transfer.commands", commandId, command).get();

            await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                    "select count(*) from processed_messages where message_id = ?", Integer.class, commandId) == 1);
            assertThat(jdbc.queryForObject("select outcome from processed_messages where message_id = ?",
                    String.class, commandId)).isEqualTo("REJECTED:ACCOUNT_NOT_FOUND");
            assertThat(accounts.balance(from)).isEqualByComparingTo("100.00");
        }

        private KafkaConsumer<String, String> testConsumer() {
            return new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        }

        private static String header(ConsumerRecord<String, String> r, String name) {
            return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
        }

        private JsonNode readJson(String s) {
            try {
                return mapper.readTree(s);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ================================================================== helpers

    static <T> List<T> runConcurrently(int n, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return task.call();
            }));
        }
        gate.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }
}
