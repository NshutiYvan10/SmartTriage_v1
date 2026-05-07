package com.smartTriage.smartTriage_server.module.location.repository;

import com.smartTriage.smartTriage_server.module.location.entity.RwProvince;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RwProvinceRepository extends JpaRepository<RwProvince, UUID> {
    List<RwProvince> findAllByOrderByDisplayOrderAscNameAsc();
    Optional<RwProvince> findByCode(String code);
}
