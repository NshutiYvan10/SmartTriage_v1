package com.smartTriage.smartTriage_server.module.hospital.service;

import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.bed.service.BedService;
import com.smartTriage.smartTriage_server.module.hospital.dto.CreateHospitalRequest;
import com.smartTriage.smartTriage_server.module.hospital.dto.HospitalResponse;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.mapper.HospitalMapper;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
public class HospitalService {

    private final HospitalRepository hospitalRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.smartTriage.smartTriage_server.module.location.repository.RwProvinceRepository rwProvinceRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.smartTriage.smartTriage_server.module.location.repository.RwDistrictRepository rwDistrictRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.smartTriage.smartTriage_server.module.location.repository.RwSectorRepository rwSectorRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.smartTriage.smartTriage_server.module.location.repository.RwCellRepository rwCellRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.smartTriage.smartTriage_server.module.location.repository.RwVillageRepository rwVillageRepository;

    /**
     * BedService is injected lazily to break a constructor-time cycle:
     * BedService depends on HospitalService for {@link #findHospitalOrThrow(UUID)},
     * and (since Phase G #4) HospitalService depends on BedService for the
     * auto-seed-on-create hook. {@code @Lazy} resolves the proxy at first
     * use, which is always inside a transactional method — well after both
     * beans are fully constructed.
     */
    private final BedService bedService;

    public HospitalService(HospitalRepository hospitalRepository,
                           @Lazy @Autowired BedService bedService) {
        this.hospitalRepository = hospitalRepository;
        this.bedService = bedService;
    }

    @Transactional
    public HospitalResponse createHospital(CreateHospitalRequest request) {
        if (hospitalRepository.existsByHospitalCode(request.getHospitalCode())) {
            throw new DuplicateResourceException("Hospital", "hospitalCode", request.getHospitalCode());
        }

        Hospital hospital = HospitalMapper.toEntity(request);
        // Resolve structured location IDs (any subset) into entity refs.
        if (request.getProvinceId() != null) {
            rwProvinceRepository.findById(request.getProvinceId())
                    .ifPresent(hospital::setProvinceRef);
        }
        if (request.getDistrictId() != null) {
            rwDistrictRepository.findById(request.getDistrictId())
                    .ifPresent(hospital::setDistrictRef);
        }
        if (request.getSectorId() != null) {
            rwSectorRepository.findById(request.getSectorId())
                    .ifPresent(hospital::setSectorRef);
        }
        if (request.getCellId() != null) {
            rwCellRepository.findById(request.getCellId())
                    .ifPresent(hospital::setCellRef);
        }
        if (request.getVillageId() != null) {
            rwVillageRepository.findById(request.getVillageId())
                    .ifPresent(hospital::setVillageRef);
        }
        hospital = hospitalRepository.save(hospital);

        log.info("Hospital created: {} ({})", hospital.getName(), hospital.getHospitalCode());

        // Phase G #4 — auto-seed the default bed inventory so the hospital
        // can immediately accept triaged patients. Wrapped in try/catch so
        // a seed failure doesn't roll back the hospital itself: the admin
        // always has POST /api/v1/beds/hospital/{id}/seed-defaults as
        // recovery, and the seed call is idempotent per zone.
        try {
            BedService.SeedResult result = bedService.seedDefaultBedsForHospital(hospital.getId());
            log.info("Auto-seeded {} beds for new hospital {} (tier={}, zones={})",
                    result.bedsCreated(), hospital.getHospitalCode(),
                    result.tierUsed(), result.zonesSeeded().size());
        } catch (Exception e) {
            log.error("Auto-seed failed for hospital {} ({}): {} — admin can backfill via "
                    + "POST /api/v1/beds/hospital/{}/seed-defaults",
                    hospital.getHospitalCode(), hospital.getId(), e.getMessage(),
                    hospital.getId(), e);
        }

        return HospitalMapper.toResponse(hospital);
    }

    public HospitalResponse getHospitalById(UUID id) {
        Hospital hospital = findHospitalOrThrow(id);
        return HospitalMapper.toResponse(hospital);
    }

    public HospitalResponse getHospitalByCode(String code) {
        Hospital hospital = hospitalRepository.findByHospitalCodeAndIsActiveTrue(code)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "hospitalCode", code));
        return HospitalMapper.toResponse(hospital);
    }

    public Page<HospitalResponse> getAllHospitals(Pageable pageable) {
        return hospitalRepository.findAll(pageable)
                .map(HospitalMapper::toResponse);
    }

    @Transactional
    public void deactivateHospital(UUID id) {
        Hospital hospital = findHospitalOrThrow(id);
        hospital.softDelete();
        hospitalRepository.save(hospital);
        log.info("Hospital deactivated: {}", hospital.getHospitalCode());
    }

    /**
     * Internal method — used by other services to resolve hospital entity.
     */
    public Hospital findHospitalOrThrow(UUID id) {
        return hospitalRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", id));
    }
}
