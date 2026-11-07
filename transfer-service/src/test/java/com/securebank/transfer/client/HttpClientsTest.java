package com.securebank.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jwt.SignedJWT;
import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.transfer.client.ClientViews.HoldView;
import com.securebank.transfer.client.ClientViews.LedgerEntryView;
import com.securebank.transfer.client.ClientViews.TransferPosting;
import com.securebank.transfer.config.ClientConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP clients against WireMock: the mapping of HTTP outcomes to Success / Rejected / Unknown. */
@DisplayName("HTTP downstream clients")
class HttpClientsTest {

    static WireMockServer server;
    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    static final ServiceTokenProvider TOKENS =
            new ServiceTokenProvider("test-only-secret-test-only-secret-1234567890", "transfer-service");

    HttpAccountClient accounts;
    HttpLedgerClient ledger;
    final UUID account = UUID.randomUUID();

    @BeforeAll
    static void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        accounts = new HttpAccountClient(client(server.baseUrl()), MAPPER);
        ledger = new HttpLedgerClient(client(server.baseUrl()), MAPPER);
    }

    static RestClient client(String baseUrl) {
        return ClientConfig.restClient(RestClient.builder(), baseUrl, Duration.ofMillis(500), Duration.ofMillis(400),
                TOKENS);
    }

    private String holdsUrl() {
        return "/api/internal/accounts/" + account + "/holds";
    }

    @Test
    @DisplayName("2xx is Success; the SERVICE token and correlation id are sent")
    void success() throws Exception {
        server.stubFor(post(urlEqualTo(holdsUrl())).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"reference\":\"TRF-1\",\"accountId\":\"" + account
                        + "\",\"amount\":10.00,\"currency\":\"SGD\",\"status\":\"HELD\",\"replayed\":false,"
                        + "\"createdAt\":\"2026-11-06T10:00:00Z\"}")));

        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-123");
        try {
            RemoteResult<HoldView> r = accounts.placeHold(account, "TRF-1", new BigDecimal("10.00"), "SGD");
            assertThat(r).isInstanceOf(RemoteResult.Success.class);
            assertThat(((RemoteResult.Success<HoldView>) r).value().status()).isEqualTo("HELD");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        var request = server.findAll(postRequestedFor(urlEqualTo(holdsUrl()))).get(0);
        assertThat(request.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("corr-123");
        SignedJWT token = SignedJWT.parse(request.getHeader("Authorization").substring("Bearer ".length()));
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo("transfer-service");
        assertThat(token.getJWTClaimsSet().getStringListClaim("roles")).isEqualTo(List.of("SERVICE"));
        server.verify(postRequestedFor(urlEqualTo(holdsUrl()))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("TRF-1"))));
    }

    @Test
    @DisplayName("4xx business error is Rejected and keeps the downstream error code")
    void rejected() {
        server.stubFor(post(urlEqualTo(holdsUrl())).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":422,\"error\":\"INSUFFICIENT_FUNDS\",\"message\":\"Insufficient funds\"}")));

        RemoteResult<HoldView> r = accounts.placeHold(account, "TRF-2", BigDecimal.TEN, "SGD");

        assertThat(r).isEqualTo(new RemoteResult.Rejected<HoldView>(422, "INSUFFICIENT_FUNDS", "Insufficient funds"));
    }

    @Test
    @DisplayName("5xx is Unknown, never Rejected (the server may have applied the request)")
    void serverError() {
        server.stubFor(post(urlEqualTo(holdsUrl())).willReturn(aResponse().withStatus(500)));
        assertThat(accounts.placeHold(account, "TRF-3", BigDecimal.TEN, "SGD")).isInstanceOf(RemoteResult.Unknown.class);
    }

    @Test
    @DisplayName("401/403 are Unknown: a credentials problem says nothing about the business outcome")
    void authErrors() {
        server.stubFor(post(urlEqualTo(holdsUrl())).willReturn(aResponse().withStatus(403)));
        assertThat(accounts.placeHold(account, "TRF-4", BigDecimal.TEN, "SGD")).isInstanceOf(RemoteResult.Unknown.class);
    }

    @Test
    @DisplayName("a response slower than the read timeout is Unknown")
    void slowResponse() {
        server.stubFor(post(urlEqualTo("/api/transactions")).willReturn(aResponse().withStatus(201)
                .withFixedDelay(1500).withBody("{}")));

        RemoteResult<LedgerEntryView> r = ledger.postTransfer(new TransferPosting("TRF-5", "t", UUID.randomUUID(), "a",
                UUID.randomUUID(), "b", BigDecimal.ONE, "SGD"));

        assertThat(r).isInstanceOf(RemoteResult.Unknown.class);
    }

    @Test
    @DisplayName("connection refused is Unknown; status checks report Unavailable")
    void connectionRefused() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        HttpAccountClient down = new HttpAccountClient(client("http://localhost:" + closedPort), MAPPER);

        assertThat(down.placeHold(account, "TRF-6", BigDecimal.TEN, "SGD")).isInstanceOf(RemoteResult.Unknown.class);
        assertThat(down.findHold(account, "TRF-6")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    @DisplayName("status checks: 200 Found, 404 NotFound, 503 Unavailable")
    void lookups() {
        server.stubFor(get(urlEqualTo("/api/transactions/reference/TRF-7")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"entryId\":\"" + UUID.randomUUID() + "\",\"reference\":\"TRF-7\",\"entryType\":\"TRANSFER\"}")));
        server.stubFor(get(urlEqualTo("/api/transactions/reference/TRF-8")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlEqualTo("/api/transactions/reference/TRF-9")).willReturn(aResponse().withStatus(503)));

        assertThat(ledger.findByReference("TRF-7")).isInstanceOf(Lookup.Found.class);
        assertThat(ledger.findByReference("TRF-8")).isInstanceOf(Lookup.NotFound.class);
        assertThat(ledger.findByReference("TRF-9")).isInstanceOf(Lookup.Unavailable.class);
    }

    @Test
    @DisplayName("ledger posting is a balanced TRANSFER entry: DEBIT payer, CREDIT payee, with owners")
    void postingShape() {
        server.stubFor(post(urlEqualTo("/api/transactions")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"entryId\":\"" + UUID.randomUUID() + "\",\"reference\":\"TRF-10\"}")));
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        ledger.postTransfer(new TransferPosting("TRF-10", "Transfer TRF-10", from, "alice", to, "bob",
                new BigDecimal("12.34"), "SGD"));

        server.verify(postRequestedFor(urlEqualTo("/api/transactions"))
                .withRequestBody(matchingJsonPath("$.entryType", equalTo("TRANSFER")))
                .withRequestBody(matchingJsonPath("$.lines[0].accountRef", equalTo(from.toString())))
                .withRequestBody(matchingJsonPath("$.lines[0].direction", equalTo("DEBIT")))
                .withRequestBody(matchingJsonPath("$.lines[0].ownerId", equalTo("alice")))
                .withRequestBody(matchingJsonPath("$.lines[1].direction", equalTo("CREDIT")))
                .withRequestBody(matchingJsonPath("$.lines[1].ownerId", equalTo("bob")))
                .withRequestBody(containing("12.34")));
    }
}
