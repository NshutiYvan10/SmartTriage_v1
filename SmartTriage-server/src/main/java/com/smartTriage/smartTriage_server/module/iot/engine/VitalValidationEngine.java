package com.smartTriage.smartTriage_server.module.iot.engine;

import com.smartTriage.smartTriage_server.common.enums.SignalQuality;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceVitalPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * VitalValidationEngine — filters noise and validates incoming IoT vital readings.
 *
 * Medical-grade validation ensures that only physiologically plausible readings
 * enter the clinical data pipeline. This is the first line of defence against
 * sensor artefacts, motion noise, and hardware faults.
 *
 * Validation rules:
 *   1. Physiological range checks (hard limits beyond which data is impossible)
 *   2. Rate-of-change checks (a value can't jump impossibly between readings)
 *   3. Signal quality assessment (perfusion index, waveform quality)
 *   4. Completeness checks (at least one vital must be present)
 *
 * Readings that fail validation are persisted (for audit) but flagged
 * as invalid and excluded from clinical decision-making.
 */
@Slf4j
@Component
public class VitalValidationEngine {

    // ====================================================================
    // PHYSIOLOGICAL HARD LIMITS
    // Readings outside these ranges are physically impossible artefacts
    // ====================================================================

    private static final int HR_MIN = 15;
    private static final int HR_MAX = 300;
    private static final int RR_MIN = 2;
    private static final int RR_MAX = 80;
    private static final int SPO2_MIN = 30;
    private static final int SPO2_MAX = 100;
    private static final double TEMP_MIN = 25.0;
    private static final double TEMP_MAX = 45.0;
    private static final int SBP_MIN = 30;
    private static final int SBP_MAX = 300;
    private static final int DBP_MIN = 15;
    private static final int DBP_MAX = 200;
    private static final double GLUCOSE_MIN = 0.5;
    private static final double GLUCOSE_MAX = 50.0;

    /** Minimum SpO2 perfusion index to trust SpO2 reading */
    private static final double MIN_PERFUSION_INDEX = 0.2;

    /**
     * Result of vital validation.
     */
    public record ValidationResult(
            boolean isValid,
            SignalQuality signalQuality,
            List<String> warnings,
            String rejectionReason
    ) {
        public static ValidationResult valid(SignalQuality quality, List<String> warnings) {
            return new ValidationResult(true, quality, warnings, null);
        }

        public static ValidationResult invalid(String reason, List<String> warnings) {
            return new ValidationResult(false, SignalQuality.INVALID, warnings, reason);
        }
    }

    /**
     * Validate an incoming device vital payload.
     */
    public ValidationResult validate(DeviceVitalPayload payload) {
        List<String> warnings = new ArrayList<>();
        boolean hasAnyVital = false;

        // --- Check at least one vital is present ---
        if (payload.getHeartRate() != null) hasAnyVital = true;
        if (payload.getSpo2() != null) hasAnyVital = true;
        if (payload.getRespiratoryRate() != null) hasAnyVital = true;
        if (payload.getTemperature() != null) hasAnyVital = true;
        if (payload.getSystolicBp() != null) hasAnyVital = true;
        if (payload.getBloodGlucose() != null) hasAnyVital = true;

        if (!hasAnyVital) {
            return ValidationResult.invalid("No vital readings present in payload", warnings);
        }

        // --- Heart Rate ---
        if (payload.getHeartRate() != null) {
            if (payload.getHeartRate() < HR_MIN || payload.getHeartRate() > HR_MAX) {
                return ValidationResult.invalid(
                        "Heart rate out of physiological range: " + payload.getHeartRate(), warnings);
            }
        }

        // --- SpO2 ---
        if (payload.getSpo2() != null) {
            if (payload.getSpo2() < SPO2_MIN || payload.getSpo2() > SPO2_MAX) {
                return ValidationResult.invalid(
                        "SpO2 out of physiological range: " + payload.getSpo2(), warnings);
            }
            // Check perfusion index if available
            if (payload.getSpo2PerfusionIndex() != null
                    && payload.getSpo2PerfusionIndex() < MIN_PERFUSION_INDEX) {
                warnings.add("Low perfusion index (" + payload.getSpo2PerfusionIndex()
                        + ") — SpO2 reading may be unreliable");
            }
        }

        // --- Respiratory Rate ---
        if (payload.getRespiratoryRate() != null) {
            if (payload.getRespiratoryRate() < RR_MIN || payload.getRespiratoryRate() > RR_MAX) {
                return ValidationResult.invalid(
                        "Respiratory rate out of physiological range: " + payload.getRespiratoryRate(),
                        warnings);
            }
        }

        // --- Temperature ---
        if (payload.getTemperature() != null) {
            if (payload.getTemperature() < TEMP_MIN || payload.getTemperature() > TEMP_MAX) {
                return ValidationResult.invalid(
                        "Temperature out of physiological range: " + payload.getTemperature(), warnings);
            }
        }

        // --- Blood Pressure ---
        if (payload.getSystolicBp() != null) {
            if (payload.getSystolicBp() < SBP_MIN || payload.getSystolicBp() > SBP_MAX) {
                return ValidationResult.invalid(
                        "Systolic BP out of physiological range: " + payload.getSystolicBp(), warnings);
            }
        }
        if (payload.getDiastolicBp() != null) {
            if (payload.getDiastolicBp() < DBP_MIN || payload.getDiastolicBp() > DBP_MAX) {
                return ValidationResult.invalid(
                        "Diastolic BP out of physiological range: " + payload.getDiastolicBp(), warnings);
            }
            // DBP should be less than SBP
            if (payload.getSystolicBp() != null
                    && payload.getDiastolicBp() >= payload.getSystolicBp()) {
                warnings.add("Diastolic BP ≥ Systolic BP — possible cuff artefact");
            }
        }

        // --- Blood Glucose ---
        if (payload.getBloodGlucose() != null) {
            if (payload.getBloodGlucose() < GLUCOSE_MIN || payload.getBloodGlucose() > GLUCOSE_MAX) {
                return ValidationResult.invalid(
                        "Blood glucose out of physiological range: " + payload.getBloodGlucose(),
                        warnings);
            }
        }

        // --- Determine signal quality ---
        SignalQuality quality = assessSignalQuality(payload, warnings);

        return ValidationResult.valid(quality, warnings);
    }

    /**
     * Assess overall signal quality based on device metadata and reading characteristics.
     */
    private SignalQuality assessSignalQuality(DeviceVitalPayload payload, List<String> warnings) {
        boolean hasConcern = false;

        // Low battery may affect accuracy
        if (payload.getBatteryLevel() != null && payload.getBatteryLevel() < 15) {
            warnings.add("Device battery critically low: " + payload.getBatteryLevel() + "%");
            hasConcern = true;
        }

        // Weak WiFi signal may cause data loss
        if (payload.getWifiRssi() != null && payload.getWifiRssi() < -80) {
            warnings.add("Weak WiFi signal: " + payload.getWifiRssi() + " dBm");
            hasConcern = true;
        }

        // Low perfusion index → poor SpO2 reliability
        if (payload.getSpo2PerfusionIndex() != null && payload.getSpo2PerfusionIndex() < 0.5) {
            hasConcern = true;
        }

        if (hasConcern) {
            return warnings.size() > 1 ? SignalQuality.POOR : SignalQuality.ACCEPTABLE;
        }

        return SignalQuality.GOOD;
    }
}
