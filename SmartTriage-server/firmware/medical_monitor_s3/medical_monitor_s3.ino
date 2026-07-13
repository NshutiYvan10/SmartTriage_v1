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
#include "config.h"
#include "state.h"
#include "filters.h"
#include "sensors.h"
#include "bp.h"
#include "alarms.h"
#include "net.h"
#include "ui.h"

// ---- shared-state definitions (declared extern in state.h) ----
MonitorState g_state;
SemaphoreHandle_t g_stateMutex;
SemaphoreHandle_t g_spiBusMutex;   // arbitrates the shared GPIO-12 clock (display ↔ cuff ADC)
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
    bool simulating;
    if (stateLock(5)) { simulating = g_state.simulation; stateUnlock(); }
    else { vTaskDelay(1); continue; }

    if (simulating) {
      sim.poll();
    } else {
      spo2.poll(ecg.leadsOff());
      ecg.poll(spo2.irFallbackBpm(), spo2.fingerOn());
      temp.poll();
    }
    alarms.poll();
    vTaskDelay(pdMS_TO_TICKS(SENSOR_TASK_TICK_MS));
  }
}

// Core 1 — blood pressure (its own task: the measurement is a long
// closed-loop cycle and must never be starved by sampling).
static void bpTask(void *) { bp.taskLoop(); }

// Core 0 — network. Blocking HTTP lives here, away from the samplers.
static void netTask(void *) { net.taskLoop(); }

// Core 0 — display + touch.
static void uiTask(void *) {
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
//  Setup / loop
// =====================================================================
void setup() {
  Serial.begin(115200);
  Serial.println("\n=== SmartTriage Medical Monitor v3.0 (ESP32-S3) ===");

  g_stateMutex = xSemaphoreCreateMutex();
  g_spiBusMutex = xSemaphoreCreateMutex();

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  Wire.setClock(100000);          // MAX30205 is SMBus-class: 100 kHz only
  delay(50);

  ui.begin();                     // screen up first — boot feedback
  alarms.begin();

  bool spo2Ok = spo2.begin();
  temp.begin();
  ecg.begin();
  bp.begin();                     // includes cuff-pressure zero calibration
  net.attachEcg(&ecg);

  Serial.printf("MAX30102: %s | MAX30205: %s\n",
                spo2Ok ? "ok" : "NOT FOUND",
                temp.present() ? "ok" : "NOT FOUND");
  if (stateLock()) {
    if (!spo2Ok) g_state.chSpo2 = Chan::ABSENT;
    if (!temp.present()) g_state.chTemp = Chan::ABSENT;
    stateUnlock();
  }

  // Samplers + BP on core 1; network + UI on core 0 (with WiFi).
  xTaskCreatePinnedToCore(sensorTask, "sensors", 8192,  nullptr, 4, nullptr, 1);
  xTaskCreatePinnedToCore(bpTask,     "bp",      6144,  nullptr, 5, nullptr, 1);
  xTaskCreatePinnedToCore(netTask,    "net",     8192,  nullptr, 3, nullptr, 0);
  xTaskCreatePinnedToCore(uiTask,     "ui",      12288, nullptr, 2, nullptr, 0);

  Serial.println("Tasks started.");
}

void loop() {
  trendTick();
  vTaskDelay(pdMS_TO_TICKS(250));
}
