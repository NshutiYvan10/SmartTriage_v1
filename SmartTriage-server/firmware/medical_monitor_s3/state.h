/*
 * state.h — the single shared model of the monitor.
 * Sensor tasks WRITE under the mutex; UI and network tasks take cheap
 * snapshot copies. Nothing else may share data between tasks.
 */
#pragma once
#include <Arduino.h>
#include <time.h>
#include "config.h"

// Per-channel signal status — drives the dashboard's per-sensor chips.
enum class Chan : uint8_t { ABSENT, NO_CONTACT, OK, FAULT };

// Blood-pressure measurement lifecycle.
enum class BpPhase : uint8_t { IDLE, ZEROING, INFLATING, MEASURING, COMPUTING, DONE, ERROR };

struct BpReading {
  int    sys = 0, dia = 0, map = 0;
  time_t at  = 0;          // UTC epoch (0 = not clock-synced yet)
  bool   valid = false;
};

// One latching alarm slot. `active` reflects the live condition;
// `silencedUntil` implements the touch-silence.
struct AlarmFlags {
  bool spo2Low     = false;
  bool hrLow       = false;
  bool hrHigh      = false;
  bool tempHigh    = false;
  bool tempLow     = false;
  bool bpSysHigh   = false;
  bool bpSysLow    = false;
  bool ecgLeadsOff = false;
  bool backendLost = false;
  bool any() const {
    return spo2Low || hrLow || hrHigh || tempHigh || tempLow
        || bpSysHigh || bpSysLow || ecgLeadsOff || backendLost;
  }
};

struct MonitorState {
  // ---- smoothed, plausibility-gated vitals (0 = not available) ----
  float hr = 0, spo2 = 0, temp = 0, rr = 0;
  float perfusionIndex = 0;         // SpO2 signal-quality metric
  bool  hrFromEcg = false;          // false → IR fallback (leads off)

  // ---- per-channel status ----
  Chan chSpo2 = Chan::ABSENT;
  Chan chTemp = Chan::ABSENT;
  Chan chEcg  = Chan::ABSENT;
  Chan chBp   = Chan::ABSENT;       // FAULT when pressure sensor misbehaves

  // ---- blood pressure ----
  BpPhase  bpPhase = BpPhase::IDLE;
  float    cuffPressure = 0;        // live, for the BP page progress
  uint8_t  bpProgress = 0;          // 0-100 for the progress bar
  BpReading bpLast;                 // persists on screen + in payloads
  BpReading bpHistory[BP_HISTORY_SIZE];
  uint8_t  bpHistoryCount = 0;
  char     bpError[40] = "";
  bool     bpRequested = false;     // UI → bpTask trigger
  bool     bpCalibrated = false;    // scale validated against a reference?

  // ---- alarms ----
  AlarmFlags alarms;
  uint32_t alarmSilencedUntil = 0;  // millis(); 0 = not silenced

  // ---- network ----
  bool     wifiUp = false;
  int      wifiRssi = 0;
  bool     backendUp = false;       // ACKed within BACKEND_LOST_ALARM_MS
  time_t   lastAckAt = 0;           // UTC epoch of last accepted ingest
  uint32_t lastAckMillis = 0;
  uint32_t txOk = 0, txFail = 0;
  uint16_t offlineBuffered = 0;
  bool     clockSynced = false;

  // ---- mode ----
  bool simulation = false;          // demo data; NEVER transmitted
};

extern MonitorState g_state;
extern SemaphoreHandle_t g_stateMutex;

// Shared-wire arbitration: GPIO 12 is both the display's SPI clock and
// the cuff-pressure ADC's bit-bang clock (fixed wiring). The UI holds
// this mutex for each frame's drawing/touch; the BP module holds it for
// each ~70 µs pressure read. Nothing else may touch the TFT bus.
extern SemaphoreHandle_t g_spiBusMutex;

inline bool stateLock(uint32_t ms = 20) {
  return xSemaphoreTake(g_stateMutex, pdMS_TO_TICKS(ms)) == pdTRUE;
}
inline void stateUnlock() { xSemaphoreGive(g_stateMutex); }

// Snapshot copy for readers (UI / net). Blocks briefly; writers hold the
// lock only for field assignments so this never stalls perceptibly.
inline MonitorState snapshotState() {
  MonitorState copy;
  if (stateLock(50)) { copy = g_state; stateUnlock(); }
  return copy;
}

// ---- trend rings (written by the trend collector, read by the UI) ----
struct TrendRing {
  float   v[TREND_POINTS] = {0};
  uint8_t idx = 0, count = 0;
  void push(float x) {
    v[idx] = x;
    idx = (idx + 1) % TREND_POINTS;
    if (count < TREND_POINTS) count++;
  }
};
extern TrendRing g_trendHr, g_trendSpo2, g_trendTemp, g_trendRr;

// ---- ECG waveform ring for the UI trace + payload export ----
// Written at 250 Hz by the ECG sampler (single writer), read by the UI.
// A seq counter lets the UI consume only new samples; benign races on
// individual int16 reads are acceptable for a display trace.
extern volatile int16_t  g_ecgWave[ECG_WAVE_RING];
extern volatile uint16_t g_ecgWaveHead;

// Pleth (IR) waveform ring, same pattern, filled at ~100 Hz.
extern volatile int16_t  g_plethWave[ECG_WAVE_RING];
extern volatile uint16_t g_plethWaveHead;
