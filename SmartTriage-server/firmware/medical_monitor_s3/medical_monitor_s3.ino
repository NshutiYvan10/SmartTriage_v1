/*
 * ============================================================
 *  SmartTriage Medical Monitor — ESP32-S3 firmware v3.0
 * ============================================================
 *  A ground-up rewrite of the placeholder monitor firmware:
 *
 *   - MAX30102 pulse-ox on the CORRECT library (the old build used the
 *     register-incompatible MAX30100 driver — HR/SpO2 were always 0),
 *     with R-ratio-median SpO2, perfusion gating, artifact rejection.
 *   - AD8232 ECG at 250 Hz: baseline-wander removal + 50 Hz notch,
 *     adaptive R-peak HR, ECG-derived respiration (RR was previously
 *     a fabricated constant), lead-off detection with IR-HR fallback.
 *   - MAX30205 contact temperature with validation + site offset.
 *   - REAL oscillometric blood pressure with a hard safety envelope
 *     (pressure-feedback inflation, 200 mmHg hard abort, deflate on
 *     every error path). The old build inflated open-loop for 3 s and
 *     hardcoded 118/78.
 *   - Five-page flicker-free touch UI: vitals dashboard, live ECG +
 *     pleth waveforms, 10-minute trends, guided BP measurement,
 *     device status. Swipe to navigate; alarm banner on every page.
 *   - Alarms per clinical spec (SpO2<90, HR<40/>150, T>39.5/<35.5,
 *     SBP>180/<80, leads-off, server-link-lost>60 s) with touch-silence.
 *   - Transmission to SmartTriage every 5 s on the real device API
 *     (X-Device-API-Key → /api/v1/iot/stream/ingest), one exported
 *     ECG beat per payload, offline ring buffer, NTP timestamps.
 *     The old build transmitted NOTHING (it served a local web page).
 *   - Simulation mode survives as a demo tool only: unmissable amber
 *     banner, and nothing is ever transmitted while simulating.
 *
 *  Board:      ESP32-S3 (Arduino-ESP32 core 3.x)
 *  Display:    SPI TFT via TFT_eSPI — configure User_Setup.h for your
 *              panel; layout auto-adapts to tft.width()/height().
 *  Libraries:  TFT_eSPI, SparkFun MAX3010x, ArduinoJson (v7)
 *
 *  Provisioning: see config.h (WiFi, server, device serial + API key).
 * ============================================================
 */
#include <Wire.h>
#include "esp_task_wdt.h"
#include "esp_log.h"
#include "config.h"
#include "state.h"
#include "filters.h"
#include "cal.h"
#include "sensors.h"
#include "bp.h"
#include "alarms.h"
#include "net.h"
#include "ui.h"

// ---- shared-state definitions (declared extern in state.h) ----
MonitorState g_state;
SemaphoreHandle_t g_stateMutex;
SemaphoreHandle_t g_spiBusMutex;   // arbitrates the shared GPIO-12 clock (display ↔ cuff ADC)
SPIClass *g_tftSpi = nullptr;      // display SPI driver; BP cycle end()s/begin()s it (state.h)
TrendRing g_trendHr, g_trendSpo2, g_trendTemp, g_trendRr;
volatile int16_t  g_ecgWave[ECG_WAVE_RING] = {0};
volatile uint16_t g_ecgWaveHead = 0;
volatile int16_t  g_plethWave[ECG_WAVE_RING] = {0};
volatile uint16_t g_plethWaveHead = 0;

// ---- modules ----
static Spo2Pipeline spo2;
static TempPipeline temp;
static EcgPipeline  ecg;
static SimSource    sim;
static BpModule     bp;
static AlarmManager alarms;
static NetLink      net;
static UiController ui;

// =====================================================================
//  Tasks
// =====================================================================

// Core 1 — samplers. Nothing on this core ever blocks on I/O other than
// the I2C/ADC reads themselves.
static void sensorTask(void *) {
  for (;;) {
    // One lock per iteration for BOTH flags: this loop runs at 500 Hz, and
    // taking the state mutex twice that often is the contention pattern that
    // visibly degraded the UI once already (see TempPipeline's publish note).
    bool simulating;
    uint8_t ledWant;
    if (stateLock(5)) {
      simulating = g_state.simulation;
      ledWant    = g_state.spo2LedLevel;
      stateUnlock();
    } else { vTaskDelay(1); continue; }

    if (simulating) {
      sim.poll();
    } else {
      spo2.applyLedLevel(ledWant);   // diagnostic; no-op at the clinical level
      spo2.poll(ecg.leadsOff());
      temp.poll();          // ECG now samples in its own task (see ecgTask)
    }
    alarms.poll();
    vTaskDelay(pdMS_TO_TICKS(SENSOR_TASK_TICK_MS));
  }
}

