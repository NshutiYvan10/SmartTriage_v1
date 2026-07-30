// =====================================================================
//  cal.h — runtime vitals calibration (NVS-persisted)
//
//  WHY THIS EXISTS. The MAX30205 is a ±0.1 °C CONTACT sensor: it reports
//  the true temperature of whatever surface it touches. Peripheral skin
//  genuinely sits several degrees below core (wrist/finger ~30-34 °C),
//  and the gap depends on placement, strap pressure and ambient — so a
//  compile-time constant (TEMP_SITE_OFFSET_C, tuned for a snug axillary
//  site) cannot be right for every box. Same story for SpO2: the classic
//  110−25R curve is a population fit; an individual sensor/LED pair can
//  sit a couple of points off and earns a small trim against a reference
//  oximeter.
//
//  So, like the touch calibration ("touch" NVS) and the BP pressure scale
//  ("bp" NVS), the site offset and SpO2 trim are now DEVICE-CALIBRATED
//  values: persisted in NVS namespace "vitalcal", defaulting to the old
//  compile-time behaviour until a calibration is run, surviving reflash.
//
//  Calibration is driven over the serial console (the monitor is always
//  on a bench with a serial monitor attached when accuracy work happens):
//
//      cal show              current offsets + live readings
//      cal temp 36.8         reference core temp (clinical thermometer)
//      cal spo2 97           reference SpO2 (reference pulse oximeter)
//      cal temp reset        back to compile-time default
//      cal spo2 reset        back to zero trim
//
//  "cal temp 36.8" computes  newOffset = oldOffset + (36.8 − displayed)
//  from the CURRENT smoothed reading, so the procedure is simply: attach
//  the sensor exactly as it will be used, wait for the reading to
//  plateau, take the reference measurement, type it in. The math and the
//  stored result are printed back for the calibration record.
//
//  HR and RR have no calibration constants on purpose: both are timing /
//  counting measurements (beat intervals, breath cycles) — they are
//  either detected correctly or rejected, there is no scale to trim.
//  BP keeps its own richer calibration in bp.h (CAL PUMP + stored
//  counts-per-mmHg scale, reference-validated).
// =====================================================================
#pragma once

#include <Preferences.h>
#include "config.h"

// Safety rails: an offset beyond these is a placement/coupling problem
// (sensor in air, strap loose), not a calibration — refuse to store it.
// A CONTACT sensor's site offset is small: deep axilla is about +0.4 C, and
// no legitimate placement needs more than ~1.5 C. The old 10 C rail happily
// stored a SITE ERROR: running "cal temp 36.8" with the probe on the WRIST
// (raw ~31) stores +5.8 C — and calibrating is the act that OPENS both the
// transmission gate (net.h) and the alarm gate (alarms.h). Correct axillary
// placement afterwards then reads 36.4 + 5.8 = 42.2 C, clamps to TEMP_MAX,
// transmits, and fires critical hyperthermia. The rail refuses that outright.
#define CAL_TEMP_OFFSET_MAX    1.5f   // °C, either direction
#define CAL_SPO2_TRIM_MAX      6.0f   // points, either direction

class VitalCal {
public:
  void begin() {
    prefs_.begin("vitalcal", false);
    tempStored_ = prefs_.isKey("tofs");
    spo2Stored_ = prefs_.isKey("sofs");
    tempOffset_ = prefs_.getFloat("tofs", TEMP_SITE_OFFSET_C);
    spo2Trim_   = prefs_.getFloat("sofs", 0.0f);
    Serial.printf("[cal] temp site offset %+.2f C (%s), spo2 trim %+.1f pt (%s)\n",
                  tempOffset_, tempStored_ ? "device-calibrated" : "default",
                  spo2Trim_, spo2Stored_ ? "device-calibrated" : "default");
  }

  float tempOffset() const { return tempOffset_; }
  float spo2Trim()   const { return spo2Trim_; }
  bool  tempCalibrated() const { return tempStored_; }
  bool  spo2Calibrated() const { return spo2Stored_; }

  // Returns false (and stores nothing) when the requested offset is
  // outside the plausibility rails.
  bool setTempOffset(float v) {
    if (fabsf(v) > CAL_TEMP_OFFSET_MAX) return false;
    tempOffset_ = v;
    prefs_.putFloat("tofs", v);
    tempStored_ = true;
    return true;
  }
  bool setSpo2Trim(float v) {
    if (fabsf(v) > CAL_SPO2_TRIM_MAX) return false;
    spo2Trim_ = v;
    prefs_.putFloat("sofs", v);
    spo2Stored_ = true;
    return true;
  }

  void resetTemp() { prefs_.remove("tofs"); tempOffset_ = TEMP_SITE_OFFSET_C; tempStored_ = false; }
  void resetSpo2() { prefs_.remove("sofs"); spo2Trim_ = 0.0f; spo2Stored_ = false; }

private:
  Preferences prefs_;
  // Plain aligned floats: written from loop() (core 1), read by
  // sensorTask (core 1) — 32-bit reads/writes are atomic on ESP32.
  float tempOffset_ = TEMP_SITE_OFFSET_C;
  float spo2Trim_   = 0.0f;
  bool  tempStored_ = false;
  bool  spo2Stored_ = false;
};

VitalCal g_cal;
