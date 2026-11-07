package com.securebank.transfer.support;

import com.securebank.transfer.client.AccountClient;
import com.securebank.transfer.client.ClientViews.AccountSummary;
import com.securebank.transfer.client.ClientViews.CreditView;
import com.securebank.transfer.client.ClientViews.HoldView;
import com.securebank.transfer.client.Lookup;
import com.securebank.transfer.client.RemoteResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory account-service with the same contract as the real one (verified by HoldsApiIT):
 * replay before rule checks, tombstone on release-before-reserve, holds ring-fence funds.
 * All state changes are synchronized, mirroring the real row lock.
 */
public class FakeAccounts implements AccountClient {

    public enum Op { GET_ACCOUNT, PLACE_HOLD, FIND_HOLD, CAPTURE, RELEASE, CREDIT, FIND_CREDIT }

    public final FaultPlan<Op> faults = new FaultPlan<>(Op.class);

    public static final class Account {
        final UUID id;
        final String owner;
        final String currency;
        volatile String status = "ACTIVE";
        BigDecimal balance;
        BigDecimal reserved = BigDecimal.ZERO;

        Account(UUID id, String owner, String currency, BigDecimal balance) {
            this.id = id;
            this.owner = owner;
            this.currency = currency;
            this.balance = balance;
        }
    }

    static final class Hold {
        final String reference;
        final UUID accountId;
        final BigDecimal amount;
        String status;
        final boolean tombstone;

        Hold(String reference, UUID accountId, BigDecimal amount, String status, boolean tombstone) {
            this.reference = reference;
            this.accountId = accountId;
            this.amount = amount;
            this.status = status;
            this.tombstone = tombstone;
        }
    }

    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Map<String, Hold> holds = new HashMap<>();
    private final Map<String, CreditView> credits = new HashMap<>();

    // ------------------------------------------------------------------ test helpers

    public synchronized UUID open(String owner, String balance) {
        return open(owner, balance, "SGD");
    }

    public synchronized UUID open(String owner, String balance, String currency) {
        UUID id = UUID.randomUUID();
        accounts.put(id, new Account(id, owner, currency, new BigDecimal(balance)));
        return id;
    }

    public synchronized void setStatus(UUID id, String status) {
        accounts.get(id).status = status;
    }

    public synchronized BigDecimal balance(UUID id) {
        return accounts.get(id).balance;
    }

    public synchronized BigDecimal reserved(UUID id) {
        return accounts.get(id).reserved;
    }

    public synchronized String holdStatus(String reference) {
        Hold h = holds.get(reference);
        return h == null ? null : h.status;
    }

    public synchronized long creditCount(String reference) {
        return credits.containsKey(reference) ? 1 : 0;
    }

    /** Simulates a delayed duplicate of a reserve request reaching account-service late. */
    public RemoteResult<HoldView> lateReserve(UUID accountId, String reference, BigDecimal amount) {
        return doPlaceHold(accountId, reference, amount, "SGD");
    }

    // ------------------------------------------------------------------ AccountClient

    @Override
    public RemoteResult<AccountSummary> getAccount(UUID accountId) {
        return faults.mutate(Op.GET_ACCOUNT, () -> {
            synchronized (this) {
                Account a = accounts.get(accountId);
                if (a == null) {
                    return rejected(404, "ACCOUNT_NOT_FOUND");
                }
                return new RemoteResult.Success<>(new AccountSummary(a.id, "SB" + a.id.toString().substring(0, 8),
                        a.owner, a.currency, a.status));
            }
        }, () -> rejected(404, "ACCOUNT_NOT_FOUND"), FakeAccounts::unknown);
    }

    @Override
    public RemoteResult<HoldView> placeHold(UUID accountId, String reference, BigDecimal amount, String currency) {
        return faults.mutate(Op.PLACE_HOLD, () -> doPlaceHold(accountId, reference, amount, currency),
                () -> rejected(409, "ACCOUNT_NOT_ACTIVE"), FakeAccounts::unknown);
    }

    private synchronized RemoteResult<HoldView> doPlaceHold(UUID accountId, String reference, BigDecimal amount,
                                                           String currency) {
        Account a = accounts.get(accountId);
        if (a == null) {
            return rejected(404, "ACCOUNT_NOT_FOUND");
        }
        Hold existing = holds.get(reference);
        if (existing != null) {
            if ("RELEASED".equals(existing.status)) {
                return rejected(409, "HOLD_STATE_CONFLICT");
            }
            return new RemoteResult.Success<>(view(existing, true));
        }
        if (!"ACTIVE".equals(a.status)) {
            return rejected(409, "ACCOUNT_NOT_ACTIVE");
        }
        if (!a.currency.equals(currency)) {
            return rejected(422, "CURRENCY_MISMATCH");
        }
        if (a.balance.subtract(a.reserved).compareTo(amount) < 0) {
            return rejected(422, "INSUFFICIENT_FUNDS");
        }
        a.reserved = a.reserved.add(amount);
        Hold h = new Hold(reference, accountId, amount, "HELD", false);
        holds.put(reference, h);
        return new RemoteResult.Success<>(view(h, false));
    }

