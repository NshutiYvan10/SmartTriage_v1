package com.smartTriage.smartTriage_server.module.labcatalog.repository;

import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import com.smartTriage.smartTriage_server.module.labcatalog.entity.LabTestCatalog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LabTestCatalogRepository extends JpaRepository<LabTestCatalog, UUID> {

    /**
     * Search by test_name or short_name. Common-in-Rwanda first so the
     * routine ED panel surfaces before less-frequent tests.
     */
    @Query("SELECT t FROM LabTestCatalog t WHERE t.isActive = true " +
           "AND (LOWER(t.testName) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(t.shortName) LIKE LOWER(CONCAT('%', :query, '%'))) " +
           "ORDER BY t.isCommonInRwanda DESC, t.testName ASC")
    List<LabTestCatalog> searchActive(@Param("query") String query);

    @Query("SELECT t FROM LabTestCatalog t WHERE t.isActive = true " +
           "AND t.investigationType = :type " +
           "ORDER BY t.isCommonInRwanda DESC, t.testName ASC")
    List<LabTestCatalog> findByType(@Param("type") InvestigationType type);

    List<LabTestCatalog> findByIsCommonInRwandaTrueAndIsActiveTrueOrderByTestNameAsc();
}
