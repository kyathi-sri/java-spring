package com.securebank.transfer.client;

import com.securebank.transfer.client.ClientViews.LedgerEntryView;
import com.securebank.transfer.client.ClientViews.TransferPosting;

import java.util.UUID;

/** transaction-service ledger API. Posting is idempotent on reference; reversal at most once per entry. */
public interface LedgerClient {

    RemoteResult<LedgerEntryView> postTransfer(TransferPosting posting);

    Lookup<LedgerEntryView> findByReference(String reference);

    /** Posts a REVERSAL entry (reference {@code REV-<original reference>}); never edits the original. */
    RemoteResult<LedgerEntryView> reverse(UUID entryId, String reason);
}
