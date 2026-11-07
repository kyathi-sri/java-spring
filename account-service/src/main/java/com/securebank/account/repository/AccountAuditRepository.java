package com.securebank.account.repository;

import com.securebank.account.domain.AccountAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountAuditRepository extends JpaRepository<AccountAudit, Long> {

    List<AccountAudit> findByAccountIdOrderByCreatedAtAsc(UUID accountId);
}
