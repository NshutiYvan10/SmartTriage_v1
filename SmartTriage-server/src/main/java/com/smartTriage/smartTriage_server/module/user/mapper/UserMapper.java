package com.smartTriage.smartTriage_server.module.user.mapper;

import com.smartTriage.smartTriage_server.module.user.dto.UserResponse;
import com.smartTriage.smartTriage_server.module.user.entity.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole())
                .designation(user.getDesignation())
                .designationLabel(user.getDesignation() != null ? user.getDesignation().getLabel() : null)
                .employeeNumber(user.getEmployeeNumber())
                .professionalLicense(user.getProfessionalLicense())
                .department(user.getDepartment())
                .hospitalId(user.getHospital().getId())
                .hospitalName(user.getHospital().getName())
                .accountStatus(user.getAccountStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