// Core 1 — ECG sampler, on its OWN tick-locked cadence.
//
// WHY THIS IS A SEPARATE TASK: the 50 Hz mains notch (filters.h) is
// configured for exactly 250 Hz, and bench-simulating the real filter shows
// it is brutally sensitive to a wrong MEAN sample interval — 4.03 ms instead
// of 4.000 collapses mains rejection from ~124 dB to ~16 dB, and 4.25 ms
// leaves ~2 dB, i.e. none at all. Random jitter is far more forgiving
// (±100 µs still yields ~25 dB). Sampling on a millis() gate inside the
// shared sampler loop made the MEAN interval a function of unrelated work
// (the pulse-ox I2C FIFO drain, the temperature read) — precisely the fatal
// mode, and precisely why a pulse-ox that is busier when a finger is present
// could degrade the ECG. vTaskDelayUntil locks the mean to the tick.
static void ecgTask(void *) {
  TickType_t last = xTaskGetTickCount();
  const TickType_t period = pdMS_TO_TICKS(ECG_SAMPLE_INTERVAL_MS);
  bool simulating = false;
  uint16_t simCheck = 0;
  for (;;) {
    // The simulation flag is re-read ~5x/s, not every sample: taking the
    // state mutex 250x/s from this task is exactly the kind of contention
    // that degrades the UI (see TempPipeline's 1 Hz-publish note).
    if (simCheck++ % 50 == 0) {
      if (stateLock(2)) { simulating = g_state.simulation; stateUnlock(); }
    }
    if (!simulating) ecg.poll(spo2.irFallbackBpm(), spo2.fingerOn());
    vTaskDelayUntil(&last, period);
  }
}

// Core 1 — blood pressure (its own task: the measurement is a long
// closed-loop cycle and must never be starved by sampling).
static void bpTask(void *) { bp.taskLoop(); }

// Core 0 — network. Blocking HTTP lives here, away from the samplers.
static void netTask(void *) { net.taskLoop(); }

// Core 0 — display + touch. Watchdog-covered: ui.frame() feeds it per
// attempt; a hard freeze inside a frame reboots the monitor in 20 s.
static void uiTask(void *) {
  esp_task_wdt_add(NULL);
  for (;;) {
    ui.frame();
    vTaskDelay(pdMS_TO_TICKS(UI_FRAME_MS));
  }
}

// Trend collector — one point per TREND_INTERVAL_MS.
static void trendTick() {
  static uint32_t last = 0;
  if (millis() - last < TREND_INTERVAL_MS) return;
  last = millis();
  MonitorState s = snapshotState();
  g_trendHr.push(s.hr);
  g_trendSpo2.push(s.spo2);
  g_trendTemp.push(s.temp);
  g_trendRr.push(s.rr);
}

