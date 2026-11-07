package com.securebank.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shapes of downstream responses this service relies on.
 *
 * <p>Tolerant reader: unknown fields are ignored explicitly (not via global ObjectMapper config), so
 * a downstream service adding a field can never turn a successful 2xx into an "unknown" outcome,
 * which would make the saga retry an already-applied step forever.
 */
public final class ClientViews {

    private ClientViews() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountSummary(UUID id, String accountNumber, String ownerId, String currency, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HoldView(String reference, UUID accountId, BigDecimal amount, String currency, String status,
                           boolean tombstone, boolean replayed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditView(String reference, UUID accountId, BigDecimal amount, String currency,
                             boolean replayed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LedgerEntryView(UUID entryId, String reference, String entryType, UUID reversesEntryId,
                                  boolean replayed) {
    }

    /** Everything the ledger needs to post the double entry of one transfer. */
    public record TransferPosting(String reference, String description, UUID sourceAccountId, String sourceOwnerId,
                                  UUID beneficiaryAccountId, String beneficiaryOwnerId, BigDecimal amount,
                                  String currency) {
    }
}
