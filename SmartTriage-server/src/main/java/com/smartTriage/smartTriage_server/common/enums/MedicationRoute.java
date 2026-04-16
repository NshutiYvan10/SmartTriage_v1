package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Medication administration routes — standard clinical routes
 * as documented on Rwandan triage form medication logs.
 */
@Getter
@RequiredArgsConstructor
public enum MedicationRoute {

    PO("Oral (PO)"),
    IV("Intravenous (IV)"),
    IM("Intramuscular (IM)"),
    SC("Subcutaneous (SC)"),
    SL("Sublingual (SL)"),
    PR("Per Rectum (PR)"),
    INH("Inhalation (INH)"),
    NEB("Nebuliser (NEB)"),
    TOP("Topical (TOP)"),
    NASAL("Nasal"),
    OPHTHALMIC("Ophthalmic"),
    OTIC("Otic / Ear"),
    ETT("Endotracheal (ETT)"),
    IO("Intraosseous (IO)"),
    OTHER("Other");

    private final String description;
}
