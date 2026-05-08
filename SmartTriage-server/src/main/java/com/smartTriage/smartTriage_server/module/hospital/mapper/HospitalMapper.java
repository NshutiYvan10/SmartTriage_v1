package com.smartTriage.smartTriage_server.module.hospital.mapper;

import com.smartTriage.smartTriage_server.module.hospital.dto.CreateHospitalRequest;
import com.smartTriage.smartTriage_server.module.hospital.dto.HospitalResponse;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;

/**
 * Maps between Hospital entity and DTOs.
 * No entity exposure in API responses — this is non-negotiable in healthcare systems.
 */
public final class HospitalMapper {

    private HospitalMapper() {}

    public static Hospital toEntity(CreateHospitalRequest request) {
        return Hospital.builder()
                .name(request.getName())
                .hospitalCode(request.getHospitalCode())
                .address(request.getAddress())
                .city(request.getCity())
                .province(request.getProvince())
                .country(request.getCountry())
                .phoneNumber(request.getPhoneNumber())
                .email(request.getEmail())
                .tier(request.getTier())
                .bedCapacity(request.getBedCapacity())
                .edCapacity(request.getEdCapacity())
                .icuCapacity(request.getIcuCapacity())
                .hasPediatricResus(Boolean.TRUE.equals(request.getHasPediatricResus()))
                .hasNeonatalUnit(Boolean.TRUE.equals(request.getHasNeonatalUnit()))
                .twoStepVerificationEnabled(Boolean.TRUE.equals(request.getTwoStepVerificationEnabled()))
                .build();
    }

    public static HospitalResponse toResponse(Hospital hospital) {
        return HospitalResponse.builder()
                .id(hospital.getId())
                .name(hospital.getName())
                .hospitalCode(hospital.getHospitalCode())
                .address(hospital.getAddress())
                .city(hospital.getCity())
                .province(hospital.getProvince())
                .country(hospital.getCountry())
                .phoneNumber(hospital.getPhoneNumber())
                .email(hospital.getEmail())
                .tier(hospital.getTier())
                .bedCapacity(hospital.getBedCapacity())
                .edCapacity(hospital.getEdCapacity())
                .icuCapacity(hospital.getIcuCapacity())
                .hasPediatricResus(hospital.isHasPediatricResus())
                .hasNeonatalUnit(hospital.isHasNeonatalUnit())
                .twoStepVerificationEnabled(hospital.isTwoStepVerificationEnabled())
                .active(hospital.isActive())
                .provinceId(hospital.getProvinceRef() != null ? hospital.getProvinceRef().getId() : null)
                .districtId(hospital.getDistrictRef() != null ? hospital.getDistrictRef().getId() : null)
                .sectorId(hospital.getSectorRef() != null ? hospital.getSectorRef().getId() : null)
                .cellId(hospital.getCellRef() != null ? hospital.getCellRef().getId() : null)
                .villageId(hospital.getVillageRef() != null ? hospital.getVillageRef().getId() : null)
                .createdAt(hospital.getCreatedAt())
                .updatedAt(hospital.getUpdatedAt())
                .build();
    }
}
