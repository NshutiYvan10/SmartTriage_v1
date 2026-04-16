package com.smartTriage.smartTriage_server.module.hospital.service;

import com.smartTriage.smartTriage_server.common.exception.DuplicateResourceException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hospital.dto.CreateHospitalRequest;
import com.smartTriage.smartTriage_server.module.hospital.dto.HospitalResponse;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.mapper.HospitalMapper;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalService {

    private final HospitalRepository hospitalRepository;

    @Transactional
    public HospitalResponse createHospital(CreateHospitalRequest request) {
        if (hospitalRepository.existsByHospitalCode(request.getHospitalCode())) {
            throw new DuplicateResourceException("Hospital", "hospitalCode", request.getHospitalCode());
        }

        Hospital hospital = HospitalMapper.toEntity(request);
        hospital = hospitalRepository.save(hospital);

        log.info("Hospital created: {} ({})", hospital.getName(), hospital.getHospitalCode());
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
