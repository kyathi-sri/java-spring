package com.securebank.transaction.service;

import com.securebank.transaction.domain.Direction;
import com.securebank.transaction.domain.EntryType;
import com.securebank.transaction.domain.JournalEntry;
import com.securebank.transaction.dto.EntryResponse;
import com.securebank.transaction.dto.PostEntryRequest;
import com.securebank.transaction.exception.LedgerExceptions.IdempotencyConflictException;
import com.securebank.transaction.exception.LedgerExceptions.InvalidRequestException;
import com.securebank.transaction.exception.LedgerExceptions.ReversalNotAllowedException;
import com.securebank.transaction.exception.LedgerExceptions.UnbalancedEntryException;
import com.securebank.transaction.repository.JournalEntryRepository;
import com.securebank.transaction.repository.LedgerLineRepository;
import com.securebank.transaction.security.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService")
class LedgerServiceTest {

    @Mock JournalEntryRepository entries;
    @Mock LedgerLineRepository lines;
    @Mock PlatformTransactionManager txManager;

    LedgerService service;
    final Caller transferService = new Caller("transfer-service", false, false, true);

    @BeforeEach
    void setUp() {
        service = new LedgerService(entries, lines, txManager);
    }

    static PostEntryRequest.Line line(String account, Direction d, String amount) {
        return new PostEntryRequest.Line(account, null, d, new BigDecimal(amount), "SGD");
    }

    static PostEntryRequest transfer(String reference, String amount) {
        return new PostEntryRequest(reference, EntryType.TRANSFER, "test", List.of(
                line("ACC-A", Direction.DEBIT, amount),
                line("ACC-B", Direction.CREDIT, amount)));
    }

    @Test
    @DisplayName("balanced entries pass, including multi-line splits")
    void balancedPasses() {
        assertThatCode(() -> LedgerService.assertBalanced(List.of(
                line("A", Direction.DEBIT, "100.00"),
                line("B", Direction.CREDIT, "60.00"),
                line("C", Direction.CREDIT, "40.00")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("unbalanced entry is rejected before touching the database")
    void unbalancedRejected() {
        PostEntryRequest bad = new PostEntryRequest("REF-1", EntryType.TRANSFER, null, List.of(
                line("A", Direction.DEBIT, "100.00"),
                line("B", Direction.CREDIT, "99.99")));

        assertThatThrownBy(() -> service.post(bad, transferService)).isInstanceOf(UnbalancedEntryException.class);
        verify(entries, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("each currency must balance on its own")
    void perCurrencyBalance() {
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(
                new PostEntryRequest.Line("A", null, Direction.DEBIT, new BigDecimal("10.00"), "SGD"),
                new PostEntryRequest.Line("B", null, Direction.CREDIT, new BigDecimal("10.00"), "USD"))))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    @DisplayName("REVERSAL type cannot be posted directly")
    void reversalTypeRejected() {
        PostEntryRequest req = new PostEntryRequest("REF-2", EntryType.REVERSAL, null, List.of(
                line("A", Direction.DEBIT, "1.00"), line("B", Direction.CREDIT, "1.00")));
        assertThatThrownBy(() -> service.post(req, transferService)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("new entry is saved with normalized amounts")
    void postsNewEntry() {
        when(entries.findWithLinesByReference("REF-3")).thenReturn(Optional.empty());

        EntryResponse resp = service.post(transfer("REF-3", "25.5"), transferService);

        ArgumentCaptor<JournalEntry> saved = ArgumentCaptor.forClass(JournalEntry.class);
        verify(entries).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLines()).hasSize(2)
                .allSatisfy(l -> assertThat(l.getAmount()).isEqualByComparingTo("25.50"));
        assertThat(resp.replayed()).isFalse();
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("transfer-service");
    }

    @Test
    @DisplayName("retry with the same reference and payload is a replay, not a second posting")
    void replay() {
        JournalEntry existing = existingTransfer("REF-4", "25.50");
        when(entries.findWithLinesByReference("REF-4")).thenReturn(Optional.of(existing));

        EntryResponse resp = service.post(transfer("REF-4", "25.5"), transferService);

        assertThat(resp.replayed()).isTrue();
        assertThat(resp.entryId()).isEqualTo(existing.getId());
        verify(entries, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("same reference with a different amount is a conflict")
    void conflict() {
        when(entries.findWithLinesByReference("REF-5")).thenReturn(Optional.of(existingTransfer("REF-5", "25.50")));

        assertThatThrownBy(() -> service.post(transfer("REF-5", "26.00"), transferService))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("a reversal cannot be reversed")
    void reverseReversal() {
        JournalEntry reversal = new JournalEntry("REV-X", EntryType.REVERSAL, null,
                java.util.UUID.randomUUID(), null, "admin");
        when(entries.findWithLinesById(reversal.getId())).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.reverse(reversal.getId(), "oops", transferService))
                .isInstanceOf(ReversalNotAllowedException.class);
    }

    @Test
    @DisplayName("reversal flips every line and links to the original")
    void reversalFlipsLines() {
        JournalEntry original = existingTransfer("REF-6", "40.00");
        when(entries.findWithLinesById(original.getId())).thenReturn(Optional.of(original));
        when(entries.findWithLinesByReversesEntryId(original.getId())).thenReturn(Optional.empty());

        EntryResponse resp = service.reverse(original.getId(), "customer dispute", transferService);

        assertThat(resp.entryType()).isEqualTo(EntryType.REVERSAL);
        assertThat(resp.reference()).isEqualTo("REV-REF-6");
        assertThat(resp.reversesEntryId()).isEqualTo(original.getId());
        assertThat(resp.lines()).extracting(EntryResponse.Line::accountRef, EntryResponse.Line::direction)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ACC-A", Direction.CREDIT),
                        org.assertj.core.groups.Tuple.tuple("ACC-B", Direction.DEBIT));
    }

    private static JournalEntry existingTransfer(String reference, String amount) {
        JournalEntry e = new JournalEntry(reference, EntryType.TRANSFER, "test", null, null, "transfer-service");
        e.addLine("ACC-A", null, Direction.DEBIT, new BigDecimal(amount), "SGD");
        e.addLine("ACC-B", null, Direction.CREDIT, new BigDecimal(amount), "SGD");
        return e;
    }
}
