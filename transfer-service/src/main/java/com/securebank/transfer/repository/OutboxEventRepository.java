package com.securebank.transfer.repository;

import com.securebank.transfer.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Oldest unpublished events, row-locked. {@code SKIP LOCKED} lets several relay instances run
     * side by side without publishing the same event concurrently.
     */
    @Query(value = "select * from outbox_events where published_at is null order by id "
            + "limit :limit for update skip locked", nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(UUID aggregateId);

    long countByPublishedAtIsNull();
}
