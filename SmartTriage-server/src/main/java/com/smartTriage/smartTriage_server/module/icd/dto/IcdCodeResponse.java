package com.smartTriage.smartTriage_server.module.icd.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcdCodeResponse {
    private UUID id;
    private String code;
    private String description;
    private String category;
    private boolean isCommonInRwanda;
    private String clinicalNotes;
}
