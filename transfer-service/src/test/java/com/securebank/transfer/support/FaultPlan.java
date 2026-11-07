package com.securebank.transfer.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Queue of faults per operation, plus call counters. Thread-safe. */
public class FaultPlan<O extends Enum<O>> {

    private final Map<O, Deque<Fault>> faults;
    private final Map<O, AtomicInteger> calls;

    public FaultPlan(Class<O> ops) {
        this.faults = new EnumMap<>(ops);
        this.calls = new EnumMap<>(ops);
        for (O op : ops.getEnumConstants()) {
            faults.put(op, new ArrayDeque<>());
            calls.put(op, new AtomicInteger());
        }
    }

    public synchronized void inject(O op, Fault fault, int times) {
        for (int i = 0; i < times; i++) {
            faults.get(op).add(fault);
        }
    }

    public synchronized void clear() {
        faults.values().forEach(Deque::clear);
        calls.values().forEach(c -> c.set(0));
    }

    public int calls(O op) {
        return calls.get(op).get();
    }

    synchronized Fault next(O op) {
        calls.get(op).incrementAndGet();
        return faults.get(op).poll();
    }

    /**
     * Runs a mutating operation under the next injected fault.
     *
     * @param rejection what to return for {@link Fault#REJECT}
     * @param unknown   what to return for a timeout
     */
    public <R> R mutate(O op, Supplier<R> action, Supplier<R> rejection, Supplier<R> unknown) {
        Fault fault = next(op);
        if (fault == null) {
            return action.get();
        }
        return switch (fault) {
            case TIMEOUT_NOT_APPLIED -> unknown.get();
            case REJECT -> rejection.get();
            case TIMEOUT_APPLIED -> {
                action.get();
                yield unknown.get();
            }
            case CRASH_AFTER_APPLY -> {
                action.get();
                throw new SimulatedCrash(op.name());
            }
        };
    }

    /** Runs a read-only status check; any timeout fault makes it unavailable. */
    public <R> R read(O op, Supplier<R> action, Supplier<R> unavailable) {
        Fault fault = next(op);
        return fault == null ? action.get() : unavailable.get();
    }
}
