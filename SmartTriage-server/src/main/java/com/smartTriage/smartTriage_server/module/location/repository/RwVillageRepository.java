package com.smartTriage.smartTriage_server.module.location.repository;

import com.smartTriage.smartTriage_server.module.location.entity.RwVillage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RwVillageRepository extends JpaRepository<RwVillage, UUID> {
    List<RwVillage> findByCellIdOrderByNameAsc(UUID cellId);
    Optional<RwVillage> findByCode(String code);
}
