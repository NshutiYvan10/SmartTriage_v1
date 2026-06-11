package com.smartTriage.smartTriage_server.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Prescription type — the administration pattern of a medication order
 * (Medication Management module, V67).
 *
 * <p>Determines the dose-level workflow:
 * <ul>
 *   <li>{@link #ONE_TIME} — a single dose, given once and stopped.
 *       E.g. "Morphine 4 mg IV once". Legacy orders (rows created
 *       before V67 carry a NULL prescription_type) behave as ONE_TIME.</li>
 *   <li>{@link #SCHEDULED} — recurring at a fixed interval until the
 *       order is discontinued, completes its duration / max doses, or
 *       the patient is discharged. E.g. "Ceftriaxone 1 g IV q24h".
 *       Each administration auto-creates the next DUE dose.</li>
 *   <li>{@link #PRN} — given only when a clinical condition occurs
 *       (pain, nausea, …). No auto-generated doses; the nurse records
 *       each administration, gated by a minimum interval, an optional
 *       max-per-24h, and an optional structured vitals gate.</li>
 *   <li>{@link #CONTINUOUS} — uninterrupted administration, typically
 *       an IV infusion at a rate ("Normal saline at 100 mL/hr"). The
 *       nurse confirms initiation; rate changes and the stop are each
 *       recorded as events on the order's dose timeline.</li>
 * </ul>
 *
 * <p>Orthogonal to {@link MedicationProductType}: a blood transfusion
 * is productType=BLOOD_PRODUCT with (usually) ONE_TIME or CONTINUOUS
 * administration.
 */
@Getter
@RequiredArgsConstructor
public enum PrescriptionType {

    ONE_TIME("One-time", "Single dose, given once"),
    SCHEDULED("Scheduled", "Recurring at a fixed interval"),
    PRN("PRN", "As needed, when a clinical condition occurs"),
    CONTINUOUS("Continuous", "Uninterrupted administration (infusion)");

    private final String label;
    private final String description;
}
