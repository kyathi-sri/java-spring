package com.securebank.transaction.repository;

import com.securebank.transaction.domain.JournalEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    @EntityGraph(attributePaths = "lines")
    Optional<JournalEntry> findWithLinesById(UUID id);

    @EntityGraph(attributePaths = "lines")
    Optional<JournalEntry> findWithLinesByReference(String reference);

    @EntityGraph(attributePaths = "lines")
    Optional<JournalEntry> findWithLinesByReversesEntryId(UUID reversesEntryId);
}
