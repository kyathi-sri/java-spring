package com.securebank.common.event;

/**
 * Canonical Kafka topic names shared across producers and consumers.
 *
 * <p>Centralizing the strings here prevents the classic "producer writes to
 * {@code transfer-events} but consumer listens on {@code transfer.events}" bug.
 * This is a pure constants holder — the only thing it is safe to share.
 */
public final class EventTopics {

    private EventTopics() {
    }

    /** Lifecycle events for fund transfers (saga orchestration signals). */
    public static final String TRANSFER_EVENTS = "securebank.transfer.events";

    /** Ledger posting requests/acknowledgements between transfer and transaction services. */
    public static final String LEDGER_EVENTS = "securebank.ledger.events";

    /** Transfer instructions from asynchronous channels (e.g. scheduled payments), consumed by transfer-service. */
    public static final String TRANSFER_COMMANDS = "securebank.transfer.commands";

    /** Notification triggers consumed by notification-service. */
    public static final String NOTIFICATION_EVENTS = "securebank.notification.events";
}
