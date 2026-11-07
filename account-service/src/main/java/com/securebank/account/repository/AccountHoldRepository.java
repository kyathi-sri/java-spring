package com.securebank.account.repository;

import com.securebank.account.domain.AccountHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * No separate hold lock is needed: every hold mutation first locks the owning account row
 * ({@code SELECT ... FOR UPDATE}), which serializes all hold changes for that account.
 */
public interface AccountHoldRepository extends JpaRepository<AccountHold, UUID> {

    Optional<AccountHold> findByReference(String reference);
}
