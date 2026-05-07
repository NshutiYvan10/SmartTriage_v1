package com.smartTriage.smartTriage_server.module.location.repository;

import com.smartTriage.smartTriage_server.module.location.entity.RwSector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RwSectorRepository extends JpaRepository<RwSector, UUID> {
    List<RwSector> findByDistrictIdOrderByNameAsc(UUID districtId);
    Optional<RwSector> findByCode(String code);
}