    @Override
    public Lookup<HoldView> findHold(UUID accountId, String reference) {
        return faults.read(Op.FIND_HOLD, () -> {
            synchronized (this) {
                Hold h = holds.get(reference);
                return h == null || !h.accountId.equals(accountId) ? new Lookup.NotFound<>()
                        : new Lookup.Found<>(view(h, false));
            }
        }, () -> new Lookup.Unavailable<>("simulated timeout"));
    }

    @Override
    public RemoteResult<HoldView> captureHold(UUID accountId, String reference) {
        return faults.mutate(Op.CAPTURE, () -> {
            synchronized (this) {
                Hold h = holds.get(reference);
                if (h == null) {
                    return rejected(404, "HOLD_NOT_FOUND");
                }
                switch (h.status) {
                    case "CAPTURED":
                        return new RemoteResult.Success<>(view(h, true));
                    case "RELEASED":
                        return rejected(409, "HOLD_STATE_CONFLICT");
                    default:
                        Account a = accounts.get(accountId);
                        a.reserved = a.reserved.subtract(h.amount);
                        a.balance = a.balance.subtract(h.amount);
                        h.status = "CAPTURED";
                        return new RemoteResult.Success<>(view(h, false));
                }
            }
        }, () -> rejected(409, "HOLD_STATE_CONFLICT"), FakeAccounts::unknown);
    }

    @Override
    public RemoteResult<HoldView> releaseHold(UUID accountId, String reference) {
        return faults.mutate(Op.RELEASE, () -> {
            synchronized (this) {
                Hold h = holds.get(reference);
                if (h == null) {
                    Hold tombstone = new Hold(reference, accountId, BigDecimal.ZERO, "RELEASED", true);
                    holds.put(reference, tombstone);
                    return new RemoteResult.Success<>(view(tombstone, false));
                }
                switch (h.status) {
                    case "RELEASED":
                        return new RemoteResult.Success<>(view(h, true));
                    case "CAPTURED":
                        return rejected(409, "HOLD_STATE_CONFLICT");
                    default:
                        Account a = accounts.get(accountId);
                        a.reserved = a.reserved.subtract(h.amount);
                        h.status = "RELEASED";
                        return new RemoteResult.Success<>(view(h, false));
                }
            }
        }, () -> rejected(409, "HOLD_STATE_CONFLICT"), FakeAccounts::unknown);
    }

    @Override
    public RemoteResult<CreditView> credit(UUID accountId, String reference, BigDecimal amount, String currency) {
        return faults.mutate(Op.CREDIT, () -> {
            synchronized (this) {
                CreditView existing = credits.get(reference);
                if (existing != null) {
                    return new RemoteResult.Success<>(new CreditView(reference, accountId, amount, currency, true));
                }
                Account a = accounts.get(accountId);
                if (a == null) {
                    return rejected(404, "ACCOUNT_NOT_FOUND");
                }
                if ("CLOSED".equals(a.status)) {
                    return rejected(409, "ACCOUNT_NOT_ACTIVE");
                }
                a.balance = a.balance.add(amount);
                CreditView c = new CreditView(reference, accountId, amount, currency, false);
                credits.put(reference, c);
                return new RemoteResult.Success<>(c);
            }
        }, () -> rejected(409, "ACCOUNT_NOT_ACTIVE"), FakeAccounts::unknown);
    }

    @Override
    public Lookup<CreditView> findCredit(UUID accountId, String reference) {
        return faults.read(Op.FIND_CREDIT, () -> {
            synchronized (this) {
                CreditView c = credits.get(reference);
                return c == null ? new Lookup.NotFound<>() : new Lookup.Found<>(c);
            }
        }, () -> new Lookup.Unavailable<>("simulated timeout"));
    }

    private static HoldView view(Hold h, boolean replayed) {
        return new HoldView(h.reference, h.accountId, h.amount, "SGD", h.status, h.tombstone, replayed);
    }

    private static <T> RemoteResult<T> rejected(int status, String code) {
        return new RemoteResult.Rejected<>(status, code, "simulated " + code);
    }

    private static <T> RemoteResult<T> unknown() {
        return new RemoteResult.Unknown<>("simulated timeout");
    }
}
