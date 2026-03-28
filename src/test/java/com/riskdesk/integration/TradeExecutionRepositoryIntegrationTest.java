package com.riskdesk.integration;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.persistence.JpaTradeExecutionRepositoryAdapter;
import com.riskdesk.infrastructure.persistence.TradeExecutionJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaTradeExecutionRepositoryAdapter.class)
class TradeExecutionRepositoryIntegrationTest {

    @Autowired
    private JpaTradeExecutionRepositoryAdapter adapter;

    @Autowired
    private TradeExecutionJpaRepository repository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void createIfAbsent_returnsExistingRowOnSequentialDuplicate() {
        TradeExecutionRecord first = adapter.createIfAbsent(execution(91L));
        TradeExecutionRecord second = adapter.createIfAbsent(execution(91L));

        assertThat(repository.count()).isEqualTo(1);
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createIfAbsent_isIdempotentUnderConcurrentCalls() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<TradeExecutionRecord> task = () -> {
            ready.countDown();
            start.await();
            return adapter.createIfAbsent(execution(99L));
        };

        Future<TradeExecutionRecord> firstFuture = pool.submit(task);
        Future<TradeExecutionRecord> secondFuture = pool.submit(task);

        ready.await();
        start.countDown();

        TradeExecutionRecord first = firstFuture.get();
        TradeExecutionRecord second = secondFuture.get();
        pool.shutdownNow();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(first.getId()).isEqualTo(second.getId());
    }

    @Test
    void save_usesVersionForOptimisticLocking() {
        TradeExecutionRecord created = adapter.createIfAbsent(execution(105L));

        TradeExecutionRecord first = adapter.findById(created.getId()).orElseThrow();
        entityManager.clear();
        TradeExecutionRecord stale = adapter.findById(created.getId()).orElseThrow();
        entityManager.clear();

        first.setStatusReason("first update");
        first.setUpdatedAt(Instant.parse("2026-03-28T17:01:00Z"));
        adapter.save(first);

        entityManager.clear();

        stale.setStatusReason("stale update");
        stale.setUpdatedAt(Instant.parse("2026-03-28T17:02:00Z"));

        assertThatThrownBy(() -> adapter.save(stale))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void findByMentorSignalReviewIds_returnsOnlyMatchingExecutions() {
        adapter.createIfAbsent(execution(201L));
        adapter.createIfAbsent(execution(202L));
        adapter.createIfAbsent(execution(203L));

        List<TradeExecutionRecord> found = adapter.findByMentorSignalReviewIds(List.of(202L, 204L, 201L));

        assertThat(found)
            .extracting(TradeExecutionRecord::getMentorSignalReviewId)
            .containsExactlyInAnyOrder(201L, 202L);
    }

    private TradeExecutionRecord execution(Long reviewId) {
        TradeExecutionRecord record = new TradeExecutionRecord();
        record.setExecutionKey("exec:mentor-review:" + reviewId);
        record.setMentorSignalReviewId(reviewId);
        record.setReviewAlertKey("2026-03-28T16:00:00Z:MNQ:SMC:CHoCH");
        record.setReviewRevision(1);
        record.setBrokerAccountId("DU1234567");
        record.setInstrument("MNQ");
        record.setTimeframe("10m");
        record.setAction("LONG");
        record.setQuantity(1);
        record.setTriggerSource(ExecutionTriggerSource.MANUAL_ARMING);
        record.setRequestedBy("integration-test");
        record.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        record.setStatusReason("Execution foundation created.");
        record.setNormalizedEntryPrice(new BigDecimal("18123.50"));
        record.setVirtualStopLoss(new BigDecimal("18099.75"));
        record.setVirtualTakeProfit(new BigDecimal("18160.25"));
        record.setCreatedAt(Instant.parse("2026-03-28T17:00:00Z"));
        record.setUpdatedAt(Instant.parse("2026-03-28T17:00:00Z"));
        return record;
    }
}
