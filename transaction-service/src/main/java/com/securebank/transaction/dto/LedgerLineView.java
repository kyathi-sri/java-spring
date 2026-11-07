package com.securebank.transaction.dto;

import com.securebank.transaction.domain.Direction;
import com.securebank.transaction.domain.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A ledger line as seen in account history (joined with its journal entry). */
public record LedgerLineView(
        Long lineId,
        UUID entryId,
        String reference,
        EntryType entryType,
        Direction direction,
        BigDecimal amount,
        String currency,
        Instant postedAt,
        String description
) {
}
