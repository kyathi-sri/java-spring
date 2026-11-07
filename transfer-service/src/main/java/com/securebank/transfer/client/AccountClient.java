package com.securebank.transfer.client;

import com.securebank.transfer.client.ClientViews.AccountSummary;
import com.securebank.transfer.client.ClientViews.CreditView;
import com.securebank.transfer.client.ClientViews.HoldView;

import java.math.BigDecimal;
import java.util.UUID;

/** account-service internal API. Every mutating call is idempotent on {@code reference}. */
public interface AccountClient {

    RemoteResult<AccountSummary> getAccount(UUID accountId);

    RemoteResult<HoldView> placeHold(UUID accountId, String reference, BigDecimal amount, String currency);

    Lookup<HoldView> findHold(UUID accountId, String reference);

    RemoteResult<HoldView> captureHold(UUID accountId, String reference);

    /** Writes a tombstone if the hold does not exist, so a delayed reserve is refused later. */
    RemoteResult<HoldView> releaseHold(UUID accountId, String reference);

    RemoteResult<CreditView> credit(UUID accountId, String reference, BigDecimal amount, String currency);

    Lookup<CreditView> findCredit(UUID accountId, String reference);
}
