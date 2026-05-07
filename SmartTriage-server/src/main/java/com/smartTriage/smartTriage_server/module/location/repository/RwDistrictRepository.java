package com.smartTriage.smartTriage_server.module.location.repository;

import com.smartTriage.smartTriage_server.module.location.entity.RwDistrict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RwDistrictRepository extends JpaRepository<RwDistrict, UUID> {
    List<RwDistrict> findByProvinceIdOrderByNameAsc(UUID provinceId);
    Optional<RwDistrict> findByCode(String code);
}
