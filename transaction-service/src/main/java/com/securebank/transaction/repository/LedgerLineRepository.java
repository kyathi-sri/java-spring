package com.securebank.transaction.repository;

import com.securebank.transaction.domain.LedgerLine;
import com.securebank.transaction.dto.LedgerLineView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Two variants of each read (with and without the owner filter) are used instead of
 * {@code (:owner is null or ...)}: PostgreSQL cannot infer the type of a null parameter,
 * and a fixed predicate also lets the planner use {@code idx_lines_account_owner}.
 */
public interface LedgerLineRepository extends JpaRepository<LedgerLine, Long> {

    String VIEW = "select new com.securebank.transaction.dto.LedgerLineView("
            + "l.id, e.id, e.reference, e.entryType, l.direction, l.amount, l.currency, l.postedAt, e.description) "
            + "from LedgerLine l join l.journalEntry e ";

    @Query(value = VIEW + "where l.accountRef = :ref and l.postedAt >= :from and l.postedAt < :to",
           countQuery = "select count(l) from LedgerLine l "
                   + "where l.accountRef = :ref and l.postedAt >= :from and l.postedAt < :to")
    Page<LedgerLineView> history(@Param("ref") String accountRef, @Param("from") Instant from,
                                 @Param("to") Instant to, Pageable pageable);

    @Query(value = VIEW + "where l.accountRef = :ref and l.ownerId = :owner "
            + "and l.postedAt >= :from and l.postedAt < :to",
           countQuery = "select count(l) from LedgerLine l where l.accountRef = :ref and l.ownerId = :owner "
                   + "and l.postedAt >= :from and l.postedAt < :to")
    Page<LedgerLineView> historyForOwner(@Param("ref") String accountRef, @Param("owner") String owner,
                                         @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** Liability-account balance strictly before {@code asOf}: credits minus debits. */
    @Query(value = "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0) "
            + "from ledger_lines where account_ref = :ref and posted_at < :asOf", nativeQuery = true)
    BigDecimal balanceBefore(@Param("ref") String accountRef, @Param("asOf") Instant asOf);

    @Query(value = "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0) "
            + "from ledger_lines where account_ref = :ref and owner_id = :owner and posted_at < :asOf",
           nativeQuery = true)
    BigDecimal balanceBeforeForOwner(@Param("ref") String accountRef, @Param("owner") String owner,
                                     @Param("asOf") Instant asOf);

    /** Trial balance: across the whole ledger, debits must equal credits per currency. */
    @Query(value = "select currency as currency, "
            + "sum(case when direction = 'DEBIT' then amount else 0 end) as totalDebits, "
            + "sum(case when direction = 'CREDIT' then amount else 0 end) as totalCredits, "
            + "count(*) as lineCount "
            + "from ledger_lines group by currency order by currency", nativeQuery = true)
    List<TrialBalanceRow> trialBalance();

    /** Entries whose own lines do not net to zero (must always be 0). */
    @Query(value = "select count(*) from (select journal_entry_id from ledger_lines "
            + "group by journal_entry_id, currency "
            + "having sum(case when direction = 'DEBIT' then amount else -amount end) <> 0) unbalanced",
           nativeQuery = true)
    long countUnbalancedEntries();

    interface TrialBalanceRow {
        String getCurrency();
        BigDecimal getTotalDebits();
        BigDecimal getTotalCredits();
        Long getLineCount();
    }
}
