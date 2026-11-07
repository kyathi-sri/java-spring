package com.securebank.transfer.service;

import com.securebank.transfer.config.TransferProperties;
import com.securebank.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Finishes every transfer that is not yet COMPLETED/FAILED: those whose retry is due, those left
 * behind by a crashed worker (expired lease), and those created just before a crash. This is what
 * guarantees every transfer eventually reaches a terminal, consistent state.
 */
@Component
public class TransferRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(TransferRecoveryJob.class);

    private final TransferRepository transfers;
    private final TransferSaga saga;
    private final TransferProperties props;

    public TransferRecoveryJob(TransferRepository transfers, TransferSaga saga, TransferProperties props) {
        this.transfers = transfers;
        this.saga = saga;
        this.props = props;
    }

    /** @return number of transfers picked up */
    public int runOnce() {
        List<UUID> due = transfers.findDue(Instant.now(), props.recovery().batchSize());
        for (UUID id : due) {
            try {
                saga.advance(id);
            } catch (RuntimeException e) {
                log.error("Recovery of transfer {} failed; it will be picked up again", id, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Recovery pass processed {} transfer(s)", due.size());
        }
        return due.size();
    }
}