// =====================================================================
//  Calibration console (cal.h) — serial-driven, used on the bench with
//  a reference instrument at hand. Non-"cal" input is ignored so a
//  stray paste into the serial monitor cannot change anything.
// =====================================================================
static void calConsolePoll() {
  static char buf[48];
  static uint8_t len = 0;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\r') continue;
    if (c != '\n') {
      if (len < sizeof(buf) - 1) buf[len++] = c;
      continue;
    }
    buf[len] = '\0';
    len = 0;

    if (strncmp(buf, "cal", 3) != 0) continue;   // not for us
    char arg1[16] = {0}, arg2[16] = {0};
    sscanf(buf, "cal %15s %15s", arg1, arg2);
    MonitorState s = snapshotState();

    if (strcmp(arg1, "show") == 0 || arg1[0] == '\0') {
      Serial.printf("[cal] temp: offset %+.2f C (%s) -> reading %s%.1f C\n",
                    g_cal.tempOffset(),
                    g_cal.tempCalibrated() ? "device-calibrated" : "default",
                    s.chTemp == Chan::OK ? "" : "n/a, raw ", s.temp);
      Serial.printf("[cal] spo2: trim %+.1f pt (%s) -> reading %s%.0f %%\n",
                    g_cal.spo2Trim(),
                    g_cal.spo2Calibrated() ? "device-calibrated" : "default",
                    s.chSpo2 == Chan::OK ? "" : "n/a, last ", s.spo2);
      Serial.println("[cal] usage: cal temp <ref C> | cal spo2 <ref %> | cal temp reset | cal spo2 reset | cal touch run | cal touch reset");
      continue;
    }

    // Touch calibration escape hatch — reachable even when the panel is
    // unresponsive (garbage stored cal), which is exactly when it's needed.
    if (strcmp(arg1, "touch") == 0) {
      if (strcmp(arg2, "run") == 0) {
        ui.requestTouchCalibration();
        Serial.println("[cal] touch calibration starting on the display — tap the corner arrows");
      } else if (strcmp(arg2, "reset") == 0) {
        ui.resetTouchCalibration();
      } else {
        Serial.println("[cal] usage: cal touch run | cal touch reset");
      }
      continue;
    }

    bool isTemp = strcmp(arg1, "temp") == 0;
    bool isSpo2 = strcmp(arg1, "spo2") == 0;
    if (!isTemp && !isSpo2) {
      Serial.printf("[cal] unknown target '%s' — cal show for usage\n", arg1);
      continue;
    }

    if (strcmp(arg2, "reset") == 0) {
      if (isTemp) { g_cal.resetTemp(); temp.resetSmoothing(); }
      else        { g_cal.resetSpo2(); spo2.resetSmoothing(); }
      Serial.printf("[cal] %s calibration cleared — back to default\n", arg1);
      continue;
    }

    float ref = atof(arg2);
    if (isTemp) {
      // Anchor on the CURRENT smoothed reading: the sensor must be attached
      // exactly as it will be used and the value plateaued, or the offset
      // bakes in a transient.
      if (s.chTemp != Chan::OK) {
        Serial.println("[cal] temp has no live reading — attach the sensor, wait for a value, retry");
        continue;
      }
      if (ref < 30.0f || ref > 43.0f) {
        Serial.printf("[cal] reference %.1f C outside 30-43 — typo?\n", ref);
        continue;
      }
      // Refuse to calibrate off a SITE ERROR. A core reference typed while the
      // probe reads ~31 C means the probe is on the wrist/forearm, not in the
      // axilla — storing that offset would then unlock transmission and alarms
      // on a 5-6 C lie. See cal.h.
      if (s.temp < TEMP_CAL_MIN_RAW) {
        Serial.printf("[cal] REFUSED: probe reads %.1f C, which is SKIN not core. A core "
                      "reference is only valid from a deep-axillary placement (expect "
                      ">= %.0f C before calibrating). Move the probe, wait for the "
                      "plateau, retry.\n", s.temp, TEMP_CAL_MIN_RAW);
        continue;
      }
      if (!temp.settled()) {
        Serial.println("[cal] REFUSED: temperature has not plateaued yet. The sensor needs "
                       "minutes, not seconds — hold placement until '[temp] plateau reached' "
                       "appears, then calibrate. Calibrating on the transient bakes in the "
                       "settling error and it will NOT transfer to the next person.");
        continue;
      }
      float newOfs = g_cal.tempOffset() + (ref - s.temp);
      if (!g_cal.setTempOffset(newOfs)) {
        Serial.printf("[cal] refused: offset %+.2f C exceeds +/-%.0f rail — that gap is a "
                      "coupling problem (loose strap / sensor in air), not calibration\n",
                      newOfs, CAL_TEMP_OFFSET_MAX);
        continue;
      }
      temp.resetSmoothing();
      Serial.printf("[cal] temp: reading %.1f C, reference %.1f C -> site offset %+.2f C stored "
                    "(was %+.2f). Re-check in ~1 min.\n",
                    s.temp, ref, newOfs, newOfs - (ref - s.temp));
    } else {
      if (s.chSpo2 != Chan::OK) {
        Serial.println("[cal] spo2 has no live reading — finger on sensor, wait for a value, retry");
        continue;
      }
      if (ref < 80.0f || ref > 100.0f) {
        Serial.printf("[cal] reference %.0f %% outside 80-100 — typo?\n", ref);
        continue;
      }
      float newTrim = g_cal.spo2Trim() + (ref - s.spo2);
      if (!g_cal.setSpo2Trim(newTrim)) {
        Serial.printf("[cal] refused: trim %+.1f pt exceeds +/-%.0f rail — a gap that large is "
                      "signal quality (perfusion / finger placement), not calibration\n",
                      newTrim, CAL_SPO2_TRIM_MAX);
        continue;
      }
      spo2.resetSmoothing();
      Serial.printf("[cal] spo2: reading %.0f %%, reference %.0f %% -> trim %+.1f pt stored "
                    "(was %+.1f). Re-check in ~1 min.\n",
                    s.spo2, ref, newTrim, newTrim - (ref - s.spo2));
    }
  }
}

