package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.IbkrWatchlist;
import com.riskdesk.domain.trading.port.IbkrWatchlistRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class JpaIbkrWatchlistRepositoryAdapter implements IbkrWatchlistRepositoryPort {

    private final IbkrWatchlistJpaRepository repository;

    public JpaIbkrWatchlistRepositoryAdapter(IbkrWatchlistJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<IbkrWatchlist> findAll() {
        return repository.findAll().stream()
            .map(IbkrWatchlistEntityMapper::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public List<IbkrWatchlist> replaceAll(List<IbkrWatchlist> watchlists) {
        repository.deleteAll();
        repository.flush();
        return repository.saveAll(watchlists.stream().map(IbkrWatchlistEntityMapper::toEntity).toList()).stream()
            .map(IbkrWatchlistEntityMapper::toDomain)
            .toList();
    }
}
