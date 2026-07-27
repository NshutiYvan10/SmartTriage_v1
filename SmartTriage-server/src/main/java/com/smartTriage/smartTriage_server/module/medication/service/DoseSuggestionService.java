package com.smartTriage.smartTriage_server.module.medication.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.medication.dto.DoseSuggestionResponse;
import com.smartTriage.smartTriage_server.module.medsafety.entity.DrugFormulary;
import com.smartTriage.smartTriage_server.module.medsafety.repository.DrugFormularyRepository;
import com.smartTriage.smartTriage_server.module.triage.entity.TriageRecord;
import com.smartTriage.smartTriage_server.module.triage.repository.TriageRecordRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.repository.VisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DoseSuggestionService — computes an editable, clinically standard
 * starting prescription for a drug (or IV fluid) and a specific patient,
 * from facts the system already holds: age, measured weight (triage
 * child weight → patient record), pregnancy-relevant flags on the
 * formulary row, and the V118 typical-dose fields.
 *
 * <p>Principles:
 * <ul>
 *   <li><b>Suggest, never decide.</b> Output pre-fills the form; the
 *       doctor edits or accepts. The safety engine still validates the
 *       final submission — suggestion and policing share one formulary
 *       so they can never disagree about bounds.</li>
 *   <li><b>Show the arithmetic.</b> Every number comes with the formula
 *       that produced it ("15 mg/kg × 14 kg = 210 mg").</li>
 *   <li><b>Estimated weight is loud.</b> When no measured weight exists
 *       for a child, the APLS age-band estimate is used and flagged as
 *       an estimate that must be confirmed.</li>
 *   <li><b>Caps are hard.</b> A paediatric weight-based dose is capped
 *       at the paediatric per-dose max AND at the adult typical dose —
 *       a 60 kg 12-year-old must not out-dose an adult.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoseSuggestionService {

    private final VisitRepository visitRepository;
    private final TriageRecordRepository triageRecordRepository;
    private final DrugFormularyRepository formularyRepository;

    public enum FluidPurpose { MAINTENANCE, BOLUS }

    // ====================================================================
    // DRUG SUGGESTION
    // ====================================================================

    public DoseSuggestionResponse suggestForDrug(UUID visitId, UUID formularyId) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));
        DrugFormulary drug = formularyRepository.findByIdAndIsActiveTrue(formularyId)
                .orElseThrow(() -> new ResourceNotFoundException("DrugFormulary", "id", formularyId));

        List<String> rationale = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Weight weight = resolveWeight(visit, rationale, warnings);
        Integer ageYears = ageYears(visit);
        boolean pediatric = visit.isPediatric();

        Double dose = null;
        if (pediatric) {
            Double mgPerKg = drug.getTypicalPediatricDoseMgPerKg() != null
                    ? drug.getTypicalPediatricDoseMgPerKg()
                    : midpoint(drug.getPediatricMinDoseMgPerKg(), drug.getPediatricMaxDoseMgPerKg());
            if (mgPerKg != null && weight.kg != null) {
                double raw = mgPerKg * weight.kg;
                rationale.add(String.format("%s mg/kg × %s kg = %s mg",
                        trim(mgPerKg), trim(weight.kg), trim(raw)));
                // Hard caps: paediatric per-dose max, then the adult dose.
                if (drug.getPediatricMaxDoseMgPerKg() != null) {
                    double pedsCap = drug.getPediatricMaxDoseMgPerKg() * weight.kg;
                    if (raw > pedsCap) {
                        raw = pedsCap;
                        rationale.add(String.format("Capped at paediatric max %s mg/kg → %s mg",
                                trim(drug.getPediatricMaxDoseMgPerKg()), trim(raw)));
                    }
                }
                Double adultCap = drug.getTypicalAdultDoseMg() != null
                        ? drug.getTypicalAdultDoseMg() : drug.getAdultMaxDoseMg();
                if (adultCap != null && raw > adultCap) {
                    raw = adultCap;
                    rationale.add(String.format("Capped at adult dose %s mg", trim(adultCap)));
                }
                dose = roundDose(raw);
                if (!dose.equals(raw)) {
                    rationale.add(String.format("Rounded to practical dose %s mg", trim(dose)));
                }
            } else if (mgPerKg != null) {
                warnings.add("No weight available — record the child's weight to compute a mg/kg dose.");
            } else if (drug.getTypicalAdultDoseMg() != null) {
                warnings.add("No paediatric dosing data for this drug — adult dose shown; verify suitability.");
                dose = drug.getTypicalAdultDoseMg();
            }
        } else {
            dose = drug.getTypicalAdultDoseMg() != null
                    ? drug.getTypicalAdultDoseMg()
                    : midpoint(drug.getAdultMinDoseMg(), drug.getAdultMaxDoseMg());
            if (dose != null) {
                rationale.add(String.format("Standard adult dose %s mg", trim(dose)));
                if (ageYears != null && ageYears >= 65 && drug.getGeriatricAdjustmentPercent() != null) {
                    double adjusted = roundDose(dose * (1 - drug.getGeriatricAdjustmentPercent() / 100.0));
                    rationale.add(String.format("Geriatric adjustment −%s%% (age %d) → %s mg",
                            trim(drug.getGeriatricAdjustmentPercent()), ageYears, trim(adjusted)));
                    dose = adjusted;
                }
            }
        }

        // Route: formulary default when it's actually available; else first available.
        String route = pickRoute(drug);
        Double interval = drug.getDefaultIntervalHours();
        String prescriptionType = interval != null ? "SCHEDULED" : "ONE_TIME";

        addFormularyWarnings(drug, warnings);

        return DoseSuggestionResponse.builder()
                .suggested(dose != null)
                .drugName(drug.getGenericName())
                .doseValue(dose)
                .doseUnit("mg")
                .route(route)
                .intervalHours(interval)
                .prescriptionType(prescriptionType)
                .weightUsedKg(weight.kg)
                .weightSource(weight.source)
                .rationale(rationale)
                .warnings(warnings)
                .note(drug.getSuggestionNote())
                .build();
    }

    // ====================================================================
    // IV FLUIDS
    // ====================================================================

    /**
     * IV-fluid calculator. MAINTENANCE uses Holliday–Segar (4-2-1) for
     * children and 30 ml/kg/day (default 100 ml/h) for adults; BOLUS is
     * 20 ml/kg for children (10 ml/kg neonates) and 500–1000 ml for
     * adults, over 30 minutes. Fluid choice is a suggestion the doctor
     * can replace.
     */
    public DoseSuggestionResponse suggestForFluid(UUID visitId, FluidPurpose purpose) {
        Visit visit = visitRepository.findByIdAndIsActiveTrue(visitId)
                .orElseThrow(() -> new ResourceNotFoundException("Visit", "id", visitId));

        List<String> rationale = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Weight weight = resolveWeight(visit, rationale, warnings);
        Integer ageYears = ageYears(visit);
        boolean pediatric = visit.isPediatric();
        boolean neonate = isNeonate(visit);

        String fluidName;
        Double volumeMl = null;
        Double ratePerHour = null;
        Double durationHours = null;
        String prescriptionType;

        if (purpose == FluidPurpose.MAINTENANCE) {
            prescriptionType = "CONTINUOUS";
            if (pediatric && weight.kg != null) {
                double w = weight.kg;
                double rate = 4 * Math.min(w, 10)
                        + 2 * Math.max(0, Math.min(w - 10, 10))
                        + 1 * Math.max(0, w - 20);
                ratePerHour = round1(rate);
                volumeMl = round1(rate * 24);
                durationHours = 24.0;
                fluidName = "D5 + Normal Saline 0.45%";
                rationale.add(String.format(
                        "Holliday–Segar (4-2-1): %s kg → %s ml/h (%s ml/24 h)",
                        trim(w), trim(ratePerHour), trim(volumeMl)));
                if (neonate) {
                    warnings.add("Neonate — use neonatal maintenance protocol (day-of-life dependent), not 4-2-1.");
                }
            } else if (pediatric) {
                fluidName = "D5 + Normal Saline 0.45%";
                prescriptionType = "CONTINUOUS";
                warnings.add("No weight available — record the child's weight to compute the 4-2-1 maintenance rate.");
            } else {
                double rate = weight.kg != null ? round1(weight.kg * 30 / 24) : 100;
                ratePerHour = rate;
                volumeMl = round1(rate * 24);
                durationHours = 24.0;
                fluidName = "Normal Saline 0.9%";
                rationale.add(weight.kg != null
                        ? String.format("30 ml/kg/day: %s kg → %s ml/h", trim(weight.kg), trim(rate))
                        : "Standard adult maintenance 100 ml/h (no weight on file)");
                if (ageYears != null && ageYears >= 65) {
                    warnings.add("Age ≥65 — assess cardiac/renal status before full maintenance rate.");
                }
            }
        } else { // BOLUS
            prescriptionType = "ONE_TIME";
            fluidName = "Ringer's Lactate";
            if (pediatric && weight.kg != null) {
                double perKg = neonate ? 10 : 20;
                double raw = perKg * weight.kg;
                // Fluid volumes round to the nearest 10 ml (a giving-set
                // increment), and the equation always shows the TRUE
                // product — rounding is stated separately so the maths
                // on screen is never false.
                volumeMl = Math.round(raw / 10.0) * 10.0;
                durationHours = 0.5;
                ratePerHour = round1(volumeMl / durationHours);
                rationale.add(String.format("%s ml/kg × %s kg = %s ml over 30 min",
                        trim(perKg), trim(weight.kg), trim(raw)));
                if (!volumeMl.equals(raw)) {
                    rationale.add(String.format("Rounded to %s ml", trim(volumeMl)));
                }
                if (neonate) {
                    rationale.add("Neonatal bolus 10 ml/kg (not 20)");
                }
                warnings.add("Reassess after each bolus (perfusion, hepatomegaly, crackles) before repeating.");
            } else if (pediatric) {
                warnings.add("No weight available — record the child's weight to compute a 20 ml/kg bolus.");
            } else {
                volumeMl = (ageYears != null && ageYears >= 65) ? 500.0 : 1000.0;
                durationHours = 0.5;
                ratePerHour = round1(volumeMl / durationHours);
                rationale.add(String.format("Adult crystalloid bolus %s ml over 30 min%s",
                        trim(volumeMl),
                        (ageYears != null && ageYears >= 65) ? " (reduced for age ≥65)" : ""));
            }
        }

        return DoseSuggestionResponse.builder()
                .suggested(volumeMl != null || ratePerHour != null)
                .drugName(fluidName)
                .fluidName(fluidName)
                .volumeMl(volumeMl)
                .rateMlPerHour(ratePerHour)
                .durationHours(durationHours)
                .doseUnit("ml")
                .doseValue(volumeMl)
                .route("IV")
                .prescriptionType(prescriptionType)
                .weightUsedKg(weight.kg)
                .weightSource(weight.source)
                .rationale(rationale)
                .warnings(warnings)
                .build();
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    private record Weight(Double kg, String source) {}

    /**
     * Weight resolution order: latest triage child weight (measured at
     * the front door this visit) → patient-record weight → APLS age-band
     * estimate for children 0–12 y (LOUDLY flagged) → none.
     */
    private Weight resolveWeight(Visit visit, List<String> rationale, List<String> warnings) {
        TriageRecord latestTriage = triageRecordRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByTriageTimeDesc(visit.getId())
                .orElse(null);
        if (latestTriage != null && latestTriage.getChildWeightKg() != null) {
            return new Weight(latestTriage.getChildWeightKg(), "MEASURED");
        }
        if (visit.getPatient() != null && visit.getPatient().getWeightKg() != null) {
            return new Weight(visit.getPatient().getWeightKg().doubleValue(), "MEASURED");
        }
        // APLS estimate for children only.
        if (visit.isPediatric() && visit.getPatient() != null
                && visit.getPatient().getDateOfBirth() != null) {
            LocalDate dob = visit.getPatient().getDateOfBirth();
            long months = ChronoUnit.MONTHS.between(dob, LocalDate.now());
            Double est = null;
            if (months >= 0 && months < 12) est = 0.5 * months + 4;
            else if (months < 60) est = 2 * (months / 12.0) + 8;      // 1–5 y
            else if (months <= 144) est = 3 * (months / 12.0) + 7;    // 6–12 y
            if (est != null) {
                double rounded = round1(est);
                warnings.add(String.format(
                        "Weight ESTIMATED from age (~%s kg, APLS formula) — weigh the child and confirm before administration.",
                        trim(rounded)));
                return new Weight(rounded, "ESTIMATED_BY_AGE");
            }
        }
        return new Weight(null, "NONE");
    }

    private void addFormularyWarnings(DrugFormulary drug, List<String> warnings) {
        if (drug.isRenalAdjustmentRequired()) {
            warnings.add("Renal adjustment may be required — check renal function.");
        }
        if (drug.isHepaticAdjustmentRequired()) {
            warnings.add("Hepatic adjustment may be required.");
        }
        if (drug.isHighAlert()) {
            warnings.add("HIGH-ALERT medication — independent double-check required.");
        }
        if (drug.getBlackBoxWarning() != null && !drug.getBlackBoxWarning().isBlank()) {
            warnings.add("Black-box warning: " + drug.getBlackBoxWarning());
        }
    }

    private String pickRoute(DrugFormulary drug) {
        String available = drug.getAvailableRoutes();
        String preferred = drug.getDefaultRoute();
        if (preferred != null && (available == null || available.contains(preferred))) {
            return preferred;
        }
        if (available != null && !available.isBlank()) {
            return available.split(",")[0].trim();
        }
        return preferred;
    }

    private Integer ageYears(Visit visit) {
        if (visit.getPatient() == null || visit.getPatient().getDateOfBirth() == null) return null;
        return Period.between(visit.getPatient().getDateOfBirth(), LocalDate.now()).getYears();
    }

    private boolean isNeonate(Visit visit) {
        if (visit.getPatient() == null || visit.getPatient().getDateOfBirth() == null) return false;
        long days = ChronoUnit.DAYS.between(visit.getPatient().getDateOfBirth(), LocalDate.now());
        return days >= 0 && days <= 28;
    }

    private static Double midpoint(Double lo, Double hi) {
        if (lo != null && hi != null) return (lo + hi) / 2;
        return hi != null ? hi : lo;
    }

    /** Round to a practically-measurable dose by magnitude. */
    private static Double roundDose(double mg) {
        double step;
        if (mg < 1) step = 0.1;
        else if (mg < 10) step = 0.5;
        else if (mg < 50) step = 2.5;
        else if (mg < 100) step = 5;
        else if (mg < 500) step = 25;
        else step = 50;
        return Math.round(mg / step) * step;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Trim trailing .0 for display strings. */
    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(round1(v));
    }
}