// =====================================================================
//  Setup / loop
// =====================================================================
void setup() {
  Serial.begin(115200);
  // Give a just-(re)attached serial monitor a moment to connect — every
  // live debugging round so far lost the boot banner + health lines to a
  // late attach. (The 30 s [recap] line below is the belt to this brace.)
  delay(1200);
  Serial.println("\n=== SmartTriage Medical Monitor " FIRMWARE_VERSION " (ESP32-S3) ===");

  // Silence the IDF WiFi driver's association-retry chatter. It logs
  // "Set status to INIT" at ERROR level several times per SECOND while
  // associating — hundreds of lines that bury every diagnostic we
  // actually print (it made a captured field log unusable). Our own
  // wifi=up/down heartbeat + [net] lines report link state.
  esp_log_level_set("wifi", ESP_LOG_NONE);

  g_stateMutex = xSemaphoreCreateMutex();
  g_spiBusMutex = xSemaphoreCreateMutex();

  // Load device calibration (temp site offset + SpO2 trim) from NVS —
  // before the sampler tasks start so their first readings use it.
  g_cal.begin();

  // Core 0 runs the display task, whose long SPI bursts (a full-screen
  // fill on ILI9488 is ~0.4 s of continuous, non-yielding writes) can
  // legitimately hold the core past the 5 s idle-task watchdog.
  // NOTE: disableCore0WDT() is the classic remedy but on Arduino core 3.x
  // it leaves the idle task calling esp_task_wdt_reset() into a watchdog
  // it no longer belongs to — flooding serial with "task not found" ~200x
  // per second (observed live). Reconfiguring the watchdog to stop
  // watching idle tasks entirely is the clean 3.x approach.
  //
  // v3.2.0: the UI task SUBSCRIBES itself (uiTask → esp_task_wdt_add) and
  // feeds the watchdog once per frame attempt; trigger_panic reboots the
  // monitor if the UI ever freezes hard (observed twice on real hardware
  // before the shared-bus ownership fix). A 20 s self-recovery beats a
  // bricked bedside monitor. The touch-calibration screen unsubscribes
  // for its (user-paced) duration.
  {
    esp_task_wdt_config_t wdtCfg = {};
    wdtCfg.timeout_ms = 20000;
    wdtCfg.idle_core_mask = 0;      // watch no idle tasks
    wdtCfg.trigger_panic = true;    // frozen UI → reboot, not a brick
    esp_task_wdt_reconfigure(&wdtCfg);
  }

  // Bus electrical pre-check (v3.4.1): with only the ESP32's weak internal
  // pullups, a line held LOW means a short / mis-wire; both lines idling
  // HIGH but nobody ACKing means the sensor module itself has no power
  // (the usual suspect since the box's battery-supply rework) or its
  // wires don't reach these pins. Same pins the OLD working firmware
  // used (SDA 6 / SCL 7 — verified against the original sketch).
  pinMode(PIN_I2C_SDA, INPUT_PULLUP);
  pinMode(PIN_I2C_SCL, INPUT_PULLUP);
  delay(5);
  bool sdaIdleHigh = digitalRead(PIN_I2C_SDA) == HIGH;
  bool sclIdleHigh = digitalRead(PIN_I2C_SCL) == HIGH;

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  Wire.setClock(100000);          // MAX30205 is SMBus-class: 100 kHz only
  delay(50);

  // Boot I2C census — both clinical sensors have reported NOT FOUND on
  // this hardware, so print exactly who answers on the bus. Expected:
  // 0x57 (MAX30102) and 0x48 (MAX30205).
  {
    int found = 0;
    Serial.print("[i2c] scan:");
    for (uint8_t a = 1; a < 127; a++) {
      Wire.beginTransmission(a);
      if (Wire.endTransmission() == 0) { Serial.printf(" 0x%02X", a); found++; }
    }
    Serial.printf("%s (%d device%s)\n", found ? "" : " none", found, found == 1 ? "" : "s");
    if (found == 0) {
      Serial.printf("[i2c] diagnosis: SDA %s, SCL %s -> %s\n",
                    sdaIdleHigh ? "idles high" : "HELD LOW",
                    sclIdleHigh ? "idles high" : "HELD LOW",
                    (!sdaIdleHigh || !sclIdleHigh)
                        ? "a line is shorted or mis-wired"
                        : "wiring idle but no chip answers - check the sensor module's VCC/GND with a meter");
    }
  }

  // BP first, screen second — deliberately: the cuff-pressure zero
  // calibration borrows GPIO 12 (the display's future SPI clock) for its
  // bit-banged reads. Doing that BEFORE tft.init() means the display's
  // SPI bus is configured from scratch afterwards and can never inherit
  // a disturbed pin state from the calibration.
  bp.begin();                     // includes cuff-pressure zero calibration
  ui.begin();
  alarms.begin();

  bool spo2Ok = spo2.begin();
  temp.begin();
  ecg.begin();
  net.attachEcg(&ecg);

  Serial.printf("PULSE-OX: %s | MAX30205: %s\n",
                spo2Ok ? "ok" : "NOT FOUND",
                temp.present() ? "ok" : "NOT FOUND");
  if (stateLock()) {
    if (!spo2Ok) g_state.chSpo2 = Chan::ABSENT;
    if (!temp.present()) g_state.chTemp = Chan::ABSENT;
    stateUnlock();
  }

  // Samplers + BP on core 1; network + UI on core 0 (with WiFi).
  xTaskCreatePinnedToCore(sensorTask, "sensors", 8192,  nullptr, 4, nullptr, 1);
  // ECG above the other samplers: its whole value is a cadence nothing else
  // may shift. The work per tick is one analogRead plus float filtering.
  xTaskCreatePinnedToCore(ecgTask,    "ecg",     4096,  nullptr, 6, nullptr, 1);
  xTaskCreatePinnedToCore(bpTask,     "bp",      6144,  nullptr, 5, nullptr, 1);
  xTaskCreatePinnedToCore(netTask,    "net",     8192,  nullptr, 3, nullptr, 0);
  xTaskCreatePinnedToCore(uiTask,     "ui",      12288, nullptr, 2, nullptr, 0);

  Serial.println("Tasks started.");
}

