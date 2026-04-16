# SmartTriage — AI Monitoring Engine: Strategic Design Document

**Date:** February 26, 2026  
**Version:** 1.0  
**Status:** Architecture Design — Pre-Implementation  
**Authors:** SmartTriage Engineering  

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current System Baseline](#2-current-system-baseline)
3. [Architecture Decision: Internal Module vs Microservice](#3-architecture-decision-internal-module-vs-microservice)
4. [End-to-End Data Pipeline](#4-end-to-end-data-pipeline)
5. [AI Engine Architecture — Hybrid Approach](#5-ai-engine-architecture--hybrid-approach)
6. [Layer 1 — Rule-Based Clinical Logic Engine](#6-layer-1--rule-based-clinical-logic-engine)
7. [Layer 2 — Statistical Trend Analysis Engine](#7-layer-2--statistical-trend-analysis-engine)
8. [Layer 3 — Machine Learning Clinical Pattern Recognition](#8-layer-3--machine-learning-clinical-pattern-recognition)
9. [Deterioration Detection Model](#9-deterioration-detection-model)
10. [Clinical Pattern Library](#10-clinical-pattern-library)
11. [Alert Prioritization & Escalation Framework](#11-alert-prioritization--escalation-framework)
12. [Automatic Re-Triage Decision Engine](#12-automatic-re-triage-decision-engine)
13. [Explainability Framework](#13-explainability-framework)
14. [Improvement & Stabilization Detection](#14-improvement--stabilization-detection)
15. [Data Architecture & Storage Strategy](#15-data-architecture--storage-strategy)
16. [Real-Time Communication & Notification](#16-real-time-communication--notification)
17. [Reliability, Fault Tolerance & Latency](#17-reliability-fault-tolerance--latency)
18. [Audit Logging & Medico-Legal Traceability](#18-audit-logging--medico-legal-traceability)
19. [Medical Calibration & Validation Strategy](#19-medical-calibration--validation-strategy)
20. [Module Structure & Integration Map](#20-module-structure--integration-map)
21. [Database Migration Plan](#21-database-migration-plan)
22. [Implementation Roadmap](#22-implementation-roadmap)
23. [Risk Register](#23-risk-register)

---

## 1. Executive Summary

This document defines the architecture for SmartTriage's **AI Monitoring Engine** — the intelligent layer that continuously consumes real-time vital sign streams from ESP32-based IoT patient monitors, detects clinical deterioration, identifies improvement patterns, and triggers automated clinical responses.

### Design Principles

| Principle | Rationale |
|---|---|
| **Safety-first conservatism** | False positives (over-escalation) are acceptable; false negatives (missed deterioration) are not. This is a life-critical system. |
| **Hybrid AI approach** | Rule-based clinical logic (deterministic, auditable) + statistical trend analysis (rate-of-change, multi-parameter correlation) + ML pattern recognition (sepsis, respiratory distress). Each layer adds intelligence; the rule layer is always the safety net. |
| **Full explainability** | Every alert, every escalation, every re-triage must carry a human-readable clinical reasoning chain. No black-box decisions. |
| **Medico-legal auditability** | Every AI decision is immutably logged — this is a medical-legal record in a hospital. |
| **Incremental rollout** | Layer 1 (rules) ships first and is clinically safe alone. Layers 2 and 3 are additive enhancements. |
| **Low latency** | Analysis must complete within 200ms of data ingestion. Deterioration alerts must reach clinicians within 1 second. |

### What This Engine Does

```
╔══════════════════════════════════════════════════════════════════════╗
║  ESP32 Monitor → Ingestion → Validation → AI Analysis → Response   ║
║                                                                      ║
║  AI Analysis includes:                                               ║
║    ├── Static threshold checks (Rwanda protocol, TEWS)               ║
║    ├── Trend analysis (slope, rate-of-change, moving averages)       ║
║    ├── Multi-parameter correlation (e.g., ↓BP + ↑HR = shock)        ║
║    ├── Clinical pattern matching (sepsis, respiratory distress)      ║
║    ├── Improvement/stabilization detection                           ║
║    └── Composite risk scoring (weighted multi-dimensional score)     ║
║                                                                      ║
║  Responses include:                                                  ║
║    ├── Real-time alerts to clinicians (WebSocket push)               ║
║    ├── Automatic re-triage (category escalation)                     ║
║    ├── Clinical insight suggestions (explainable reasoning)          ║
║    └── Audit records (immutable decision log)                        ║
╚══════════════════════════════════════════════════════════════════════╝
```

---

## 2. Current System Baseline

Before designing the AI engine, we must understand what already exists and how the AI layer extends it.

### What We Already Have

| Component | Current Capability | Location |
|---|---|---|
| **ESP32 Firmware** | Streams HR, SpO2, Temp, RR, ECG every 3 seconds via HTTP POST to `/api/v1/iot/stream/ingest` | `firmware/medical_monitor.ino` |
| **VitalValidationEngine** | Physiological range validation, signal quality assessment, noise rejection | `module/iot/engine/VitalValidationEngine.java` |
| **ContinuousMonitoringEngine** | Basic deterioration detection: single-vital critical, multi-vital trend, rapid decline, SPO2 override, TEWS escalation | `module/iot/engine/ContinuousMonitoringEngine.java` |
| **Alert System** | ClinicalAlert entity with severity/type/acknowledgment, WebSocket push via RealTimeEventPublisher | `module/alert/` |
| **TEWS Calculators** | Adult + Pediatric TEWS scoring per Rwanda National Standard | `module/triage/engine/` |
| **Auto-Retriage** | System-triggered retriage with cooldown, escalation-only (never downgrades), creates TriageRecord with `isSystemTriggered=true` | `ContinuousMonitoringEngine` |
| **VitalStream Table** | High-frequency time-series (every 1–5 seconds), with validation flag, signal quality, ECG waveform | `vital_streams` |
| **VitalSigns Table** | Low-frequency clinical snapshots (minutes apart), used for TEWS | `vital_signs` |
| **DeviceHeartbeatScheduler** | Fail-safe: detects missing device data every 15 seconds, raises CRITICAL alerts | `module/iot/scheduler/` |

### Current Data Flow

```
ESP32 Device
    │ POST /api/v1/iot/stream/ingest (every 3–5 sec)
    │ Header: X-Device-API-Key
    ▼
IoTStreamController
    ├── authenticateDevice()
    ├── processHeartbeat()
    ├── VitalStreamService.ingestVitals()
    │       ├── VitalValidationEngine.validate()
    │       ├── Persist VitalStream (all, including rejected)
    │       └── Return DeviceAckResponse
    │
    └── ContinuousMonitoringEngine.analyseAndRespond()    ◄── THIS IS WHERE AI LIVES
            ├── Get 5-min window of validated readings
            ├── 6 detection checks (sequential priority)
            ├── Generate ClinicalAlert
            └── Auto-retriage (if cooldown passed + severity escalation)
```

### What's Missing (This Design Addresses)

| Gap | Current State | Target State |
|---|---|---|
| **Trend analysis** | Only compares earliest vs latest in 5-min window | Proper time-series analysis: linear regression slope, moving average crossover, rate-of-change per minute |
| **Multi-parameter correlation** | Counts abnormal vitals (≥2 = alert) | True clinical correlation: BP↓ + HR↑ = shock pattern, Temp↑ + HR↑ + RR↑ = sepsis screen |
| **Clinical pattern matching** | Enum exists (`SEPSIS_PATTERN`, `RESPIRATORY_FAILURE_PATTERN`, `HEMODYNAMIC_INSTABILITY`) but detection logic is not implemented | Full pattern library with clinical rules |
| **Sustained abnormality** | Enum exists but detection not implemented | Rolling window confirmation — abnormality must persist N minutes before escalation |
| **Improvement detection** | Not implemented | Detect stabilization and improvement trends to support potential future de-escalation |
| **Composite risk score** | Only TEWS (which requires non-IoT inputs like mobility, AVPU) | AI Risk Score: continuous, multi-dimensional, IoT-native |
| **Explainability** | Alert messages contain basic descriptions | Structured reasoning chain: evidence → pattern → clinical significance → recommendation |
| **ML integration** | None | Pluggable ML model interface for sepsis prediction, respiratory distress, cardiac arrest risk |
| **Alert fatigue management** | No deduplication, no rate limiting | Intelligent alert suppression, grouping, and progressive escalation |
| **Audit depth** | Alert + triage records | Full AI decision log with every input, every check, every reasoning step |

---

## 3. Architecture Decision: Internal Module vs Microservice

### Decision: Internal Module (Phase 1) → Extractable Microservice (Phase 2+)

| Factor | Internal Module | Separate Microservice |
|---|---|---|
| **Latency** | ~1ms (in-process method call) | ~10–50ms (network hop + serialization) |
| **Transactional consistency** | Single DB transaction (alert + retriage + audit atomically) | Requires distributed transaction or saga pattern |
| **Operational complexity** | Zero — same JVM, same deployment | New service, new deployment pipeline, new monitoring |
| **Data access** | Direct JPA repository access to VitalStream, Visit, TriageRecord | Requires API calls or shared database (anti-pattern) |
| **Team size** | Single backend team | Better with separate team owning AI service |
| **Hospital environment** | Single-server deployment is realistic for Rwandan hospitals | Multi-service requires orchestration infrastructure |

**Phase 1 (Now):** The AI engine is an internal module (`module/ai/`) within the SmartTriage Spring Boot monolith. It is invoked synchronously after every validated reading, exactly as the `ContinuousMonitoringEngine` currently works.

**Phase 2 (Future, if needed):** The module is designed with clean interfaces (`AIAnalysisEngine` interface, `AnalysisRequest`/`AnalysisResult` DTOs) so it can be extracted into a standalone microservice accessed via gRPC or async message queue when horizontal scaling is required.

### Module Location

```
module/
├── ai/                                    ← NEW MODULE
│   ├── engine/
│   │   ├── AIAnalysisOrchestrator.java    ← Main entry point (replaces ContinuousMonitoringEngine core logic)
│   │   ├── RuleBasedDetectionEngine.java  ← Layer 1: Clinical rules
│   │   ├── TrendAnalysisEngine.java       ← Layer 2: Statistical trends
│   │   ├── PatternRecognitionEngine.java  ← Layer 3: Clinical patterns + ML
│   │   ├── RiskScoringEngine.java         ← Composite risk score calculator
│   │   ├── AlertIntelligenceEngine.java   ← Alert deduplication, fatigue management
│   │   └── ImprovementDetectionEngine.java← Stabilization and improvement detection
│   ├── model/
│   │   ├── AnalysisContext.java           ← Input context for analysis (patient, vitals, history)
│   │   ├── AnalysisResult.java            ← Full result with findings, score, alerts, reasoning
│   │   ├── ClinicalFinding.java           ← Individual finding (what was detected)
│   │   ├── ClinicalReasoning.java         ← Explainability chain
│   │   ├── RiskScore.java                 ← Composite risk score value object
│   │   ├── TrendResult.java               ← Trend analysis output for a single parameter
│   │   └── PatternMatch.java              ← Clinical pattern match result
│   ├── dto/
│   │   ├── AIInsightResponse.java         ← API response for AI analysis results
│   │   └── PatientRiskSummary.java        ← Dashboard-facing risk summary
│   ├── config/
│   │   └── AIEngineConfig.java            ← Externalized thresholds, feature flags
│   ├── audit/
│   │   ├── AIDecisionLog.java             ← Entity: immutable decision audit log
│   │   └── AIDecisionLogRepository.java
│   └── controller/
│       └── AIInsightController.java       ← Optional: API to retrieve AI analysis for a visit
├── iot/
│   ├── engine/
│   │   ├── ContinuousMonitoringEngine.java   ← REFACTORED: delegates to AIAnalysisOrchestrator
│   │   └── VitalValidationEngine.java        ← Unchanged
│   ...
```

### Integration Point

The `ContinuousMonitoringEngine` remains the entry point but delegates to the new `AIAnalysisOrchestrator`:

```java
// Current flow (ContinuousMonitoringEngine.analyseAndRespond):
//   - Get readings → run 6 checks → generate alert → auto-retriage

// New flow:
//   ContinuousMonitoringEngine.analyseAndRespond()
//     └── aiOrchestrator.analyse(context)           ← NEW
//           ├── Layer 1: RuleBasedDetectionEngine
//           ├── Layer 2: TrendAnalysisEngine
//           ├── Layer 3: PatternRecognitionEngine
//           ├── Risk scoring
//           ├── Alert intelligence
//           └── Return AnalysisResult
//     └── Process result (alerts, retriage, audit)   ← Existing infra
```

---

## 4. End-to-End Data Pipeline

### Complete Pipeline: Device to Clinical Response

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PHASE 1: DATA ACQUISITION                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ESP32 Device (medical_monitor.ino)                                 │
│  ├── Reads sensors at 250Hz (ECG), 1Hz (temp), continuous (SpO2)   │
│  ├── Processes locally: R-peak detection, SpO2 calculation, RR     │
│  ├── Evaluates local status (NORMAL/WARNING/CRITICAL)              │
│  └── Sends HTTP POST every 3 seconds to server                    │
│       Body: DeviceVitalPayload (JSON, ~500 bytes)                  │
│       Auth: X-Device-API-Key header                                │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                    PHASE 2: INGESTION & VALIDATION                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  IoTStreamController.ingestVitals()                                 │
│  ├── Device authentication (API key lookup)                        │
│  ├── Serial number cross-validation                                │
│  ├── Heartbeat update                                              │
│  └── VitalStreamService.ingestVitals()                             │
│       ├── VitalValidationEngine.validate()                         │
│       │   ├── Physiological range check (HR: 15–300, SpO2: 30–100)│
│       │   ├── Signal quality assessment (battery, RSSI, PI)       │
│       │   └── Returns: ValidationResult (valid/invalid + quality)  │
│       ├── Persist VitalStream (ALL readings, even rejected)        │
│       ├── Update DeviceSession statistics                          │
│       └── Return DeviceAckResponse                                 │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                    PHASE 3: AI ANALYSIS                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ContinuousMonitoringEngine.analyseAndRespond()                     │
│  └── AIAnalysisOrchestrator.analyse(context)          ◄── NEW      │
│       │                                                             │
│       ├── Build AnalysisContext                                     │
│       │   ├── Latest reading                                       │
│       │   ├── Recent readings (5-min window, ~60–100 readings)     │
│       │   ├── Extended history (30-min window for deeper trends)   │
│       │   ├── Current triage state (category, TEWS, timestamp)     │
│       │   ├── Patient demographics (age → pediatric thresholds)    │
│       │   └── Previous AI analysis results (for state continuity)  │
│       │                                                             │
│       ├── LAYER 1: RuleBasedDetectionEngine                        │
│       │   ├── Critical threshold checks (Rwanda protocol)          │
│       │   ├── SpO2 override (< 92% → RED)                         │
│       │   ├── TEWS-based escalation                                │
│       │   └── Single-vital danger zone                             │
│       │                                                             │
│       ├── LAYER 2: TrendAnalysisEngine                             │
│       │   ├── Per-parameter linear regression (slope over window)  │
│       │   ├── Rate-of-change per minute                            │
│       │   ├── Moving average crossover detection                   │
│       │   ├── Sustained abnormality confirmation                   │
│       │   └── Multi-parameter divergence detection                 │
│       │                                                             │
│       ├── LAYER 3: PatternRecognitionEngine                        │
│       │   ├── Sepsis screening (SIRS criteria from vitals)         │
│       │   ├── Respiratory distress pattern                         │
│       │   ├── Hemodynamic instability / shock pattern              │
│       │   ├── Cardiac arrhythmia indicators                        │
│       │   ├── Neurological deterioration (if GCS available)        │
│       │   └── (Future: ML model inference)                         │
│       │                                                             │
│       ├── RiskScoringEngine                                        │
│       │   ├── Weighted composite from all 3 layers                 │
│       │   ├── Score: 0–100 (continuous risk index)                 │
│       │   └── Trend: IMPROVING / STABLE / WORSENING / CRITICAL    │
│       │                                                             │
│       ├── ImprovementDetectionEngine                               │
│       │   ├── Vital normalization tracking                         │
│       │   ├── Sustained improvement confirmation                   │
│       │   └── Stabilization pattern detection                      │
│       │                                                             │
│       ├── AlertIntelligenceEngine                                  │
│       │   ├── Deduplication (suppress repeat alerts for same issue)│
│       │   ├── Alert grouping (combine related findings)            │
│       │   ├── Progressive escalation (warn → alert → critical)    │
│       │   └── Alert fatigue prevention (maximum alerts per window) │
│       │                                                             │
│       └── Return AnalysisResult                                    │
│           ├── List<ClinicalFinding> findings                       │
│           ├── RiskScore compositeRisk                              │
│           ├── List<PatternMatch> patterns                          │
│           ├── ClinicalReasoning reasoning (explainability)         │
│           ├── AlertDecision alertDecision                          │
│           └── RetriageDecision retriageDecision                    │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                    PHASE 4: CLINICAL RESPONSE                       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ContinuousMonitoringEngine (response handling)                     │
│  ├── Generate ClinicalAlert (if alertDecision.shouldAlert)         │
│  ├── Push WebSocket notification (RealTimeEventPublisher)          │
│  │   ├── /topic/vitals/{visitId}       — reading data              │
│  │   ├── /topic/alerts/{hospitalId}    — alert broadcast           │
│  │   └── /topic/triage/{visitId}       — triage change             │
│  ├── Auto-retriage (if retriageDecision.shouldRetriage)            │
│  │   ├── Create VitalSigns snapshot                                │
│  │   ├── Create TriageRecord (isSystemTriggered = true)            │
│  │   └── Update Visit (category, TEWS, retriageCount)             │
│  ├── Persist AIDecisionLog (full audit)                            │
│  └── Update DeviceSession statistics                               │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Timing Budget

| Phase | Target Latency | Constraint |
|---|---|---|
| ESP32 → Server (HTTP) | ~50–200ms | WiFi + network |
| Authentication + Validation | < 10ms | API key lookup + range checks |
| VitalStream persistence | < 20ms | Single INSERT with indexes |
| **AI Analysis (all layers)** | **< 200ms** | **CPU-bound, no external I/O** |
| Alert persistence + WebSocket push | < 20ms | Single INSERT + STOMP publish |
| Auto-retriage (if triggered) | < 50ms | 3 INSERTs + 1 UPDATE |
| **Total end-to-end** | **< 500ms** | Device reading → clinician dashboard |

---

## 5. AI Engine Architecture — Hybrid Approach

### Why Hybrid?

A purely rule-based system misses complex multi-parameter patterns. A purely ML-based system is a black box that cannot be medically validated or explained. A hybrid approach gives us:

```
┌─────────────────────────────────────────────────────────────┐
│                    AI ENGINE LAYERS                          │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  LAYER 3: Machine Learning (Future Enhancement)             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Sepsis early warning model                          │   │
│  │ Cardiac arrest risk model                           │   │
│  │ Respiratory failure prediction                      │   │
│  │ → Enhances detection, NEVER overrides safety rules  │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  LAYER 2: Statistical Trend Analysis                        │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Linear regression slope per parameter               │   │
│  │ Rate-of-change detection (Δ/min)                    │   │
│  │ Moving average crossover (SMA-short vs SMA-long)    │   │
│  │ Multi-parameter correlation                         │   │
│  │ Sustained abnormality confirmation                  │   │
│  │ → Detects what rules cannot: gradual trends         │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  LAYER 1: Rule-Based Clinical Logic (SAFETY NET)            │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Rwanda National Triage Protocol thresholds          │   │
│  │ TEWS scoring and escalation                         │   │
│  │ SpO2 override (< 92% → RED)                        │   │
│  │ Critical vital thresholds (HR, BP, Temp, RR)        │   │
│  │ → DETERMINISTIC. AUDITABLE. ALWAYS RUNS FIRST.      │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  FOUNDATION: VitalValidationEngine (Existing)               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │ Physiological range validation                      │   │
│  │ Signal quality assessment                           │   │
│  │ Noise rejection                                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Layer Interaction Rules

1. **Layer 1 always runs.** Its findings are authoritative for immediate critical thresholds.
2. **Layer 2 enriches.** Trend data adds context — a reading might be within normal range but trending dangerously.
3. **Layer 3 suggests.** ML models produce confidence scores that are attached as supplementary evidence, never as the sole trigger.
4. **The final risk score is a weighted composite.** Layer 1 findings carry the highest weight. Layer 3 findings carry the lowest weight (until clinically validated).
5. **Any layer can produce a `ClinicalFinding`.** All findings are included in the reasoning chain.

---

## 6. Layer 1 — Rule-Based Clinical Logic Engine

### Purpose

Deterministic, medically validated threshold checks based on the Rwanda National Standard Triage Protocol. This layer is the **safety net** — it ensures that no critical condition is ever missed, regardless of what Layers 2 and 3 conclude.

### Design

```java
@Component
public class RuleBasedDetectionEngine {

    /**
     * Evaluate a single reading against clinical rules.
     * Returns a list of findings (can be zero or more).
     */
    public List<ClinicalFinding> evaluate(AnalysisContext context) {
        List<ClinicalFinding> findings = new ArrayList<>();
        VitalStream latest = context.latestReading();
        boolean isPediatric = context.isPediatric();

        // R1: SpO2 Override (Rwanda protocol — non-negotiable)
        evaluateSpO2Override(latest, findings);

        // R2: Critical vital thresholds (TEWS score = 3 territory)
        evaluateCriticalThresholds(latest, isPediatric, findings);

        // R3: TEWS escalation from stream vitals
        evaluateTewsEscalation(context, findings);

        // R4: Multi-vital simultaneous abnormality
        evaluateMultiVitalAbnormality(latest, findings);

        // R5: Pediatric-specific emergency signs
        if (isPediatric) {
            evaluatePediatricEmergencySigns(latest, findings);
        }

        return findings;
    }
}
```

### Rule Catalog

#### R1 — SpO2 Override (Severity: CRITICAL)

| Condition | Action | Source |
|---|---|---|
| SpO2 < 92% | Immediate RED triage recommendation | Rwanda Adult Triage Protocol |
| SpO2 < 85% | CRITICAL alert + immediate RED | Clinical consensus |

```
IF SpO2 < 92%
  → Finding: SPO2_OVERRIDE
  → Severity: CRITICAL 
  → Recommendation: "Immediate RED triage — SpO2 {value}% below 92% protocol threshold"
  → Auto-retriage: YES (to RED)
```

#### R2 — Critical Vital Thresholds (Severity: CRITICAL)

These match the TEWS score = 3 boundaries (highest danger zone):

| Vital | Critical Low | Critical High | Pediatric Adjustment |
|---|---|---|---|
| Heart Rate | < 40 bpm | > 130 bpm | < 60 bpm (child), > 150 bpm (child) |
| Respiratory Rate | < 8 breaths/min | > 30 breaths/min | > 40 breaths/min (child) |
| Systolic BP | < 70 mmHg | > 200 mmHg | Not scored for children (as per Rwanda protocol) |
| Temperature | < 34.0°C | > 40.0°C | Same |
| SpO2 | < 92% | — | Same |

#### R3 — TEWS Escalation (Severity: HIGH)

```
IF computed_stream_TEWS > current_TEWS + 2
  → Finding: TEWS_ESCALATION
  → Severity: HIGH
  → Description: "TEWS increased from {current} to {computed} — significant clinical change"
```

#### R4 — Multi-Vital Simultaneous Abnormality (Severity: MEDIUM → HIGH)

```
abnormal_count = count of vitals in TEWS > 0 territory
IF abnormal_count >= 3 → Severity: HIGH, "3+ vitals simultaneously abnormal"
IF abnormal_count == 2 → Severity: MEDIUM, "2 vitals simultaneously abnormal"
```

#### R5 — Pediatric Emergency Signs (Severity: CRITICAL)

| Sign | Condition |
|---|---|
| Severe bradycardia | HR < 60 bpm in child (3–12 years) |
| Severe tachycardia | HR > 180 bpm in child |
| Severe tachypnea | RR > 60 in child |
| Hypoxia | SpO2 < 90% in child |

---

## 7. Layer 2 — Statistical Trend Analysis Engine

### Purpose

Detect **gradual deterioration** that rule-based checks miss. A patient whose heart rate is 105 bpm is "normal" — but if it was 70 bpm thirty minutes ago and has been steadily rising, that's a clinically significant trend. Layer 2 catches these.

### Analysis Methods

#### 7.1 Linear Regression Slope (Per Parameter)

For each vital sign, compute the ordinary least squares (OLS) slope over the analysis window.

```
Given: readings [(t₁, v₁), (t₂, v₂), ..., (tₙ, vₙ)] over a window

Slope (β) = Σ((tᵢ - t̄)(vᵢ - v̄)) / Σ((tᵢ - t̄)²)

Where t is relative time in minutes, v is the vital value.
```

**Clinically significant slope thresholds:**

| Parameter | Concerning Slope (per min) | Dangerous Slope (per min) |
|---|---|---|
| Heart Rate | ≥ +2.0 or ≤ -2.0 bpm/min | ≥ +5.0 or ≤ -5.0 bpm/min |
| SpO2 | ≤ -0.3 %/min | ≤ -1.0 %/min |
| Respiratory Rate | ≥ +1.0 or ≤ -0.5 br/min/min | ≥ +3.0 br/min/min |
| Systolic BP | ≤ -2.0 mmHg/min | ≤ -5.0 mmHg/min |
| Temperature | ≥ +0.1 °C/min | ≥ +0.3 °C/min |

```
IF |slope| exceeds concerning threshold AND R² > 0.5 (trend is real, not noise)
  → Finding: TRENDING_{parameter}_{direction}
  → Severity: based on slope magnitude
  → Description: "Heart rate trending upward at +3.2 bpm/min (R² = 0.78) over 5 minutes"
```

The R² (coefficient of determination) check is critical — it ensures the trend is a real linear trend, not random noise that happens to have a slope.

#### 7.2 Rate-of-Change Detection (Δ/minute)

Simpler than regression — compares a short-term moving average (last 1 min) vs medium-term moving average (last 5 min):

```
SMA_short = mean of readings in last 60 seconds
SMA_long  = mean of readings in last 300 seconds
delta     = SMA_short - SMA_long
rate      = delta / time_between_midpoints

IF rate exceeds threshold → RAPID_CHANGE finding
```

This catches rapid shifts even when individual readings stay within normal range.

#### 7.3 Moving Average Crossover

Inspired by signal processing — detects when a fast-moving average crosses below/above a slow-moving average, indicating a regime change:

```
SMA_fast  = 30-second moving average
SMA_slow  = 3-minute moving average

IF SMA_fast crosses below SMA_slow (for SpO2, BP):
  → "Downward crossover detected — possible deterioration onset"

IF SMA_fast crosses above SMA_slow (for HR, RR, Temp):
  → "Upward crossover detected — possible clinical escalation"
```

#### 7.4 Sustained Abnormality Confirmation

A key defense against transient artefacts (motion, coughing, sensor adjustment):

```
FOR each vital V:
  IF V has been in abnormal range for > SUSTAINED_THRESHOLD_MINUTES:
    → Finding: SUSTAINED_ABNORMALITY
    → More significant than a single abnormal reading
    → Severity escalation: if sustained > 10 min, upgrade severity by one level
```

**Sustained thresholds:**

| Parameter | Abnormal Range | Required Duration |
|---|---|---|
| Heart Rate | > 110 or < 50 bpm | > 3 minutes |
| SpO2 | < 95% | > 2 minutes |
| SpO2 | < 92% | > 30 seconds (immediate escalation) |
| Systolic BP | < 90 or > 180 mmHg | > 3 minutes |
| Temperature | > 38.5°C or < 35.0°C | > 5 minutes |
| Respiratory Rate | > 25 or < 8 br/min | > 3 minutes |

#### 7.5 Multi-Parameter Divergence

Detects when multiple vitals are changing in the same clinically concerning direction simultaneously:

```
worsening_count = 0
FOR each vital in [HR, RR, SpO2, SBP, Temp]:
  IF slope indicates worsening direction:
    worsening_count++

IF worsening_count >= 3:
  → Finding: MULTI_PARAMETER_DIVERGENCE
  → Severity: HIGH
  → "3 parameters simultaneously worsening — suggests systemic deterioration"
```

### TrendAnalysisEngine Design

```java
@Component
public class TrendAnalysisEngine {

    // Analysis windows
    private static final int SHORT_WINDOW_SECONDS = 60;     // 1 minute
    private static final int MEDIUM_WINDOW_SECONDS = 300;    // 5 minutes
    private static final int LONG_WINDOW_SECONDS = 1800;     // 30 minutes

    /**
     * Perform trend analysis on the full analysis context.
     * Returns per-parameter trend results + cross-parameter findings.
     */
    public TrendAnalysisResult analyse(AnalysisContext context) {
        List<TrendResult> parameterTrends = new ArrayList<>();
        List<ClinicalFinding> findings = new ArrayList<>();

        // Analyse each vital parameter independently
        parameterTrends.add(analyseParameter("heartRate", extractValues(context, "heartRate")));
        parameterTrends.add(analyseParameter("spo2", extractValues(context, "spo2")));
        parameterTrends.add(analyseParameter("respiratoryRate", extractValues(context, "respiratoryRate")));
        parameterTrends.add(analyseParameter("systolicBp", extractValues(context, "systolicBp")));
        parameterTrends.add(analyseParameter("temperature", extractValues(context, "temperature")));

        // Cross-parameter analysis
        findings.addAll(detectMultiParameterDivergence(parameterTrends));
        findings.addAll(detectSustainedAbnormalities(context));

        return new TrendAnalysisResult(parameterTrends, findings);
    }

    private TrendResult analyseParameter(String name, List<TimestampedValue> values) {
        if (values.size() < 3) return TrendResult.insufficient(name);

        double slope = computeLinearRegressionSlope(values);
        double rSquared = computeRSquared(values, slope);
        double rateOfChange = computeRateOfChange(values);
        TrendDirection direction = classifyDirection(name, slope, rSquared);

        return new TrendResult(name, slope, rSquared, rateOfChange, direction, values.size());
    }
}
```

### TrendResult Value Object

```java
public record TrendResult(
    String parameterName,       // e.g., "heartRate"
    double slope,               // OLS slope (units per minute)
    double rSquared,            // Goodness of fit (0-1)
    double rateOfChange,        // SMA-short minus SMA-long per minute
    TrendDirection direction,   // RISING_FAST, RISING, STABLE, FALLING, FALLING_FAST
    int dataPoints              // Number of readings used
) {}

public enum TrendDirection {
    RISING_FAST,    // Slope exceeds dangerous threshold
    RISING,         // Slope exceeds concerning threshold
    STABLE,         // Within normal variation
    FALLING,        // Slope exceeds concerning threshold (negative)
    FALLING_FAST,   // Slope exceeds dangerous threshold (negative)
    INSUFFICIENT    // Not enough data points for reliable analysis
}
```

---

## 8. Layer 3 — Machine Learning Clinical Pattern Recognition

### Purpose

Detect complex multi-dimensional clinical patterns that cannot be captured by simple rules or single-parameter trends. Uses both **deterministic clinical pattern rules** (Phase 1) and **trained ML models** (Phase 2).

### Phase 1: Deterministic Clinical Pattern Rules

These are evidence-based clinical scoring systems encoded as algorithms — not machine learning, but clinically sophisticated pattern matching.

#### 8.1 Sepsis Screening (qSOFA-Inspired + SIRS Criteria)

The system continuously evaluates the quick Sequential Organ Failure Assessment (qSOFA) criteria from available vital signs:

```
qSOFA (bedside scoring — no lab results needed):
  +1 if Respiratory Rate ≥ 22
  +1 if Systolic BP ≤ 100 mmHg
  +1 if altered mental status (GCS < 15 or AVPU ≠ ALERT)
  
  IF score ≥ 2 → HIGH risk of sepsis

SIRS criteria (from vital signs):
  +1 if Temperature > 38.3°C or < 36.0°C
  +1 if Heart Rate > 90 bpm
  +1 if Respiratory Rate > 20 bpm

  IF SIRS ≥ 2 AND qSOFA ≥ 1 → SEPSIS_SCREENING finding
  IF SIRS ≥ 2 AND qSOFA ≥ 2 → SEPSIS_PATTERN finding (HIGH severity)
```

SmartTriage enhancement — **temporal context**:

```
IF Temperature rising trend (Layer 2 slope > +0.1°C/min)
  AND Heart Rate rising trend (slope > +2 bpm/min)
  AND (RR > 20 OR RR rising)
  → Finding: POSSIBLE_SEPSIS_ONSET
  → Severity: HIGH
  → Clinical insight: "Concurrent rising temperature, heart rate, and respiratory rate 
     pattern is consistent with early systemic inflammatory response. Consider sepsis 
     workup (blood cultures, lactate, CBC)."
```

#### 8.2 Respiratory Distress Pattern

```
PATTERN: Respiratory Failure Risk
  Criteria:
    - SpO2 declining trend (slope < -0.3 %/min) OR SpO2 < 94%
    - RR rising trend (slope > +1.0 br/min/min) OR RR > 25
    - (Optional) HR rising (compensatory tachycardia)
  
  IF 2 of 3 criteria met:
    → Finding: RESPIRATORY_DISTRESS_PATTERN
    → Severity: HIGH
    → Clinical insight: "Rising respiratory rate with declining oxygen saturation 
       suggests respiratory distress. Monitor for signs of respiratory failure. 
       Consider ABG analysis and chest imaging."
  
  IF SpO2 < 90% AND RR > 30:
    → Finding: RESPIRATORY_FAILURE_PATTERN
    → Severity: CRITICAL
    → Clinical insight: "Respiratory failure pattern detected — severe hypoxia with 
       tachypnea. IMMEDIATE clinical review required. Consider supplemental O2, 
       non-invasive ventilation, or intubation preparation."
```

#### 8.3 Hemodynamic Instability / Shock Pattern

```
PATTERN: Compensated Shock
  Criteria:
    - Systolic BP declining trend (slope < -2 mmHg/min) OR SBP < 100
    - Heart Rate rising trend (slope > +2 bpm/min) OR HR > 100
    - (Optional) Pulse pressure narrowing (SBP - DBP < 25)
  
  IF BP declining AND HR rising simultaneously:
    → Finding: HEMODYNAMIC_INSTABILITY
    → Severity: HIGH
    → Clinical insight: "Falling blood pressure with compensatory tachycardia — pattern 
       consistent with early hypovolemic or distributive shock. Assess fluid status, 
       check for bleeding, consider IV fluid bolus."

PATTERN: Decompensated Shock
  Criteria:
    - SBP < 80 mmHg sustained > 2 minutes
    - HR > 120 bpm
    - (Optional) SpO2 declining
  
  → Finding: HEMODYNAMIC_INSTABILITY
  → Severity: CRITICAL
  → Clinical insight: "Hypotension with sustained tachycardia suggests decompensated 
     shock. IMMEDIATE resuscitation required. Consider vasopressors, rapid IV fluid 
     bolus, and surgical consultation if hemorrhagic source suspected."
```

#### 8.4 Hypothermia / Hyperthermia Syndrome

```
PATTERN: Malignant Hyperthermia Risk
  Criteria:
    - Temperature > 39°C AND rising (slope > +0.2°C/min)
    - Heart Rate > 110 bpm
    - (Optional) RR elevated
  
  → Finding: HYPERTHERMIA_SYNDROME
  → Clinical insight: "Rapidly rising temperature with tachycardia. Consider 
     infectious source, drug reaction, or environmental exposure. Active cooling 
     may be indicated."

PATTERN: Severe Hypothermia
  Criteria:
    - Temperature < 35°C AND falling (slope < -0.1°C/min)
    - HR < 60 bpm (bradycardia from hypothermia)
  
  → Finding: HYPOTHERMIA_SYNDROME
  → Severity: CRITICAL
  → Clinical insight: "Progressive hypothermia with bradycardia. Risk of cardiac 
     arrhythmia. Active rewarming indicated. Handle patient gently."
```

### Phase 2: Machine Learning Models (Future)

When Phase 1 is clinically validated and sufficient training data has been collected, ML models can be integrated as supplementary analysis:

#### Model Interface Design

```java
/**
 * Interface for pluggable ML models.
 * All models must implement this contract.
 */
public interface ClinicalMLModel {
    
    /** Unique model identifier (e.g., "sepsis-early-warning-v1") */
    String getModelId();
    
    /** Model version for audit trail */
    String getModelVersion();
    
    /**
     * Run inference on the analysis context.
     * @return prediction with confidence score and feature importances
     */
    MLPrediction predict(AnalysisContext context);
    
    /** Whether this model is currently enabled (feature flag) */
    boolean isEnabled();
}

public record MLPrediction(
    String modelId,
    String modelVersion,
    String predictionLabel,       // e.g., "SEPSIS_RISK_HIGH"
    double confidence,            // 0.0 to 1.0
    Map<String, Double> featureImportances,  // Which inputs drove the prediction
    Instant computedAt
) {}
```

#### ML Model Candidates

| Model | Input Features | Output | Training Data Source |
|---|---|---|---|
| Sepsis Early Warning | HR, RR, Temp, SBP, SpO2 trends (30-min window) | Risk probability (0–1) | MIMIC-IV, eICU datasets |
| Cardiac Arrest Risk | HR, HR variability, RR, SpO2, ECG features | Risk probability (0–1) | Research collaboration |
| Respiratory Failure Prediction | SpO2, RR, HR trends, SpO2/FiO2 ratio | Time-to-event estimate | Clinical validation studies |

#### ML Safety Constraints

```
CRITICAL RULE: ML models are ADVISORY ONLY in Phase 2.

ML predictions:
  ✅ CAN raise the alert severity (supplement Layer 1/2 findings)
  ✅ CAN generate informational clinical insights
  ✅ CAN be displayed on the dashboard as "AI Insight"
  ❌ CANNOT be the sole trigger for a CRITICAL alert
  ❌ CANNOT be the sole trigger for auto-retriage
  ❌ CANNOT override a Layer 1 determination

After 6 months of prospective clinical validation:
  → Review ML model performance with clinical team
  → If sensitivity > 85% and specificity > 80%, promote to full trigger capability
  → Requires IRB-equivalent clinical governance board approval
```

#### ML Deployment Architecture

```
Phase 2 ML Architecture (future):

Option A — In-Process (ONNX Runtime):
  ├── Models exported as ONNX format
  ├── onnxruntime-java dependency in pom.xml
  ├── Model loaded in JVM at startup
  ├── Inference: < 10ms per prediction
  └── Pro: No external dependency. Con: Model size limited by JVM heap.

Option B — Sidecar Service (Python Flask/FastAPI):
  ├── Python service running on same host
  ├── Models in scikit-learn / PyTorch / TensorFlow
  ├── SmartTriage calls via localhost HTTP (< 5ms network)
  ├── Pro: Full Python ML ecosystem. Con: Additional process to manage.
  └── Recommended for complex models (deep learning, ensemble).

Option C — Cloud ML Endpoint:
  ├── Model deployed to AWS SageMaker / Google Vertex AI
  ├── SmartTriage calls via HTTPS
  ├── Pro: Scalable, managed. Con: Latency (~100-500ms), internet dependency.
  └── NOT recommended for life-critical, low-latency environment.

Decision: Option A (ONNX) for simple models, Option B (sidecar) for complex models.
Cloud ML is explicitly excluded for life-critical real-time analysis.
```

---

## 9. Deterioration Detection Model

### Composite Deterioration Assessment

The AI engine doesn't just flag individual issues — it produces a composite assessment of the patient's trajectory.

#### 9.1 Risk Score (0–100)

```
                    ┌─────────────────────────────────┐
                    │     COMPOSITE RISK SCORE         │
                    │          (0 – 100)               │
                    ├─────────────────────────────────┤
                    │                                  │
                    │  Rule violations   × 0.40 weight │  ← Layer 1
                    │  Trend severity    × 0.35 weight │  ← Layer 2
                    │  Pattern matches   × 0.15 weight │  ← Layer 3 (rules)
                    │  ML predictions    × 0.10 weight │  ← Layer 3 (ML, when available)
                    │                                  │
                    │  = weighted_sum normalized 0–100  │
                    └─────────────────────────────────┘
```

**Component scoring:**

Layer 1 (Rule-Based) — max 100 points:
```
CRITICAL finding present  → 100
HIGH finding present      → 70
MEDIUM finding present    → 40
No findings               → 0
Multiple findings         → max(individual scores) + 10 per additional finding (cap 100)
```

Layer 2 (Trend Analysis) — max 100 points:
```
FOR each vital:
  slope severity: DANGEROUS → 30, CONCERNING → 15, STABLE → 0
  sustained abnormality: +20 per sustained
  multi-parameter divergence: +30 if ≥ 3 worsening

Score = sum (cap 100)
```

Layer 3 (Pattern Recognition) — max 100 points:
```
CRITICAL pattern match → 100
HIGH pattern match → 70
No pattern → 0
ML confidence > 0.8 → 80
ML confidence 0.5–0.8 → 50
ML confidence < 0.5 → 0
```

**Final Score:**
```
risk_score = (layer1_score × 0.40) + (layer2_score × 0.35) + 
             (layer3_rules × 0.15) + (layer3_ml × 0.10)
```

#### 9.2 Risk Zones

| Risk Score | Zone | Clinical Meaning | Response |
|---|---|---|---|
| 0–15 | GREEN | Stable, vitals normal | Routine monitoring |
| 16–35 | YELLOW | Mild concern, monitor closely | Increase observation frequency |
| 36–60 | ORANGE | Significant concern, deterioration possible | Clinical review within 15 min |
| 61–80 | RED | Active deterioration detected | Immediate clinical review |
| 81–100 | CRITICAL | Life-threatening deterioration | Emergency response, auto-retriage to RED |

#### 9.3 Risk Trajectory

In addition to the instantaneous score, track the trajectory over the last 30 minutes:

```java
public enum RiskTrajectory {
    IMPROVING,         // Risk score declining steadily (slope < -1.0/min)
    STABILIZING,       // Risk score flattening after a peak
    STABLE,            // Risk score steady (slope between -0.5 and +0.5/min)
    GRADUALLY_WORSENING, // Slow increase (slope +0.5 to +2.0/min)
    RAPIDLY_WORSENING,   // Fast increase (slope > +2.0/min)
    CRITICAL_ESCALATION  // Score crossed into RED/CRITICAL zone
}
```

---

## 10. Clinical Pattern Library

A structured, extensible library of clinical patterns. Each pattern has a unique ID, evidence-based criteria, and clinical significance.

### Pattern Structure

```java
public record ClinicalPattern(
    String patternId,           // e.g., "SEPSIS_SCREEN_V1"
    String name,                // "Sepsis Screening"
    PatternCategory category,   // INFECTIOUS, CARDIOVASCULAR, RESPIRATORY, NEUROLOGICAL
    List<PatternCriterion> criteria,
    int requiredCriteriaMet,    // How many criteria must match
    AlertSeverity severityWhenMatched,
    String clinicalSignificance,
    String suggestedActions,
    String evidenceBase         // Literature reference
) {}

public record PatternCriterion(
    String vitalParameter,      // "heartRate", "temperature", etc.
    CriterionType type,         // ABSOLUTE_THRESHOLD, TREND_SLOPE, RATE_OF_CHANGE
    double threshold,
    ComparisonOperator operator // GT, LT, GTE, LTE
) {}
```

### Pattern Registry (Version 1.0)

| ID | Name | Category | Criteria | Required | Severity |
|---|---|---|---|---|---|
| `SEPSIS_SCREEN_V1` | Sepsis Screening | INFECTIOUS | Temp>38.3∨<36, HR>90, RR>20 | 2 of 3 | HIGH |
| `SEPSIS_QSOFA_V1` | Sepsis qSOFA | INFECTIOUS | RR≥22, SBP≤100, altered mentation | 2 of 3 | CRITICAL |
| `RESP_DISTRESS_V1` | Respiratory Distress | RESPIRATORY | SpO2 slope<-0.3, RR>25∨rising, HR rising | 2 of 3 | HIGH |
| `RESP_FAILURE_V1` | Respiratory Failure | RESPIRATORY | SpO2<90%, RR>30 | 2 of 2 | CRITICAL |
| `SHOCK_COMP_V1` | Compensated Shock | CARDIOVASCULAR | SBP declining slope, HR rising slope | 2 of 2 | HIGH |
| `SHOCK_DECOMP_V1` | Decompensated Shock | CARDIOVASCULAR | SBP<80 sustained, HR>120 | 2 of 2 | CRITICAL |
| `HYPERTENSIVE_V1` | Hypertensive Crisis | CARDIOVASCULAR | SBP>180 sustained 5min, HR either extreme | 1 of 1 (SBP) | HIGH |
| `BRADY_HYPO_V1` | Bradycardia-Hypotension | CARDIOVASCULAR | HR<50, SBP<90 | 2 of 2 | CRITICAL |
| `HYPERTHERM_V1` | Malignant Hyperthermia | METABOLIC | Temp>39°C rising, HR>110 | 2 of 2 | HIGH |
| `HYPOTHERM_V1` | Severe Hypothermia | METABOLIC | Temp<35°C falling, HR<60 | 2 of 2 | CRITICAL |

### Pattern Extensibility

New patterns can be added by:
1. Defining the pattern in the registry (code or externalized configuration)
2. No changes to the engine — the `PatternRecognitionEngine` evaluates all registered patterns generically
3. Clinical governance approval required before activation in production

---

## 11. Alert Prioritization & Escalation Framework

### Alert Levels (Existing — Extended)

| Level | Name | Response Time | Notification | Visual/Audio |
|---|---|---|---|---|
| **CRITICAL** | Life-threatening | Immediate (< 30 sec) | WebSocket push + persistent banner + audible alarm | Red flashing, continuous alarm |
| **HIGH** | Serious concern | < 5 minutes | WebSocket push + prominent notification | Orange highlight, alert sound |
| **MEDIUM** | Monitor closely | < 15 minutes | WebSocket push + notification badge | Yellow indicator |
| **LOW** | Informational | Within shift | Dashboard indicator only | Blue indicator |

### Alert Intelligence Engine

To prevent **alert fatigue** (a well-documented problem in clinical monitoring), the engine implements intelligent alert management:

#### 11.1 Alert Deduplication

```
IF alert of same type + same severity exists for this visit
  AND was created within the last DEDUP_WINDOW minutes:
    → Do NOT generate a new alert
    → Instead, UPDATE the existing alert timestamp and append to description
    → Log: "Alert deduplicated (existing alert {id} updated)"

DEDUP_WINDOW:
  CRITICAL: 5 minutes (new alert every 5 min if issue persists — ensures attention)
  HIGH: 10 minutes
  MEDIUM: 20 minutes
  LOW: 60 minutes
```

#### 11.2 Progressive Escalation

If a condition persists without acknowledgment, the alert severity is escalated:

```
Alert created at t₀ with severity MEDIUM

t₀ + 10 min: If unacknowledged AND condition persists → escalate to HIGH
t₀ + 20 min: If unacknowledged AND condition persists → escalate to CRITICAL
t₀ + 30 min: If unacknowledged → generate ESCALATION alert to department head

Each escalation:
  → Creates a new ClinicalAlert with TEWS_ESCALATION type
  → References the original alert in the message
  → WebSocket push with escalation flag
```

#### 11.3 Alert Grouping

When multiple findings occur simultaneously, group them into a single composite alert rather than bombarding clinicians with 5 separate alerts:

```
IF analysis produces 3+ findings in a single cycle:
  → Generate ONE composite alert:
    Title: "Multiple Deterioration Indicators — {highest_pattern_name}"
    Severity: max(individual severities)
    Message: Enumerated list of all findings with individual details
  → Instead of 3 separate alerts
```

#### 11.4 Alert Rate Limiting

```
Maximum alerts per visit per hour:
  CRITICAL: Unlimited (never suppress life-threatening alerts)
  HIGH: 6 per hour (1 every 10 minutes)
  MEDIUM: 4 per hour
  LOW: 2 per hour

If rate limit reached for non-CRITICAL:
  → Log the suppressed alert in AIDecisionLog
  → Do NOT generate ClinicalAlert
  → Add note to next alert: "({N} additional alerts suppressed due to rate limiting)"
```

### Notification Targets

| Alert Level | WebSocket Topics | Additional |
|---|---|---|
| CRITICAL | `/topic/alerts/{hospitalId}`, `/topic/triage/{visitId}` | Push to ALL nurses on duty + attending doctor |
| HIGH | `/topic/alerts/{hospitalId}` | Push to assigned nurse + attending doctor |
| MEDIUM | `/topic/alerts/{hospitalId}` | Available on dashboard query |
| LOW | — (no push) | Available on dashboard query |

---

## 12. Automatic Re-Triage Decision Engine

### Decision Flow (Enhanced)

```
                    AI Analysis Complete
                           │
                   Deterioration detected?
                    ╱              ╲
                  NO               YES
                  │                 │
              Log & exit      Calculate suggested category
                                    │
                             Current category known?
                              ╱              ╲
                            NO                YES
                            │                  │
                      Use suggested     Compare severities
                                        ╱              ╲
                              Suggested ≤ Current    Suggested > Current
                                    │                       │
                              LOG ONLY              Check cooldown
                          (never auto-downgrade)     ╱          ╲
                                              On cooldown   Cooldown clear
                                                  │              │
                                             LOG ONLY     Check confidence
                                          (wait for next  ╱           ╲
                                            cycle)    Low conf.    High conf.
                                                        │              │
                                                  ALERT ONLY    PERFORM AUTO-RETRIAGE
                                               (suggest to       ├── Create VitalSigns snapshot
                                                clinician)       ├── Create TriageRecord
                                                                 │    (isSystemTriggered=true)
                                                                 ├── Update Visit
                                                                 ├── Generate CRITICAL alert
                                                                 ├── Push WebSocket
                                                                 └── Log AIDecisionLog
```

### Re-Triage Category Determination

```java
private TriageCategory determineTriageCategory(AnalysisResult result) {
    // Priority 1: Layer 1 rule-based determination
    if (result.hasRuleFinding(DeteriorationPattern.SPO2_OVERRIDE)) {
        return TriageCategory.RED;  // Rwanda protocol: SpO2 < 92% → RED always
    }

    // Priority 2: Risk score-based determination
    RiskScore risk = result.compositeRiskScore();
    if (risk.score() >= 81) return TriageCategory.RED;
    if (risk.score() >= 61) return TriageCategory.ORANGE;
    if (risk.score() >= 36) return TriageCategory.YELLOW;

    // Priority 3: TEWS-based determination (existing logic)
    int tews = result.computedTews();
    if (tews >= 7) return TriageCategory.RED;
    if (tews >= 5) return TriageCategory.ORANGE;
    if (tews >= 3) return TriageCategory.YELLOW;

    return TriageCategory.GREEN;
}
```

### Confidence-Based Triggering

Not all detections are equal. Low-confidence detections should alert but not auto-retriage:

```
Confidence Calculation:
  base_confidence = 0.0

  IF Layer 1 finding (rule violation): +0.40
  IF Layer 2 trend confirmed (R² > 0.5): +0.25
  IF Layer 2 sustained (> N minutes): +0.15
  IF Layer 3 pattern match: +0.15
  IF Layer 3 ML confidence > 0.8: +0.10
  IF multiple layers agree: +0.10 bonus

Auto-retriage threshold: confidence ≥ 0.60
Alert-only threshold: confidence ≥ 0.30
Below 0.30: log only (monitoring continues)
```

### Re-Triage Cooldown (Enhanced)

```
Current: Fixed 10-minute cooldown between auto-retriages.

Enhanced:
  GREEN-to-YELLOW retriage: 10-minute cooldown
  YELLOW-to-ORANGE retriage: 10-minute cooldown
  ORANGE-to-RED retriage: 5-minute cooldown (faster escalation in critical situations)
  Any-to-RED with SpO2 override: NO cooldown (immediate)

De-escalation (future, clinician-initiated only):
  Auto-retriage NEVER downgrades. Downgrade requires:
    1. Clinician action (manual retriage)
    2. System can suggest: "Patient stable for 30+ minutes, consider reassessment"
```

---

## 13. Explainability Framework

### Why Explainability is Non-Negotiable

In a medical context, a black-box AI alert is **clinically useless and legally dangerous**. Clinicians need to understand:
1. **What** was detected
2. **Why** the system thinks it's concerning
3. **What evidence** supports the conclusion
4. **What action** the system recommends
5. **What historical context** informed the decision

### ClinicalReasoning Structure

```java
/**
 * Structured explanation of an AI engine decision.
 * This is persisted for every analysis that produces a finding or alert.
 */
public record ClinicalReasoning(
    /** Human-readable summary (1-2 sentences) */
    String summary,

    /** Detailed evidence chain */
    List<ReasoningStep> steps,

    /** What clinical action is suggested */
    String recommendation,

    /** Confidence assessment */
    double confidence,
    String confidenceExplanation,

    /** Which engine layers contributed */
    List<String> contributingLayers,

    /** Timestamp of analysis */
    Instant analysedAt
) {}

public record ReasoningStep(
    int order,
    String layer,           // "RULE_ENGINE", "TREND_ANALYSIS", "PATTERN_RECOGNITION", "ML_MODEL"
    String check,           // "SpO2 threshold check", "Heart rate trend analysis"
    String observation,     // "SpO2 is 89%, below critical threshold of 92%"
    String significance,    // "Per Rwanda National Triage Protocol, SpO2 < 92% requires immediate RED triage"
    Map<String, Object> data  // Raw data: {"spo2": 89, "threshold": 92, "protocol": "Rwanda NTP"}
) {}
```

### Example Explainability Output

**Scenario:** Patient's SpO2 drops to 89% while HR rises to 118 bpm.

```json
{
  "summary": "Critical oxygen desaturation detected with compensatory tachycardia, consistent with acute respiratory compromise.",
  "steps": [
    {
      "order": 1,
      "layer": "RULE_ENGINE",
      "check": "SpO2 critical threshold (Rwanda protocol override)",
      "observation": "SpO2 is 89% — below critical threshold of 92%",
      "significance": "Per Rwanda National Triage Protocol, SpO2 below 92% mandates immediate RED (Emergency) triage categorization",
      "data": {"spo2": 89, "threshold": 92, "protocol": "Rwanda Adult Triage Form"}
    },
    {
      "order": 2,
      "layer": "TREND_ANALYSIS",
      "check": "SpO2 trend over 5-minute window",
      "observation": "SpO2 declining at -1.8%/min (R² = 0.91) — from 96% to 89% over 4 minutes",
      "significance": "Rapid, consistent decline exceeds dangerous threshold of -1.0%/min. High R² confirms this is a real trend, not sensor noise.",
      "data": {"slope": -1.8, "rSquared": 0.91, "windowMinutes": 5, "startValue": 96, "endValue": 89}
    },
    {
      "order": 3,
      "layer": "TREND_ANALYSIS",
      "check": "Heart rate trend over 5-minute window",
      "observation": "Heart rate rising at +4.1 bpm/min — from 92 to 118 bpm over 5 minutes",
      "significance": "Compensatory tachycardia is expected with hypoxia — the rising HR corroborates true respiratory compromise rather than a sensor artefact.",
      "data": {"slope": 4.1, "rSquared": 0.84, "startValue": 92, "endValue": 118}
    },
    {
      "order": 4,
      "layer": "PATTERN_RECOGNITION",
      "check": "Respiratory distress pattern (RESP_DISTRESS_V1)",
      "observation": "Pattern matched: declining SpO2 + rising HR (2 of 3 criteria met)",
      "significance": "This multi-parameter pattern is characteristic of acute respiratory distress. Isolated SpO2 drops could be a probe artefact, but the concurrent HR rise confirms a physiological response.",
      "data": {"patternId": "RESP_DISTRESS_V1", "criteriaMet": 2, "criteriaRequired": 2}
    }
  ],
  "recommendation": "IMMEDIATE clinical assessment required. Administer supplemental oxygen. Consider arterial blood gas analysis and chest imaging. Prepare for possible respiratory support escalation (NIV/intubation).",
  "confidence": 0.92,
  "confidenceExplanation": "High confidence: Rule violation confirmed (Layer 1), strong linear trend with R² > 0.9 (Layer 2), and multi-parameter clinical pattern match (Layer 3). All three engine layers converge on the same conclusion.",
  "contributingLayers": ["RULE_ENGINE", "TREND_ANALYSIS", "PATTERN_RECOGNITION"],
  "analysedAt": "2026-02-26T10:30:05Z"
}
```

---

## 14. Improvement & Stabilization Detection

### Why This Matters

Current systems only detect deterioration. But clinicians also need to know when a patient is **improving** — it affects:
- Whether to continue intensive monitoring
- Discharge readiness assessment
- Resource allocation (move monitor to a sicker patient)
- Triage re-evaluation (potential downgrade by clinician)

### Improvement Detection Logic

```java
@Component
public class ImprovementDetectionEngine {

    /**
     * Evaluate whether a patient's condition is improving or stabilizing.
     * Called on every analysis cycle. Findings are INFORMATIONAL (LOW severity).
     */
    public ImprovementResult evaluate(AnalysisContext context) {
        // Improvement = vitals moving FROM abnormal ranges TOWARD normal ranges
        // Stabilization = vitals were abnormal, now holding steady in an acceptable range
    }
}
```

#### Improvement Patterns

```
PATTERN: Vital Normalization
  IF a vital that was in abnormal range for > 5 min is now in normal range:
    → Finding: VITAL_NORMALIZING
    → "Heart rate normalized — was 125 bpm, now 88 bpm (within normal 60-100)"

PATTERN: Trend Reversal
  IF a vital had a concerning slope that has reversed:
    → Finding: TREND_REVERSED
    → "SpO2 trend reversed — was declining at -0.8%/min, now rising at +0.3%/min"

PATTERN: Sustained Improvement
  IF risk score has been declining for > 15 minutes:
    → Finding: SUSTAINED_IMPROVEMENT
    → "Patient condition improving — risk score declined from 55 to 22 over 18 minutes"

PATTERN: Stabilization After Intervention
  IF risk score peaked and has been stable (±5 points) for > 10 minutes:
    → Finding: CONDITION_STABILIZED
    → "Condition stabilized — risk score holding at 30 (±3) for 12 minutes"
```

#### Clinical Notification

```
Improvement findings:
  ✅ Displayed on dashboard as positive indicators
  ✅ Logged in AIDecisionLog
  ✅ Available via /api/v1/ai/insights/{visitId}
  ❌ Do NOT auto-downgrade triage (clinician decision only)
  ❌ Do NOT generate push alerts (reduce noise)
  ℹ️  CAN generate LOW severity informational alert if sustained > 30 min
     → "Consider clinical reassessment — patient stable/improving for 30+ minutes"
```

---

## 15. Data Architecture & Storage Strategy

### Three-Table Vital Data Strategy

| Table | Frequency | Purpose | Retention |
|---|---|---|---|
| `vital_streams` | Every 1–5 sec | Real-time analysis, AI engine input | 7 days hot, then archive/compress |
| `vital_signs` | Every few min | Clinical snapshots, TEWS, medical record | Permanent (legal requirement) |
| `ai_decision_logs` | Per analysis cycle | AI reasoning audit trail | Permanent (legal requirement) |

### New Table: `ai_decision_logs`

```sql
CREATE TABLE ai_decision_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id            UUID NOT NULL REFERENCES visits(id),
    session_id          UUID REFERENCES device_sessions(id),
    
    -- Analysis inputs
    analysis_timestamp  TIMESTAMPTZ NOT NULL DEFAULT now(),
    readings_analysed   INTEGER NOT NULL,
    window_start        TIMESTAMPTZ NOT NULL,
    window_end          TIMESTAMPTZ NOT NULL,
    
    -- Results
    risk_score          DOUBLE PRECISION NOT NULL,
    risk_trajectory     VARCHAR(30) NOT NULL,
    deterioration_detected BOOLEAN NOT NULL DEFAULT false,
    improvement_detected   BOOLEAN NOT NULL DEFAULT false,
    
    -- Findings (JSON array of ClinicalFinding objects)
    findings_json       JSONB NOT NULL DEFAULT '[]',
    
    -- Patterns matched
    patterns_json       JSONB NOT NULL DEFAULT '[]',
    
    -- Full reasoning chain
    reasoning_json      JSONB NOT NULL DEFAULT '{}',
    
    -- Actions taken
    alert_generated     BOOLEAN NOT NULL DEFAULT false,
    alert_id            UUID REFERENCES clinical_alerts(id),
    alert_severity      VARCHAR(15),
    retriage_triggered  BOOLEAN NOT NULL DEFAULT false,
    retriage_record_id  UUID REFERENCES triage_records(id),
    new_triage_category VARCHAR(10),
    previous_triage_category VARCHAR(10),
    
    -- Engine metadata
    layer1_score        DOUBLE PRECISION,
    layer2_score        DOUBLE PRECISION,
    layer3_rules_score  DOUBLE PRECISION,
    layer3_ml_score     DOUBLE PRECISION,
    confidence          DOUBLE PRECISION,
    processing_time_ms  INTEGER,
    engine_version      VARCHAR(20) NOT NULL,
    
    -- Audit
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active           BOOLEAN NOT NULL DEFAULT true,
    
    CONSTRAINT fk_ai_log_visit FOREIGN KEY (visit_id) REFERENCES visits(id)
);

-- Performance indexes
CREATE INDEX idx_ai_log_visit_time ON ai_decision_logs(visit_id, analysis_timestamp DESC);
CREATE INDEX idx_ai_log_deterioration ON ai_decision_logs(visit_id, deterioration_detected) 
    WHERE deterioration_detected = true;
CREATE INDEX idx_ai_log_retriage ON ai_decision_logs(retriage_triggered) 
    WHERE retriage_triggered = true;
```

### New Table: `ai_risk_snapshots` (Aggregated Risk Over Time)

```sql
CREATE TABLE ai_risk_snapshots (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id            UUID NOT NULL REFERENCES visits(id),
    snapshot_time       TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    risk_score          DOUBLE PRECISION NOT NULL,
    risk_zone           VARCHAR(15) NOT NULL,    -- GREEN, YELLOW, ORANGE, RED, CRITICAL
    risk_trajectory     VARCHAR(30) NOT NULL,
    
    -- Per-vital trend summaries
    hr_trend            VARCHAR(20),     -- RISING_FAST, RISING, STABLE, FALLING, FALLING_FAST
    hr_slope            DOUBLE PRECISION,
    spo2_trend          VARCHAR(20),
    spo2_slope          DOUBLE PRECISION,
    rr_trend            VARCHAR(20),
    rr_slope            DOUBLE PRECISION,
    sbp_trend           VARCHAR(20),
    sbp_slope           DOUBLE PRECISION,
    temp_trend          VARCHAR(20),
    temp_slope          DOUBLE PRECISION,
    
    -- Aggregation metadata
    readings_in_window  INTEGER NOT NULL,
    
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active           BOOLEAN NOT NULL DEFAULT true
);

-- One snapshot per minute max — for dashboard risk timeline
CREATE INDEX idx_risk_snap_visit_time ON ai_risk_snapshots(visit_id, snapshot_time DESC);
```

### Data Flow Summary

```
Every 3–5 seconds (per device):
  → VitalStream INSERT (high-frequency, AI input)
  → AI analysis runs (< 200ms)
  → AIDecisionLog INSERT (every analysis cycle)

Every 60 seconds:
  → AIRiskSnapshot INSERT (for dashboard timeline)

On deterioration detection:
  → ClinicalAlert INSERT
  → WebSocket push

On auto-retriage:
  → VitalSigns INSERT (clinical snapshot)
  → TriageRecord INSERT
  → Visit UPDATE
  → WebSocket push
```

---

## 16. Real-Time Communication & Notification

### Extended WebSocket Topics

| Topic | Payload | Trigger |
|---|---|---|
| `/topic/vitals/{visitId}` | VitalStreamResponse | Every validated reading |
| `/topic/alerts/{hospitalId}` | Alert data | On alert generation |
| `/topic/devices/{hospitalId}` | Device status | Device online/offline |
| `/topic/triage/{visitId}` | Triage change | On retriage |
| `/topic/ai/risk/{visitId}` | **NEW** — RiskScore + trajectory | Every analysis cycle |
| `/topic/ai/insights/{visitId}` | **NEW** — Clinical findings | On pattern detection |

### AI Risk Dashboard Data

```json
{
  "visitId": "...",
  "riskScore": 65,
  "riskZone": "RED",
  "riskTrajectory": "RAPIDLY_WORSENING",
  "trends": {
    "heartRate": {"direction": "RISING", "slope": 3.2, "latest": 118},
    "spo2": {"direction": "FALLING_FAST", "slope": -1.8, "latest": 89},
    "respiratoryRate": {"direction": "RISING", "slope": 1.5, "latest": 28},
    "systolicBp": {"direction": "STABLE", "slope": -0.3, "latest": 115},
    "temperature": {"direction": "STABLE", "slope": 0.02, "latest": 37.4}
  },
  "activeFindings": [
    {
      "pattern": "SPO2_OVERRIDE",
      "severity": "CRITICAL",
      "summary": "SpO2 89% — below 92% protocol threshold"
    },
    {
      "pattern": "RESPIRATORY_DISTRESS_PATTERN",
      "severity": "HIGH",
      "summary": "Declining SpO2 with rising heart rate"
    }
  ],
  "lastAnalysisAt": "2026-02-26T10:30:05Z"
}
```

---

## 17. Reliability, Fault Tolerance & Latency

### Failure Modes & Mitigations

| Failure Mode | Detection | Mitigation |
|---|---|---|
| **AI engine exception** | try-catch in ContinuousMonitoringEngine | Engine failure NEVER blocks data ingestion. Vital is persisted regardless. Error logged. Alert generated: "AI monitoring temporarily unavailable." |
| **Database timeout** | JPA transaction timeout | Retry with exponential backoff (max 3 attempts). If all fail, log to local file as fallback audit trail. |
| **WebSocket disconnect** | STOMP heartbeat | Alerts are persisted in DB — dashboard can poll as fallback. WebSocket is a delivery optimization, not the single source of truth. |
| **Device disconnect** | DeviceHeartbeatScheduler (every 15 sec) | CRITICAL alert: "Device disconnected — patient unmonitored." Auto-close session. This is the existing fail-safe and remains unchanged. |
| **Stale data** | Timestamp comparison | If latest reading is > 30 seconds old, AI engine operates in "stale data" mode — uses last known trends but does not generate new findings. Generates a warning alert. |
| **High CPU load** | Processing time tracking | If AI analysis exceeds 500ms, log warning. If exceeds 2 seconds, skip Layer 3 (ML) and return Layer 1+2 results only (graceful degradation). |
| **Memory pressure** | JVM heap monitoring | Trend analysis window sizes are bounded. Maximum readings in memory: 1800 per visit (30 min at 1/sec). Older data is not loaded. |

### Latency Guarantees

```
Category A — CRITICAL alerts:
  End-to-end latency target: < 1 second
  Meaning: From the moment the ESP32 sends a critical reading to when the 
  alert appears on the clinician's dashboard.

Category B — Standard analysis:
  End-to-end latency target: < 3 seconds
  Meaning: From device reading to dashboard Risk Score update.

Category C — AI insights:
  End-to-end latency target: < 5 seconds
  Meaning: From reading to detailed clinical reasoning available via API.
```

### Graceful Degradation Hierarchy

If the system is under stress, layers are shed in order:

```
FULL MODE (normal):
  Layer 1 + Layer 2 + Layer 3 (rules) + Layer 3 (ML) + Alert Intelligence + Audit

DEGRADED MODE 1 (high load):
  Layer 1 + Layer 2 + Layer 3 (rules) + Alert Intelligence + Audit
  → ML models skipped

DEGRADED MODE 2 (very high load):
  Layer 1 + Layer 2 + Audit
  → Pattern recognition skipped, trend analysis simplified

EMERGENCY MODE (critical overload or AI engine failure):
  Layer 1 ONLY + Audit
  → Pure rule-based checks. Always available. Never fails silently.

Data ingestion and persistence: ALWAYS RUNS regardless of AI engine state.
```

---

## 18. Audit Logging & Medico-Legal Traceability

### Audit Requirements

In a hospital environment, every AI-generated clinical decision is a medico-legal record. The audit trail must be:

1. **Immutable** — Once written, an audit log entry can never be modified or deleted
2. **Complete** — Every analysis cycle is logged, not just those with findings
3. **Traceable** — Every alert and retriage can be traced back to the exact readings, the exact rules that fired, and the exact reasoning
4. **Timestamped** — Server-side timestamps (not device-side) for legal accuracy
5. **Attributable** — System-triggered actions are clearly marked as AI-generated

### Audit Chain

```
For any auto-retriage event, the following chain MUST be reconstructable:

1. VitalStream readings (which raw IoT data triggered analysis)
   → visit_id, captured_at, all vital values, is_validated, signal_quality

2. AIDecisionLog (what the AI concluded)
   → risk_score, findings_json, reasoning_json, confidence, engine_version

3. ClinicalAlert (what alert was generated)
   → alert_type, severity, title, message, auto_generated=true

4. TriageRecord (what triage change was made)
   → is_system_triggered=true, previous_category, new category, decision_path
   → References the VitalSigns snapshot used for TEWS

5. VitalSigns snapshot (the clinical data used for TEWS calculation)
   → source=IOT_DEVICE, device_id, recorded_at

Every entity in this chain:
  - Has a UUID primary key
  - Has createdAt, updatedAt, createdBy, lastModifiedBy (from BaseEntity)
  - Has isActive flag (soft delete only — never physically deleted)
  - Has version field (optimistic locking)
```

### AIDecisionLog Immutability

```java
@Entity
@Table(name = "ai_decision_logs")
public class AIDecisionLog extends BaseEntity {
    // ... fields as defined in Section 15 ...

    /**
     * Override softDelete to PREVENT deletion of AI decision logs.
     * These are medico-legal records and must be permanently retained.
     */
    @Override
    public void softDelete() {
        throw new UnsupportedOperationException(
            "AI decision logs cannot be deleted — medico-legal audit requirement");
    }
}
```

---

## 19. Medical Calibration & Validation Strategy

### Calibration Approach

The AI engine's thresholds and rules are derived from:

| Source | What It Informs | Confidence Level |
|---|---|---|
| **Rwanda National Standard Triage Protocol** | TEWS thresholds, triage categories, SpO2 override | Highest — national clinical standard |
| **WHO Emergency Triage Assessment** | Pediatric emergency signs | Highest — international standard |
| **qSOFA / SIRS criteria** | Sepsis screening | High — WHO Sepsis-3 consensus |
| **Clinical consensus (ICU practice)** | Rapid decline thresholds, shock patterns | High — standard ICU monitoring practice |
| **Statistical analysis** | Trend slope thresholds, R² cutoffs | Medium — requires local calibration |
| **ML models (future)** | Sepsis prediction, etc. | Low initially — requires prospective validation |

### Validation Phases

#### Phase 1: Shadow Mode (Pre-Clinical Deployment)

```
Duration: 2-4 weeks
Setup: AI engine runs alongside existing ContinuousMonitoringEngine
Behavior:
  - AI generates findings and risk scores
  - AI logs all decisions to ai_decision_logs
  - AI does NOT generate alerts or trigger retriages
  - Existing engine continues to operate as-is
  
Analysis:
  - Compare AI findings with clinician-performed retriages
  - Measure: How often would AI have detected deterioration before clinician noticed?
  - Measure: How many false positives would AI have generated?
  - Measure: Alert fatigue projection (alerts per patient per hour)
```

#### Phase 2: Advisory Mode (Clinical Pilot)

```
Duration: 4-8 weeks
Setup: AI generates INFORMATIONAL alerts (LOW severity only)
Behavior:
  - AI produces risk scores and insights on dashboard
  - AI generates advisory alerts ("AI suggests clinical review — see reasoning")
  - AI does NOT auto-retriage
  - Clinicians provide feedback (agree/disagree with AI assessment)
  
Analysis:
  - Clinician agreement rate with AI findings
  - Time-to-detection improvement vs manual monitoring
  - Collect feedback to tune thresholds
```

#### Phase 3: Active Mode (Production)

```
Duration: Ongoing
Setup: Full AI engine operation
Behavior:
  - AI generates alerts at appropriate severity levels
  - AI performs auto-retriage (with all safeguards: cooldown, escalation-only, confidence threshold)
  - Clinician must still review and confirm auto-retriages
  
Monitoring:
  - Continuous tracking: sensitivity, specificity, positive predictive value
  - Monthly review with clinical governance team
  - Threshold adjustment based on accumulated data
```

### Threshold Calibration Process

```
1. Start with conservative thresholds (high sensitivity, lower specificity)
   → Will generate more alerts, but won't miss deterioration

2. Collect 30 days of data:
   - Total alerts generated
   - Alerts acknowledged as clinically relevant
   - Alerts dismissed as false positives
   - Deterioration events that were NOT detected (false negatives) — from manual chart review

3. Calculate:
   - Sensitivity = true_positives / (true_positives + false_negatives)
   - Specificity = true_negatives / (true_negatives + false_positives)
   - PPV = true_positives / (true_positives + false_positives)
   - Alert-to-action ratio = alerts_acted_upon / total_alerts

4. Adjust thresholds:
   - If sensitivity < 95%: LOWER thresholds (catch more)
   - If PPV < 40%: RAISE thresholds (fewer false positives)
   - If alert-to-action ratio < 30%: Increase dedup window, raise non-critical thresholds

5. Clinical governance review and approval before any threshold change in production
```

---

## 20. Module Structure & Integration Map

### Complete Module Architecture

```
module/
├── ai/                                           ← NEW MODULE (AI Monitoring Engine)
│   ├── engine/
│   │   ├── AIAnalysisOrchestrator.java           ← Main orchestrator, called by ContinuousMonitoringEngine
│   │   ├── RuleBasedDetectionEngine.java         ← Layer 1: Deterministic clinical rules
│   │   ├── TrendAnalysisEngine.java              ← Layer 2: Statistical trend analysis
│   │   ├── PatternRecognitionEngine.java         ← Layer 3: Clinical pattern matching
│   │   ├── RiskScoringEngine.java                ← Composite risk score calculator
│   │   ├── AlertIntelligenceEngine.java          ← Alert dedup, grouping, escalation
│   │   └── ImprovementDetectionEngine.java       ← Stabilization and improvement detection
│   ├── model/
│   │   ├── AnalysisContext.java                  ← Input: patient, readings, history
│   │   ├── AnalysisResult.java                   ← Output: findings, score, reasoning
│   │   ├── ClinicalFinding.java                  ← Single finding (what + why + severity)
│   │   ├── ClinicalReasoning.java                ← Structured explanation chain
│   │   ├── ReasoningStep.java                    ← Single step in reasoning chain
│   │   ├── RiskScore.java                        ← Composite risk value object
│   │   ├── RiskTrajectory.java                   ← Enum: IMPROVING, STABLE, WORSENING...
│   │   ├── TrendResult.java                      ← Per-parameter trend output
│   │   ├── TrendDirection.java                   ← Enum: RISING_FAST, RISING, STABLE...
│   │   ├── PatternMatch.java                     ← Clinical pattern match result
│   │   ├── ClinicalPattern.java                  ← Pattern definition (criteria + response)
│   │   ├── ImprovementResult.java                ← Improvement detection output
│   │   ├── AlertDecision.java                    ← Should alert? What severity?
│   │   └── RetriageDecision.java                 ← Should retriage? What category?
│   ├── ml/                                       ← FUTURE: ML model integration
│   │   ├── ClinicalMLModel.java                  ← Interface for pluggable ML models
│   │   ├── MLPrediction.java                     ← Model output (label + confidence)
│   │   └── MLModelRegistry.java                  ← Registry of available models
│   ├── pattern/
│   │   └── ClinicalPatternRegistry.java          ← Registry of all clinical patterns (V1)
│   ├── entity/
│   │   ├── AIDecisionLog.java                    ← Immutable audit entity
│   │   └── AIRiskSnapshot.java                   ← Periodic risk score snapshot
│   ├── repository/
│   │   ├── AIDecisionLogRepository.java
│   │   └── AIRiskSnapshotRepository.java
│   ├── dto/
│   │   ├── AIInsightResponse.java                ← API response: risk + findings + reasoning
│   │   ├── PatientRiskSummary.java               ← Dashboard: risk score + trajectory
│   │   └── RiskTimelineResponse.java             ← Dashboard: historical risk scores
│   ├── mapper/
│   │   └── AIMapper.java                         ← Entity ↔ DTO mapping
│   ├── config/
│   │   └── AIEngineConfig.java                   ← Externalized thresholds + feature flags
│   └── controller/
│       └── AIInsightController.java              ← GET /api/v1/ai/insights/{visitId}
│
├── iot/
│   ├── engine/
│   │   ├── ContinuousMonitoringEngine.java       ← REFACTORED: delegates to AIAnalysisOrchestrator
│   │   └── VitalValidationEngine.java            ← Unchanged
│   ...
```

### Integration Point Detail

```java
// ContinuousMonitoringEngine (REFACTORED)
@Transactional
public MonitoringResult analyseAndRespond(UUID visitId, DeviceSession session) {
    // 1. Build analysis context (readings, visit, triage state)
    AnalysisContext context = buildContext(visitId, session);
    
    // 2. Delegate to AI Orchestrator
    AnalysisResult result = aiOrchestrator.analyse(context);
    
    // 3. Process result (alerts, retriage, WebSocket, audit)
    return processResult(result, session);
}
```

```java
// AIAnalysisOrchestrator
@Component
public class AIAnalysisOrchestrator {

    public AnalysisResult analyse(AnalysisContext context) {
        long startTime = System.currentTimeMillis();
        
        // Layer 1: Rule-based (ALWAYS runs)
        List<ClinicalFinding> ruleFindings = ruleEngine.evaluate(context);
        
        // Layer 2: Trend analysis (runs if sufficient data)
        TrendAnalysisResult trends = trendEngine.analyse(context);
        
        // Layer 3: Pattern recognition (runs if enabled)
        List<PatternMatch> patterns = patternEngine.matchPatterns(context, trends);
        
        // Improvement detection
        ImprovementResult improvement = improvementEngine.evaluate(context);
        
        // Composite risk scoring
        RiskScore riskScore = riskEngine.calculate(ruleFindings, trends, patterns);
        
        // Alert intelligence (dedup, grouping, rate limiting)
        AlertDecision alertDecision = alertIntelligence.decide(
            context, ruleFindings, trends, patterns, riskScore);
        
        // Retriage decision
        RetriageDecision retriageDecision = evaluateRetriage(
            context, riskScore, ruleFindings, alertDecision);
        
        // Build reasoning chain
        ClinicalReasoning reasoning = buildReasoning(
            ruleFindings, trends, patterns, riskScore, alertDecision);
        
        long processingTime = System.currentTimeMillis() - startTime;
        
        return new AnalysisResult(
            ruleFindings, trends, patterns, improvement,
            riskScore, alertDecision, retriageDecision,
            reasoning, processingTime
        );
    }
}
```

### New API Endpoints

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `GET` | `/api/v1/ai/insights/{visitId}` | Auth | Latest AI analysis for a visit (risk score, findings, reasoning) |
| `GET` | `/api/v1/ai/risk-timeline/{visitId}` | Auth | Historical risk scores (for dashboard chart) |
| `GET` | `/api/v1/ai/decision-log/{visitId}` | Auth | Full decision audit log |
| `GET` | `/api/v1/ai/patterns` | Auth | List all registered clinical patterns |

---

## 21. Database Migration Plan

### V7 — AI Monitoring Engine

```sql
-- Migration: V7__ai_monitoring_engine.sql
-- Description: AI decision audit log and risk snapshot tables for the AI Monitoring Engine

-- ============================================================
-- AI Decision Logs (Immutable audit trail)
-- ============================================================
CREATE TABLE ai_decision_logs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id                UUID NOT NULL,
    session_id              UUID,
    
    analysis_timestamp      TIMESTAMPTZ NOT NULL DEFAULT now(),
    readings_analysed       INTEGER NOT NULL,
    window_start            TIMESTAMPTZ NOT NULL,
    window_end              TIMESTAMPTZ NOT NULL,
    
    risk_score              DOUBLE PRECISION NOT NULL,
    risk_zone               VARCHAR(15) NOT NULL,
    risk_trajectory         VARCHAR(30) NOT NULL,
    deterioration_detected  BOOLEAN NOT NULL DEFAULT false,
    improvement_detected    BOOLEAN NOT NULL DEFAULT false,
    
    findings_json           JSONB NOT NULL DEFAULT '[]'::jsonb,
    patterns_json           JSONB NOT NULL DEFAULT '[]'::jsonb,
    reasoning_json          JSONB NOT NULL DEFAULT '{}'::jsonb,
    
    alert_generated         BOOLEAN NOT NULL DEFAULT false,
    alert_id                UUID,
    alert_severity          VARCHAR(15),
    retriage_triggered      BOOLEAN NOT NULL DEFAULT false,
    retriage_record_id      UUID,
    new_triage_category     VARCHAR(10),
    previous_triage_category VARCHAR(10),
    
    layer1_score            DOUBLE PRECISION,
    layer2_score            DOUBLE PRECISION,
    layer3_rules_score      DOUBLE PRECISION,
    layer3_ml_score         DOUBLE PRECISION,
    confidence              DOUBLE PRECISION,
    processing_time_ms      INTEGER,
    engine_version          VARCHAR(20) NOT NULL,
    
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    last_modified_by        VARCHAR(255),
    is_active               BOOLEAN NOT NULL DEFAULT true,
    version                 BIGINT DEFAULT 0,
    
    CONSTRAINT fk_ai_log_visit FOREIGN KEY (visit_id) REFERENCES visits(id),
    CONSTRAINT fk_ai_log_session FOREIGN KEY (session_id) REFERENCES device_sessions(id),
    CONSTRAINT fk_ai_log_alert FOREIGN KEY (alert_id) REFERENCES clinical_alerts(id),
    CONSTRAINT fk_ai_log_triage FOREIGN KEY (retriage_record_id) REFERENCES triage_records(id)
);

CREATE INDEX idx_ai_log_visit_time ON ai_decision_logs(visit_id, analysis_timestamp DESC);
CREATE INDEX idx_ai_log_deterioration ON ai_decision_logs(visit_id, deterioration_detected) 
    WHERE deterioration_detected = true AND is_active = true;
CREATE INDEX idx_ai_log_retriage ON ai_decision_logs(retriage_triggered) 
    WHERE retriage_triggered = true AND is_active = true;
CREATE INDEX idx_ai_log_engine_version ON ai_decision_logs(engine_version);

-- ============================================================
-- AI Risk Snapshots (Periodic risk score for timeline)
-- ============================================================
CREATE TABLE ai_risk_snapshots (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id                UUID NOT NULL,
    snapshot_time           TIMESTAMPTZ NOT NULL DEFAULT now(),
    
    risk_score              DOUBLE PRECISION NOT NULL,
    risk_zone               VARCHAR(15) NOT NULL,
    risk_trajectory         VARCHAR(30) NOT NULL,
    
    hr_trend                VARCHAR(20),
    hr_slope                DOUBLE PRECISION,
    spo2_trend              VARCHAR(20),
    spo2_slope              DOUBLE PRECISION,
    rr_trend                VARCHAR(20),
    rr_slope                DOUBLE PRECISION,
    sbp_trend               VARCHAR(20),
    sbp_slope               DOUBLE PRECISION,
    temp_trend              VARCHAR(20),
    temp_slope              DOUBLE PRECISION,
    
    readings_in_window      INTEGER NOT NULL,
    
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ,
    created_by              VARCHAR(255),
    last_modified_by        VARCHAR(255),
    is_active               BOOLEAN NOT NULL DEFAULT true,
    version                 BIGINT DEFAULT 0,
    
    CONSTRAINT fk_risk_snap_visit FOREIGN KEY (visit_id) REFERENCES visits(id)
);

CREATE INDEX idx_risk_snap_visit_time ON ai_risk_snapshots(visit_id, snapshot_time DESC);
CREATE INDEX idx_risk_snap_zone ON ai_risk_snapshots(risk_zone) 
    WHERE is_active = true;

-- ============================================================
-- Add new DeteriorationPattern enum values
-- ============================================================
-- (handled by Java enum — no schema changes needed for enums stored as VARCHAR)

-- ============================================================
-- Add new AlertType enum values
-- ============================================================
-- New AlertType values: AI_RISK_ESCALATION, AI_CLINICAL_INSIGHT, AI_IMPROVEMENT_DETECTED
-- (handled by Java enum — no schema changes needed)
```

---

## 22. Implementation Roadmap

### Phase 1: Foundation (Weeks 1–3)

| Week | Deliverable | Details |
|---|---|---|
| 1 | AI module scaffolding | Create `module/ai/` with all packages, model classes, interfaces |
| 1 | AnalysisContext & AnalysisResult | Core data structures |
| 1 | AIDecisionLog entity + migration V7 | Database layer |
| 2 | RuleBasedDetectionEngine | Migrate + enhance existing rules from ContinuousMonitoringEngine |
| 2 | AIAnalysisOrchestrator | Basic orchestration (Layer 1 only) |
| 2 | ContinuousMonitoringEngine refactor | Delegate to orchestrator, preserve all existing behavior |
| 3 | Explainability framework | ClinicalReasoning, ReasoningStep, reasoning builder |
| 3 | Integration tests | Verify no regression from refactoring |

### Phase 2: Trend Analysis (Weeks 4–5)

| Week | Deliverable | Details |
|---|---|---|
| 4 | TrendAnalysisEngine | Linear regression, rate-of-change, moving averages |
| 4 | TrendResult + TrendDirection | Value objects |
| 4 | Sustained abnormality detection | Rolling window confirmation |
| 5 | Multi-parameter divergence | Cross-vital correlation |
| 5 | RiskScoringEngine | Composite score from Layer 1 + Layer 2 |
| 5 | AIRiskSnapshot persistence | Periodic snapshots for timeline |

### Phase 3: Clinical Patterns (Weeks 6–7)

| Week | Deliverable | Details |
|---|---|---|
| 6 | ClinicalPatternRegistry | Pattern definition framework |
| 6 | PatternRecognitionEngine | Generic pattern matcher |
| 6 | Sepsis + respiratory patterns | First pattern implementations |
| 7 | Hemodynamic + temperature patterns | Additional patterns |
| 7 | ImprovementDetectionEngine | Stabilization and improvement tracking |

### Phase 4: Alert Intelligence (Week 8)

| Week | Deliverable | Details |
|---|---|---|
| 8 | AlertIntelligenceEngine | Deduplication, grouping, progressive escalation, rate limiting |
| 8 | Enhanced auto-retriage | Confidence-based triggering, dynamic cooldown |
| 8 | AI API endpoints | `/api/v1/ai/insights/{visitId}`, risk timeline, decision log |

### Phase 5: Validation & Calibration (Weeks 9–12)

| Week | Deliverable | Details |
|---|---|---|
| 9–10 | Shadow mode deployment | AI runs in parallel, logs only, no clinical actions |
| 10–11 | Threshold tuning | Adjust based on shadow mode data |
| 11–12 | Advisory mode | AI generates informational alerts, clinician feedback loop |

### Phase 6: ML Integration (Future)

| Deliverable | Details |
|---|---|
| ML interface + model registry | Pluggable model framework |
| ONNX runtime integration | In-process model inference |
| First ML model (sepsis) | Train on collected SmartTriage data + public datasets |
| Prospective validation | 6-month clinical trial before promotion to active use |

---

## 23. Risk Register

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| **Alert fatigue** (too many false positives) | HIGH | Clinicians ignore alerts | Alert intelligence engine (dedup, rate limiting), threshold calibration, shadow mode validation |
| **Missed deterioration** (false negative) | LOW | Patient harm | Conservative thresholds, Layer 1 safety net always active, multiple detection methods |
| **AI engine crash blocks data ingestion** | MEDIUM | Data loss | Engine failure isolated with try-catch; data ingestion always completes; graceful degradation |
| **Threshold miscalibration for Rwandan population** | MEDIUM | Inappropriate responses | Calibrate with local clinical data, phased rollout, clinical governance review |
| **ML model bias** | MEDIUM (future) | Inappropriate predictions for certain patient groups | Prospective validation, ML is advisory-only until validated, feature importance logging |
| **Legal liability from AI-triggered retriage** | MEDIUM | Hospital liability | Full audit trail, AI is advisory (clinician must confirm), `isSystemTriggered` flag, explainability for every decision |
| **Data volume overwhelms database** | MEDIUM | Performance degradation | VitalStream archival after 7 days, indexed queries, bounded analysis windows, AIRiskSnapshot instead of raw log for timeline |
| **Network partition (device ↔ server)** | MEDIUM | Missed readings | DeviceHeartbeatScheduler alerts for disconnection, device-side local alerting (existing in firmware) |

---

## Summary

The SmartTriage AI Monitoring Engine is a **three-layer hybrid intelligence system** that:

1. **Never compromises on safety** — the rule-based Layer 1 is the deterministic safety net that always runs, always catches critical thresholds, and always complies with the Rwanda National Triage Protocol.

2. **Adds clinical depth** — Layer 2 statistical trend analysis catches gradual deterioration (the insidious decline that is the #1 killer in hospital monitoring) and Layer 3 pattern recognition identifies complex clinical syndromes.

3. **Manages its own noise** — the Alert Intelligence Engine prevents alert fatigue through deduplication, grouping, and progressive escalation.

4. **Explains itself** — every decision carries a full, structured clinical reasoning chain that clinicians can audit and trust.

5. **Learns to be better** — the ML layer (Phase 2) is designed in from the start with clean interfaces, safety constraints, and a validation pathway.

6. **Never hides** — every analysis cycle, every finding, every alert, and every retriage decision is immutably logged in the `ai_decision_logs` table for medico-legal traceability.

The engine integrates seamlessly with the existing SmartTriage monolith as an internal module, with a clear extraction path to a microservice if horizontal scaling is ever needed. It adds approximately 25–30 new Java source files and 2 new database tables, with no breaking changes to the existing 143-file codebase.

---

*SmartTriage AI Monitoring Engine — Designed for Clinical Safety, Built for Scale*  
*Rwanda National Standard Triage Protocol Compliant*  
*Spring Boot 4.0.3 · Java 21 · PostgreSQL 14*
