package com.riskdesk.infrastructure.quant.simulation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface Quant7GatesSimulationJpaRepository
    extends JpaRepository<Quant7GatesSimulationEntity, Long> {

    List<Quant7GatesSimulationEntity> findByStatus(String status);

    List<Quant7GatesSimulationEntity> findByStatusNot(String status);

    @Query("select coalesce(max(e.id), 0) from Quant7GatesSimulationEntity e")
    long findMaxId();
}
