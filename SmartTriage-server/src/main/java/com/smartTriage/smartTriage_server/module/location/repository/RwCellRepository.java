package com.smartTriage.smartTriage_server.module.location.repository;

import com.smartTriage.smartTriage_server.module.location.entity.RwCell;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RwCellRepository extends JpaRepository<RwCell, UUID> {
    List<RwCell> findBySectorIdOrderByNameAsc(UUID sectorId);
    Optional<RwCell> findByCode(String code);
}
