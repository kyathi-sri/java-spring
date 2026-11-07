package com.securebank.transaction.dto;

import com.securebank.transaction.domain.Direction;
import com.securebank.transaction.domain.EntryType;
import com.securebank.transaction.domain.JournalEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param replayed true when an idempotent retry returned an entry that already existed */
public record EntryResponse(
        UUID entryId,
        String reference,
        EntryType entryType,
        String description,
        UUID reversesEntryId,
        Instant postedAt,
        List<Line> lines,
        boolean replayed
) {
    public record Line(String accountRef, Direction direction, BigDecimal amount, String currency) {
    }

    public static EntryResponse from(JournalEntry e, boolean replayed) {
        List<Line> lines = e.getLines().stream()
                .map(l -> new Line(l.getAccountRef(), l.getDirection(), l.getAmount(), l.getCurrency()))
                .toList();
        return new EntryResponse(e.getId(), e.getReference(), e.getEntryType(), e.getDescription(),
                e.getReversesEntryId(), e.getPostedAt(), lines, replayed);
    }
}
