package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Why a lab specimen was rejected on receipt. Mirrors the CHECK
 * constraint on {@code lab_orders.rejection_reason}.
 */
@Getter
@RequiredArgsConstructor
public enum SpecimenRejectionReason {
    HAEMOLYSED("Haemolysed sample — red cells lysed, K+ falsely elevated"),
    CLOTTED("Clotted sample — anticoagulant tube needed"),
    INSUFFICIENT_VOLUME("Insufficient volume for assay"),
    MISLABELLED("Mislabelled or unlabelled — patient identity cannot be confirmed"),
    WRONG_CONTAINER("Wrong container / additive for this assay"),
    EXPIRED("Sample exceeded stability window"),
    OTHER("Other");

    private final String description;
}
