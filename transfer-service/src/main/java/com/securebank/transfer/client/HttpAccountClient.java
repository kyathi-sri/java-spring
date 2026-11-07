package com.securebank.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transfer.client.ClientViews.AccountSummary;
import com.securebank.transfer.client.ClientViews.CreditView;
import com.securebank.transfer.client.ClientViews.HoldView;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

public class HttpAccountClient implements AccountClient {

    private static final String ACCOUNT = "/api/internal/accounts/{id}";
    private static final String HOLDS = ACCOUNT + "/holds";
    private static final String HOLD = HOLDS + "/{reference}";
    private static final String CREDITS = ACCOUNT + "/credits";

    private final RestClient rest;
    private final ObjectMapper mapper;

    public HttpAccountClient(RestClient rest, ObjectMapper mapper) {
        this.rest = rest;
        this.mapper = mapper;
    }

    record AmountBody(String reference, BigDecimal amount, String currency) {
    }

    @Override
    public RemoteResult<AccountSummary> getAccount(UUID accountId) {
        return RemoteCalls.call(rest.get().uri(ACCOUNT, accountId), AccountSummary.class, mapper, "getAccount");
    }

    @Override
    public RemoteResult<HoldView> placeHold(UUID accountId, String reference, BigDecimal amount, String currency) {
        return RemoteCalls.call(rest.post().uri(HOLDS, accountId).contentType(MediaType.APPLICATION_JSON)
                .body(new AmountBody(reference, amount, currency)), HoldView.class, mapper, "placeHold");
    }

    @Override
    public Lookup<HoldView> findHold(UUID accountId, String reference) {
        return RemoteCalls.lookup(rest.get().uri(HOLD, accountId, reference), HoldView.class, mapper, "findHold");
    }

    @Override
    public RemoteResult<HoldView> captureHold(UUID accountId, String reference) {
        return RemoteCalls.call(rest.post().uri(HOLD + "/capture", accountId, reference), HoldView.class, mapper,
                "captureHold");
    }

    @Override
    public RemoteResult<HoldView> releaseHold(UUID accountId, String reference) {
        return RemoteCalls.call(rest.post().uri(HOLD + "/release", accountId, reference), HoldView.class, mapper,
                "releaseHold");
    }

    @Override
    public RemoteResult<CreditView> credit(UUID accountId, String reference, BigDecimal amount, String currency) {
        return RemoteCalls.call(rest.post().uri(CREDITS, accountId).contentType(MediaType.APPLICATION_JSON)
                .body(new AmountBody(reference, amount, currency)), CreditView.class, mapper, "credit");
    }

    @Override
    public Lookup<CreditView> findCredit(UUID accountId, String reference) {
        return RemoteCalls.lookup(rest.get().uri(CREDITS + "/{reference}", accountId, reference), CreditView.class,
                mapper, "findCredit");
    }
}
