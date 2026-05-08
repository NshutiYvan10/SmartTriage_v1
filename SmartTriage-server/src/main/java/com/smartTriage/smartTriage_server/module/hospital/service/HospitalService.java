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
        // Auto-generate the hospital code when the request omits one.
        // Operators can still supply a specific code (to mirror an
        // external identifier); when they don't, we synthesize a
        // human-readable one from the name plus a numeric suffix to
        // break ties when two hospitals abbreviate to the same letters
        // (e.g. Kibagabaga FH and King Faisal H both initial as KFH).
        // The code is final once persisted; renaming would orphan
        // external joins and isn't exposed through the edit flow.
        String suppliedCode = request.getHospitalCode();
        String resolvedCode = (suppliedCode == null || suppliedCode.isBlank())
                ? generateUniqueHospitalCode(request.getName())
                : suppliedCode.trim();
        if (hospitalRepository.existsByHospitalCode(resolvedCode)) {
            throw new DuplicateResourceException("Hospital", "hospitalCode", resolvedCode);
        }
        request.setHospitalCode(resolvedCode);

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
     * Reactivate a previously-deactivated hospital. Lookup uses the
     * unfiltered findById (the standard {@link #findHospitalOrThrow}
     * is is_active=true-scoped so it would 404 on a deactivated row).
     */
    @Transactional
    public HospitalResponse reactivateHospital(UUID id) {
        Hospital hospital = hospitalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", id));
        if (hospital.isActive()) {
            return HospitalMapper.toResponse(hospital);
        }
        hospital.setActive(true);
        hospital = hospitalRepository.save(hospital);
        log.info("Hospital reactivated: {}", hospital.getHospitalCode());
        return HospitalMapper.toResponse(hospital);
    }

    /**
     * Update an existing hospital. Only fields the caller actually
     * passes (non-null) are applied; the {@code hospitalCode} is
     * deliberately not updateable here — see UpdateHospitalRequest
     * Javadoc for why. Structured-location IDs follow the same
     * any-subset rule as create.
     */
    @Transactional
    public HospitalResponse updateHospital(UUID id, com.smartTriage.smartTriage_server.module.hospital.dto.UpdateHospitalRequest request) {
        Hospital hospital = findHospitalOrThrow(id);
        if (request.getName() != null)         hospital.setName(request.getName());
        if (request.getAddress() != null)      hospital.setAddress(request.getAddress());
        if (request.getCity() != null)         hospital.setCity(request.getCity());
        if (request.getProvince() != null)     hospital.setProvince(request.getProvince());
        if (request.getCountry() != null)      hospital.setCountry(request.getCountry());
        if (request.getPhoneNumber() != null)  hospital.setPhoneNumber(request.getPhoneNumber());
        if (request.getEmail() != null)        hospital.setEmail(request.getEmail());
        if (request.getTier() != null)         hospital.setTier(request.getTier());
        if (request.getBedCapacity() != null)  hospital.setBedCapacity(request.getBedCapacity());
        if (request.getEdCapacity() != null)   hospital.setEdCapacity(request.getEdCapacity());
        if (request.getIcuCapacity() != null)  hospital.setIcuCapacity(request.getIcuCapacity());
        if (request.getHasPediatricResus() != null) hospital.setHasPediatricResus(request.getHasPediatricResus());
        if (request.getHasNeonatalUnit() != null)   hospital.setHasNeonatalUnit(request.getHasNeonatalUnit());
        if (request.getTwoStepVerificationEnabled() != null) hospital.setTwoStepVerificationEnabled(request.getTwoStepVerificationEnabled());

        // Structured location: null on the request means "no change",
        // explicit-empty UUID isn't valid so we don't model "clear".
        if (request.getProvinceId() != null) {
            rwProvinceRepository.findById(request.getProvinceId()).ifPresent(hospital::setProvinceRef);
        }
        if (request.getDistrictId() != null) {
            rwDistrictRepository.findById(request.getDistrictId()).ifPresent(hospital::setDistrictRef);
        }
        if (request.getSectorId() != null) {
            rwSectorRepository.findById(request.getSectorId()).ifPresent(hospital::setSectorRef);
        }
        if (request.getCellId() != null) {
            rwCellRepository.findById(request.getCellId()).ifPresent(hospital::setCellRef);
        }
        if (request.getVillageId() != null) {
            rwVillageRepository.findById(request.getVillageId()).ifPresent(hospital::setVillageRef);
        }

        hospital = hospitalRepository.save(hospital);
        log.info("Hospital updated: {} ({})", hospital.getName(), hospital.getHospitalCode());
        return HospitalMapper.toResponse(hospital);
    }

    /**
     * Internal method — used by other services to resolve hospital entity.
     */
    public Hospital findHospitalOrThrow(UUID id) {
        return hospitalRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", id));
    }

    /**
     * Build a unique hospital code from the supplied name. Strategy:
     *
     *   1. Strip the name to ASCII letters and split on whitespace.
     *   2. Take the initial of each significant word (skipping common
     *      stop-words like "the", "of", "hospital", "medical", "centre"
     *      / "center" so "King Faisal Hospital" ends up KFH and not
     *      KFHM or KFHC).
     *   3. Truncate to 4 letters max so the prefix is always speakable.
     *   4. Append a 3-digit zero-padded counter (-001, -002, …) and
     *      walk it forward until {@link HospitalRepository#existsByHospitalCode}
     *      returns false. The first hit is the chosen code.
     *
     * Falls back to "HOSP-001" style when the name has no recognisable
     * initials (rare but possible if the operator types only special
     * characters).
     */
    private String generateUniqueHospitalCode(String name) {
        java.util.Set<String> stopWords = java.util.Set.of(
                "THE", "OF", "AND", "HOSPITAL", "MEDICAL", "CENTER", "CENTRE",
                "CLINIC", "CLINICS", "HEALTH", "REFERRAL");
        String prefix = name == null
                ? "HOSP"
                : java.util.Arrays.stream(name.replaceAll("[^A-Za-z\\s]", " ").split("\\s+"))
                        .map(String::toUpperCase)
                        .filter(w -> !w.isBlank())
                        .filter(w -> !stopWords.contains(w))
                        .map(w -> w.substring(0, 1))
                        .reduce("", String::concat);
        if (prefix.isEmpty()) prefix = "HOSP";
        if (prefix.length() > 4) prefix = prefix.substring(0, 4);

        // Walk the suffix until we find a free slot. Bounded scan —
        // 999 hospitals sharing a 4-letter prefix is far beyond
        // anything realistic in a single deployment.
        for (int i = 1; i < 1000; i++) {
            String candidate = String.format("%s-%03d", prefix, i);
            if (candidate.length() > 20) {
                // Shouldn't happen with a 4-char prefix + "-" + 3 digits = 8 chars,
                // but guard anyway in case the stop-word filter ever changes.
                candidate = candidate.substring(0, 20);
            }
            if (!hospitalRepository.existsByHospitalCode(candidate)) {
                return candidate;
            }
        }
        // Astronomically unlikely; if we ever get here, fall back to
        // a UUID-suffix code so creation still succeeds.
        return (prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 4)).toUpperCase();
    }
}
