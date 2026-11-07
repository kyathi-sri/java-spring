package com.securebank.common.event;

import java.time.Instant;

/**
 * Marker/base contract for all cross-service domain events.
 *
 * <p>Events are the integration backbone for the saga and the transactional outbox.
 * Every event is identifiable ({@code eventId}), ordered-in-time ({@code occurredAt}),
 * and typed ({@code eventType}) so consumers can route and deduplicate.
 *
 * <p>Kept framework-free on purpose: this interface lives in common-library and must
 * not drag in Spring or JPA, otherwise every service becomes coupled to those choices.
 */
public interface DomainEvent {

    String eventId();

    String eventType();

    Instant occurredAt();
}
