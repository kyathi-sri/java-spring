package com.securebank.transfer.repository;

import com.securebank.transfer.domain.TransferHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransferHistoryRepository extends JpaRepository<TransferHistory, Long> {

    List<TransferHistory> findByTransferIdOrderByIdAsc(UUID transferId);
}
