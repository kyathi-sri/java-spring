package com.securebank.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.transfer.client.ClientViews.LedgerEntryView;
import com.securebank.transfer.client.ClientViews.TransferPosting;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class HttpLedgerClient implements LedgerClient {

    private final RestClient rest;
    private final ObjectMapper mapper;

    public HttpLedgerClient(RestClient rest, ObjectMapper mapper) {
        this.rest = rest;
        this.mapper = mapper;
    }

    record Line(String accountRef, String ownerId, String direction, BigDecimal amount, String currency) {
    }

    record PostBody(String reference, String entryType, String description, List<Line> lines) {
    }

    record ReversalBody(String reason) {
    }

    @Override
    public RemoteResult<LedgerEntryView> postTransfer(TransferPosting p) {
        // Customer deposit accounts are liabilities: DEBIT the payer, CREDIT the payee.
        PostBody body = new PostBody(p.reference(), "TRANSFER", p.description(), List.of(
                new Line(p.sourceAccountId().toString(), p.sourceOwnerId(), "DEBIT", p.amount(), p.currency()),
                new Line(p.beneficiaryAccountId().toString(), p.beneficiaryOwnerId(), "CREDIT", p.amount(),
                        p.currency())));
        return RemoteCalls.call(rest.post().uri("/api/transactions").contentType(MediaType.APPLICATION_JSON)
                .body(body), LedgerEntryView.class, mapper, "postTransfer");
    }

    @Override
    public Lookup<LedgerEntryView> findByReference(String reference) {
        return RemoteCalls.lookup(rest.get().uri("/api/transactions/reference/{reference}", reference),
                LedgerEntryView.class, mapper, "findLedgerEntry");
    }

    @Override
    public RemoteResult<LedgerEntryView> reverse(UUID entryId, String reason) {
        return RemoteCalls.call(rest.post().uri("/api/transactions/{id}/reversal", entryId)
                .contentType(MediaType.APPLICATION_JSON).body(new ReversalBody(reason)),
                LedgerEntryView.class, mapper, "reverseLedgerEntry");
    }
}
