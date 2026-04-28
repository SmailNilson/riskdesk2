package com.riskdesk.domain.externalsetup.port;

import com.riskdesk.domain.externalsetup.ExternalSetup;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExternalSetupRepositoryPort {

    ExternalSetup save(ExternalSetup setup);

    Optional<ExternalSetup> findById(Long id);

    Optional<ExternalSetup> findBySetupKey(String setupKey);

    /** All setups in the given statuses, newest first. */
    List<ExternalSetup> findByStatuses(List<ExternalSetupStatus> statuses, int limit);

    /** Pending setups whose {@code expiresAt} is on or before {@code clock}. */
    List<ExternalSetup> findPendingExpiredAt(Instant clock, int limit);

    /** Latest pending submissions for a given instrument (used to enforce per-token rate limiting). */
    List<ExternalSetup> findRecentByInstrument(Instrument instrument, Instant since, int limit);
}
