package com.securebank.account.repository;

import com.securebank.account.domain.AccountCredit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountCreditRepository extends JpaRepository<AccountCredit, Long> {

    Optional<AccountCredit> findByReference(String reference);
}
