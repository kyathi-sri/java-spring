package com.securebank.transfer.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** Inbox of handled Kafka messages (plain SQL; there is no need for an entity). */
public interface ProcessedMessageRepository extends Repository<ProcessedMarker, String> {

    @Query(value = "select count(*) > 0 from processed_messages where message_id = :id", nativeQuery = true)
    boolean exists(@Param("id") String messageId);

    /** Idempotent: a duplicate insert is silently ignored. Returns 1 if this call recorded it. */
    @Modifying
    @Query(value = "insert into processed_messages (message_id, source, outcome, processed_at) "
            + "values (:id, :source, :outcome, :at) on conflict (message_id) do nothing", nativeQuery = true)
    int record(@Param("id") String messageId, @Param("source") String source, @Param("outcome") String outcome,
               @Param("at") Instant at);
}
