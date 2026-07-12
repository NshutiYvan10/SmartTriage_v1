/*
 * alarms.h — on-device alarm manager.
 *
 * Conditions (ratified spec): SpO2<90, HR<40 or >150, Temp>39.5 or <35.5,
 * SBP>180 or <80 (latest reading), ECG leads off, backend lost >60 s.
 *
 * Behaviour modelled on real monitors:
 *   - the visual banner CANNOT be dismissed while a condition persists;
 *   - touch-silence quiets the buzzer for ALARM_SILENCE_MS, banner stays;
 *   - unknown values (0) never alarm — absence of data is a channel-status
 *     problem (leads-off has its own alarm), not a fake bradycardia.
 */
#pragma once
#include <Arduino.h>
#include "config.h"
#include "state.h"

class AlarmManager {
public:
  void begin() {
    pinMode(PIN_LED_NORMAL, OUTPUT);
    pinMode(PIN_LED_WARNING, OUTPUT);
    pinMode(PIN_LED_CRITICAL, OUTPUT);
    pinMode(PIN_LED_HEART, OUTPUT);
    pinMode(PIN_BUZZER, OUTPUT);
  }

  // Call ~every 250 ms from the sensor task.
  void poll() {
    uint32_t now = millis();
    if (now - lastEvalMs_ < 250) return;
    lastEvalMs_ = now;

    MonitorState s = snapshotState();
    AlarmFlags a;

    if (s.spo2 > 0)  a.spo2Low  = s.spo2 < ALM_SPO2_CRIT;
    if (s.hr > 0)   { a.hrLow    = s.hr < ALM_HR_CRIT_LOW;
                      a.hrHigh   = s.hr > ALM_HR_CRIT_HIGH; }
    if (s.temp > 0) { a.tempHigh = s.temp > ALM_TEMP_CRIT_HIGH;
                      a.tempLow  = s.temp < ALM_TEMP_CRIT_LOW; }
    if (s.bpLast.valid) {
      a.bpSysHigh = s.bpLast.sys > ALM_SYS_CRIT_HIGH;
      a.bpSysLow  = s.bpLast.sys < ALM_SYS_CRIT_LOW;
    }
    // Leads-off only alarms once a patient is plausibly connected —
    // an idle monitor on a shelf shouldn't scream. "Connected" = any
    // other channel is producing data.
    bool patientPresent = s.spo2 > 0 || s.temp > 0 || s.hr > 0;
    a.ecgLeadsOff = !s.simulation && patientPresent && s.chEcg == Chan::NO_CONTACT;

    // Backend lost: only after we've been reachable once (or 90 s grace
    // from boot), never in simulation (transmission is deliberately off).
    if (!s.simulation) {
      bool everAcked = s.lastAckMillis > 0;
      uint32_t since = everAcked ? now - s.lastAckMillis : now;
      a.backendLost = (everAcked || now > 90000) && since > BACKEND_LOST_ALARM_MS;
    }

    bool warning = isWarning(s);

    if (stateLock()) {
      g_state.alarms = a;
      stateUnlock();
    }

    driveIndicators(a, warning, s.alarmSilencedUntil, now);
  }

  // Warning (yellow) bands — display-level, no buzzer.
  static bool isWarning(const MonitorState &s) {
    if (s.spo2 > 0 && s.spo2 < ALM_SPO2_WARN) return true;
    if (s.hr > 0 && (s.hr < ALM_HR_WARN_LOW || s.hr > ALM_HR_WARN_HIGH)) return true;
    if (s.temp > 0 && (s.temp > ALM_TEMP_WARN_HIGH || s.temp < ALM_TEMP_WARN_LOW)) return true;
    if (s.rr > 0 && (s.rr < ALM_RR_WARN_LOW || s.rr > ALM_RR_WARN_HIGH)) return true;
    return false;
  }

  // Build "SPO2 LOW · HR HIGH · LEADS OFF" for the banner / status page.
  static void describe(const AlarmFlags &a, char *out, size_t cap) {
    out[0] = '\0';
    auto add = [&](const char *label) {
      if (out[0]) strlcat(out, " · ", cap);
      strlcat(out, label, cap);
    };
    if (a.spo2Low)     add("SpO2 LOW");
    if (a.hrLow)       add("HR LOW");
    if (a.hrHigh)      add("HR HIGH");
    if (a.tempHigh)    add("TEMP HIGH");
    if (a.tempLow)     add("TEMP LOW");
    if (a.bpSysHigh)   add("BP HIGH");
    if (a.bpSysLow)    add("BP LOW");
    if (a.ecgLeadsOff) add("ECG LEADS OFF");
    if (a.backendLost) add("SERVER LINK LOST");
  }

private:
  void driveIndicators(const AlarmFlags &a, bool warning,
                       uint32_t silencedUntil, uint32_t now) {
    bool critical = a.any();
    digitalWrite(PIN_LED_CRITICAL, critical);
    digitalWrite(PIN_LED_WARNING, !critical && warning);
    digitalWrite(PIN_LED_NORMAL, !critical && !warning);

    // heartbeat blink — alive indicator
    if (now - lastBlinkMs_ > 600) { blink_ = !blink_; digitalWrite(PIN_LED_HEART, blink_); lastBlinkMs_ = now; }

    // buzzer: triple-beep burst every 2.5 s while critical and not silenced
    bool silenced = silencedUntil != 0 && now < silencedUntil;
    if (critical && !silenced && now - lastBeepMs_ > 2500) {
      lastBeepMs_ = now;
      tone(PIN_BUZZER, 1800, 120);
      beepStep_ = 2;                     // two follow-up beeps
      nextBeepAt_ = now + 220;
    }
    if (beepStep_ > 0 && now >= nextBeepAt_) {
      tone(PIN_BUZZER, 1800, 120);
      beepStep_--;
      nextBeepAt_ = now + 220;
    }
  }

  uint32_t lastEvalMs_ = 0, lastBeepMs_ = 0, lastBlinkMs_ = 0, nextBeepAt_ = 0;
  int  beepStep_ = 0;
  bool blink_ = false;
};
