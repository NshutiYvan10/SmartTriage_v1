package com.smartTriage.smartTriage_server.module.location.repository;

import com.smartTriage.smartTriage_server.module.location.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-file holder for the five Rwanda-location lookup repositories.
 * They're tiny, parallel, and never used independently of each other —
 * keeping them together makes the module easier to read than five
 * separate single-method files.
 */
public class RwLocationRepositories {
    private RwLocationRepositories() {}

    @Repository
    public interface RwProvinceRepository extends JpaRepository<RwProvince, UUID> {
        List<RwProvince> findAllByOrderByDisplayOrderAscNameAsc();
        Optional<RwProvince> findByCode(String code);
    }

    @Repository
    public interface RwDistrictRepository extends JpaRepository<RwDistrict, UUID> {
        List<RwDistrict> findByProvinceIdOrderByNameAsc(UUID provinceId);
        Optional<RwDistrict> findByCode(String code);
    }

    @Repository
    public interface RwSectorRepository extends JpaRepository<RwSector, UUID> {
        List<RwSector> findByDistrictIdOrderByNameAsc(UUID districtId);
        Optional<RwSector> findByCode(String code);
    }

    @Repository
    public interface RwCellRepository extends JpaRepository<RwCell, UUID> {
        List<RwCell> findBySectorIdOrderByNameAsc(UUID sectorId);
        Optional<RwCell> findByCode(String code);
    }

    @Repository
    public interface RwVillageRepository extends JpaRepository<RwVillage, UUID> {
        List<RwVillage> findByCellIdOrderByNameAsc(UUID cellId);
        Optional<RwVillage> findByCode(String code);
    }
}
