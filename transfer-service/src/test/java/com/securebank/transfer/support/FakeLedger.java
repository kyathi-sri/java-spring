package com.securebank.transfer.support;

import com.securebank.transfer.client.ClientViews.LedgerEntryView;
import com.securebank.transfer.client.ClientViews.TransferPosting;
import com.securebank.transfer.client.LedgerClient;
import com.securebank.transfer.client.Lookup;
import com.securebank.transfer.client.RemoteResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory ledger with transaction-service's contract (verified by LedgerApiIT): idempotent on
 * reference, at most one reversal per entry, entries never edited.
 */
public class FakeLedger implements LedgerClient {

    public enum Op { POST, FIND, REVERSE }

    public final FaultPlan<Op> faults = new FaultPlan<>(Op.class);

    public record Line(String accountRef, String direction, BigDecimal amount) {
    }

    public record Entry(UUID id, String reference, String type, UUID reverses, List<Line> lines) {
    }

    private final Map<String, Entry> byReference = new HashMap<>();
    private final Map<UUID, Entry> byId = new HashMap<>();

    public synchronized List<Entry> entriesFor(String transferReference) {
        List<Entry> out = new ArrayList<>();
        Entry original = byReference.get(transferReference);
        if (original != null) {
            out.add(original);
        }
        Entry reversal = byReference.get("REV-" + transferReference);
        if (reversal != null) {
            out.add(reversal);
        }
        return out;
    }

    /** Liability-account balance: credits minus debits. */
    public synchronized BigDecimal balance(UUID account) {
        BigDecimal total = BigDecimal.ZERO;
        for (Entry e : byId.values()) {
            for (Line l : e.lines()) {
                if (l.accountRef().equals(account.toString())) {
                    total = "CREDIT".equals(l.direction()) ? total.add(l.amount()) : total.subtract(l.amount());
                }
            }
        }
        return total;
    }

    @Override
    public RemoteResult<LedgerEntryView> postTransfer(TransferPosting p) {
        return faults.mutate(Op.POST, () -> {
            synchronized (this) {
                Entry existing = byReference.get(p.reference());
                if (existing != null) {
                    return new RemoteResult.Success<>(view(existing, true));
                }
                Entry e = new Entry(UUID.randomUUID(), p.reference(), "TRANSFER", null, List.of(
                        new Line(p.sourceAccountId().toString(), "DEBIT", p.amount()),
                        new Line(p.beneficiaryAccountId().toString(), "CREDIT", p.amount())));
                store(e);
                return new RemoteResult.Success<>(view(e, false));
            }
        }, () -> new RemoteResult.Rejected<>(422, "UNBALANCED_ENTRY", "simulated"),
                () -> new RemoteResult.Unknown<>("simulated timeout"));
    }

    @Override
    public Lookup<LedgerEntryView> findByReference(String reference) {
        return faults.read(Op.FIND, () -> {
            synchronized (this) {
                Entry e = byReference.get(reference);
                return e == null ? new Lookup.NotFound<>() : new Lookup.Found<>(view(e, false));
            }
        }, () -> new Lookup.Unavailable<>("simulated timeout"));
    }

    @Override
    public RemoteResult<LedgerEntryView> reverse(UUID entryId, String reason) {
        return faults.mutate(Op.REVERSE, () -> {
            synchronized (this) {
                Entry original = byId.get(entryId);
                if (original == null) {
                    return new RemoteResult.Rejected<>(404, "ENTRY_NOT_FOUND", "simulated");
                }
                Entry existing = byReference.get("REV-" + original.reference());
                if (existing != null) {
                    return new RemoteResult.Success<>(view(existing, true));
                }
                List<Line> flipped = original.lines().stream()
                        .map(l -> new Line(l.accountRef(), "DEBIT".equals(l.direction()) ? "CREDIT" : "DEBIT",
                                l.amount()))
                        .toList();
                Entry reversal = new Entry(UUID.randomUUID(), "REV-" + original.reference(), "REVERSAL",
                        original.id(), flipped);
                store(reversal);
                return new RemoteResult.Success<>(view(reversal, false));
            }
        }, () -> new RemoteResult.Rejected<>(422, "REVERSAL_NOT_ALLOWED", "simulated"),
                () -> new RemoteResult.Unknown<>("simulated timeout"));
    }

    private void store(Entry e) {
        byReference.put(e.reference(), e);
        byId.put(e.id(), e);
    }

    private static LedgerEntryView view(Entry e, boolean replayed) {
        return new LedgerEntryView(e.id(), e.reference(), e.type(), e.reverses(), replayed);
    }
}