void loop() {
  trendTick();
  calConsolePoll();

  // Sensor-health recap every 30 s: the boot banner keeps getting lost
  // to late-attaching serial monitors, so the verdict lines repeat.
  static uint32_t lastRecap = 0;
  if (millis() - lastRecap >= 30000) {
    lastRecap = millis();
    MonitorState s = snapshotState();
    auto chan = [](Chan c) {
      switch (c) {
        case Chan::OK: return "OK";
        case Chan::NO_CONTACT: return "off-patient";
        case Chan::FAULT: return "FAULT";
        default: return "absent";
      }
    };
    Serial.printf("[recap] fw=%s spo2:%s temp:%s ecg:%s cuff-adc:%s cal[t%+.1f s%+.1f]%s\n",
                  FIRMWARE_VERSION, chan(s.chSpo2), chan(s.chTemp), chan(s.chEcg), chan(s.chBp),
                  g_cal.tempOffset(), g_cal.spo2Trim(),
                  s.simulation ? " (SIMULATION)" : "");
  }

  // ECG timing + interference report. Prints only while the leads are on a
  // patient (it is meaningless otherwise). This is the line that settles
  // whether ECG noise is an electrical coupling problem or a sampling-rate
  // problem: compare it with a finger ON vs OFF the pulse-ox sensor, and
  // across the Device page's LED drive levels.
  static uint32_t lastEcgDiag = 0;
  if (millis() - lastEcgDiag >= ECG_DIAG_REPORT_MS) {
    lastEcgDiag = millis();
    char diag[192];
    if (ecg.diagLine(diag, sizeof(diag))) {
      MonitorState s = snapshotState();
      Serial.printf("%s | spo2-led=%s finger=%s\n", diag,
                    s.spo2LedLevel == 0 ? "OFF" : s.spo2LedLevel == 1 ? "HALF" : "full",
                    s.chSpo2 == Chan::OK ? "yes" : "no");
    }
  }

  // Serial heartbeat (runs on core 1, independent of the UI): if the UI
  // ever wedges, uptime keeps printing while frames stops rising — that
  // one line pinpoints a display/touch hang without a debugger.
  static uint32_t lastBeat = 0;
  if (millis() - lastBeat >= 5000) {
    lastBeat = millis();
    Serial.printf("[hb] up=%lus starts=%lu frames=%lu stage=%u heap=%u wifi=%s\n",
                  (unsigned long)(millis() / 1000),
                  (unsigned long)ui.frameStarts,
                  (unsigned long)ui.frameCount,
                  (unsigned)ui.stage,
                  (unsigned)ESP.getFreeHeap(),
                  WiFi.status() == WL_CONNECTED ? "up" : "down");
  }
  vTaskDelay(pdMS_TO_TICKS(250));
}
