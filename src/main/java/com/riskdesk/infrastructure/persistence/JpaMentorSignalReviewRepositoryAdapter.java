package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.analysis.port.MentorSignalReviewRepositoryPort;
import com.riskdesk.domain.model.MentorSignalReviewRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaMentorSignalReviewRepositoryAdapter implements MentorSignalReviewRepositoryPort {

    private final MentorSignalReviewJpaRepository repository;

    public JpaMentorSignalReviewRepositoryAdapter(MentorSignalReviewJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public MentorSignalReviewRecord save(MentorSignalReviewRecord review) {
        return MentorSignalReviewEntityMapper.toDomain(repository.save(MentorSignalReviewEntityMapper.toEntity(review)));
    }

    @Override
    public Optional<MentorSignalReviewRecord> findById(Long id) {
        return repository.findById(id).map(MentorSignalReviewEntityMapper::toDomain);
    }

    @Override
    public boolean existsByAlertKey(String alertKey) {
        return repository.existsByAlertKey(alertKey);
    }

    @Override
    public List<MentorSignalReviewRecord> findByAlertKeyOrderByRevisionAsc(String alertKey) {
        return repository.findByAlertKeyOrderByRevisionAsc(alertKey).stream()
            .map(MentorSignalReviewEntityMapper::toDomain)
            .toList();
    }

    @Override
    public Optional<MentorSignalReviewRecord> findLatestByAlertKey(String alertKey) {
        return repository.findFirstByAlertKeyOrderByRevisionDesc(alertKey).map(MentorSignalReviewEntityMapper::toDomain);
    }

    @Override
    public List<MentorSignalReviewRecord> findRecent(int limit) {
        return repository.findAll(
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
            .stream()
            .map(MentorSignalReviewEntityMapper::toDomain)
            .toList();
    }
}
