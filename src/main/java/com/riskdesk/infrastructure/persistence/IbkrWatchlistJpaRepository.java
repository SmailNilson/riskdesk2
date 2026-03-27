package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.IbkrWatchlistEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IbkrWatchlistJpaRepository extends JpaRepository<IbkrWatchlistEntity, Long> {
}
