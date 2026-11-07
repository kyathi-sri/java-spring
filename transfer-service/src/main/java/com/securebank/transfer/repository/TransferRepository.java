package com.securebank.transfer.repository;

import com.securebank.transfer.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByReference(String reference);

    Optional<Transfer> findByInitiatedByAndIdempotencyKey(String initiatedBy, String idempotencyKey);

    /**
     * Atomically claims an unfinished transfer for one worker. Succeeds only if nobody holds a live
     * lease. Bumping {@code version} invalidates any stale in-memory copy held by a previous owner.
     */
    @Modifying
    @Query(value = "update transfers set lease_owner = :owner, lease_until = :until, version = version + 1 "
            + "where id = :id and status in ('PENDING', 'PROCESSING') "
            + "and (lease_until is null or lease_until < :now)", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("owner") String owner, @Param("until") Instant until,
              @Param("now") Instant now);

    @Modifying
    @Query(value = "update transfers set lease_until = :until where id = :id and lease_owner = :owner",
            nativeQuery = true)
    int renew(@Param("id") UUID id, @Param("owner") String owner, @Param("until") Instant until);

    @Modifying
    @Query(value = "update transfers set lease_owner = null, lease_until = null "
            + "where id = :id and lease_owner = :owner", nativeQuery = true)
    int release(@Param("id") UUID id, @Param("owner") String owner);

    /** Unfinished transfers that are due for (another) attempt and not currently leased. */
    @Query(value = "select id from transfers where status in ('PENDING', 'PROCESSING') "
            + "and (next_attempt_at is null or next_attempt_at <= :now) "
            + "and (lease_until is null or lease_until < :now) "
            + "order by created_at limit :limit", nativeQuery = true)
    List<UUID> findDue(@Param("now") Instant now, @Param("limit") int limit);
}
