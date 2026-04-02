package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.marketdata.model.DxySnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DxySnapshotRepositoryPort {

    DxySnapshot save(DxySnapshot snapshot);

    Optional<DxySnapshot> findLatestComplete();

    Optional<DxySnapshot> findLatestCompleteAtOrBefore(Instant cutoff);

    List<DxySnapshot> findCompleteBetween(Instant from, Instant to);
}
