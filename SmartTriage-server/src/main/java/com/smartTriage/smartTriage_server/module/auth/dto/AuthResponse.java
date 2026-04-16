package com.smartTriage.smartTriage_server.module.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;
    private String role;
    private String designation;
    private String designationLabel;
    private UUID hospitalId;
    private String hospitalName;
}
