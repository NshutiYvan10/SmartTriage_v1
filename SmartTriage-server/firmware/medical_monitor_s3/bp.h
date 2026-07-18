/*
 * bp.h — oscillometric blood-pressure module.
 *
 * SAFETY CONTRACT (the previous firmware had NONE of this — it ran the
 * pump open-loop for 3 seconds and, in real mode, produced no result):
 *   - Inflation is PRESSURE-FEEDBACK controlled: stop at BP_TARGET_INFLATE_MMHG.
 *   - BP_HARD_ABORT_MMHG or any timeout/stall/sensor-fault → immediate
 *     abort; EVERY exit path runs finishSafe() = motor stop + full deflate.
 *   - The whole cycle is bounded by BP_MEASURE_TIMEOUT_MS.
 *   - A running cycle is always cancellable: on-screen button (sim) and a
 *     press-and-hold read straight from the touch chip (real cycle).
 *
 * MEASUREMENT (fixed-ratio oscillometric, the industry-standard method):
 *   controlled deflation ~3 mmHg/s while sampling cuff pressure at the
 *   ADC's 40 SPS; oscillation = pressure − slow baseline; its smoothed
 *   envelope peaks at MAP; systolic/diastolic are where the envelope
 *   crosses 55%/75% of the peak on the high-/low-pressure side.
 *
 * BUS OWNERSHIP (v3.2.0 — the hard-won part):
 *   The pressure ADC shares its clock with the display/touch SPI and has
 *   NO chip-select (see cuffadc.h). Interleaving pressure reads with UI
 *   drawing desynced the ADC (garbage readings) and froze the UI twice on
 *   real hardware. So a real measurement takes g_spiBusMutex ONCE and
 *   owns the whole shared bus for the duration: the UI freezes on a
 *   pre-drawn "display paused" screen and resumes when the cuff releases.
 *   CANCEL during that window is a press-and-hold, polled by THIS task
 *   via a bit-banged touch read (each poll desyncs the ADC; we resync).
 *
 * CALIBRATION: the zero point auto-zeroes at boot (cuff open to air).
 * The SCALE (BP_COUNTS_PER_MMHG) must be validated against a reference
 * sphygmomanometer — until then results carry bpCalibrated=false and the
 * UI shows UNCALIBRATED.
 */
#pragma once
#include <Arduino.h>
#include <SPI.h>
#include "config.h"
#include "state.h"
#include "cuffadc.h"

class BpModule {
public:
  void begin() {
    pinMode(PIN_PRES_CS, OUTPUT);
    pinMode(PIN_PRES_MISO, INPUT);
    digitalWrite(PIN_PRES_CS, HIGH);
    // PIN_PRES_SCK deliberately NOT configured here: it belongs to the
    // display's SPI peripheral; CuffAdcPinGuard borrows and returns it.

    pinMode(PIN_MOTOR_IN1, OUTPUT);
    pinMode(PIN_MOTOR_IN2, OUTPUT);
    ledcAttach(PIN_MOTOR_ENA, MOTOR_PWM_FREQ, MOTOR_PWM_RES);
    motorStop();

    zeroCalibrate();
  }

  // The bpTask body: waits for a request, runs one full cycle.
  void taskLoop() {
    for (;;) {
      bool requested = false;
      if (stateLock()) {
        requested = g_state.bpRequested;
        g_state.bpRequested = false;
        stateUnlock();
      }
      if (!requested) { vTaskDelay(pdMS_TO_TICKS(150)); continue; }
      Serial.println("[bp] START requested");

      bool sim = false;
      if (stateLock()) { sim = g_state.simulation; stateUnlock(); }
      if (sim) runSimulatedCycle();
      else     runRealCycle();
    }
  }

private:
  // ================= pressure conversion =================
  int32_t zeroRaw_ = 0;
  bool sensorPresent_ = false;
  float lastPressure_ = 0;

  float countsToMmHg(int32_t raw) {
    return (float)((double)(raw - zeroRaw_) / BP_COUNTS_PER_MMHG);
  }

  // One fresh sample. Caller owns the bus + pin guard. false = not ready
  // within capMs (sensor silent or conversion still running).
  bool readSample(float &mmHg, uint32_t capMs) {
    if (!cuffAdcWaitReady(capMs)) return false;
    mmHg = countsToMmHg(cuffAdcClockOut24());
    lastPressure_ = mmHg;
    return true;
  }

  void zeroCalibrate() {
    // Runs at boot BEFORE the UI task exists (deliberate .ino ordering),
    // so the bus is quiet; the mutex take is form, not necessity.
    xSemaphoreTake(g_spiBusMutex, portMAX_DELAY);
    int got = 0;
    int64_t sum = 0;
    {
      CuffAdcPinGuard guard;
      if (cuffAdcSyncSettle()) {                   // reset + discard 2 (framing slip)
        for (int i = 0; i < 12; i++) {
          if (!cuffAdcWaitReady(150)) continue;
          sum += cuffAdcClockOut24();
          got++;
        }
      }
    }
    xSemaphoreGive(g_spiBusMutex);

    sensorPresent_ = got >= 6;
    if (sensorPresent_) zeroRaw_ = (int32_t)(sum / got);
    Serial.printf("[bp] zero-cal: %s (samples %d, zero raw %ld)\n",
                  sensorPresent_ ? "ok - pressure ADC answering"
                                 : "FAULT - pressure ADC not responding (DOUT never ready)",
                  got, (long)zeroRaw_);
    if (stateLock()) {
      g_state.chBp = sensorPresent_ ? Chan::OK : Chan::FAULT;
      stateUnlock();
    }
  }

  // ================= motor =================
  // HARDWARE TRUTH (measured live, v3.2.0 log): driving the H-bridge in
  // "reverse" ALSO pumps air IN — pressure rose 312→366 mmHg during what
  // the code believed was active deflation, while the user watched the
  // cuff fill. There is NO powered deflate on this box: it vents
  // PASSIVELY through its bleed valve whenever the motor is off (the
  // cuff emptied as soon as the motor stopped). So: deflation == stop.
  void motorInflate() { digitalWrite(PIN_MOTOR_IN1, HIGH); digitalWrite(PIN_MOTOR_IN2, LOW);  ledcWrite(PIN_MOTOR_ENA, BP_INFLATE_PWM); }
  void motorStop()    { digitalWrite(PIN_MOTOR_IN1, LOW);  digitalWrite(PIN_MOTOR_IN2, LOW);  ledcWrite(PIN_MOTOR_ENA, 0); }

  void setPhase(BpPhase p, uint8_t progress, const char *err = nullptr) {
    // Serial trail of the measurement cycle — "the button does nothing"
    // and "the cycle failed at step X" look identical on screen from a
    // distance; the log tells them apart.
    if (p != lastLoggedPhase_) {
      lastLoggedPhase_ = p;
      Serial.printf("[bp] phase=%d%s%s (cuff %.1f mmHg)\n", (int)p,
                    err ? " err=" : "", err ? err : "", lastPressure_);
    }
    if (!stateLock(50)) return;
    g_state.bpPhase = p;
    g_state.bpProgress = progress;
    if (err) strlcpy(g_state.bpError, err, sizeof(g_state.bpError));
    else if (p != BpPhase::ERROR) g_state.bpError[0] = '\0';
    stateUnlock();
  }
  BpPhase lastLoggedPhase_ = BpPhase::IDLE;

  void publishPressure(float mmHg) {
    if (stateLock(5)) { g_state.cuffPressure = mmHg; stateUnlock(); }
  }

  // Reads-and-clears the UI's cancel request (sim cycle / pre-ownership).
  bool cancelRequested() {
    bool c = false;
    if (stateLock(10)) {
      c = g_state.bpCancelRequested;
      g_state.bpCancelRequested = false;
      stateUnlock();
    }
    return c;
  }

  // ================= in-cycle touch CANCEL (bit-banged) =================
  // While the cycle owns the bus the UI cannot poll touch, so we ask the
  // touch chip directly: clock it by hand on the shared pins and read the
  // pressure electrodes. Any firm press counts as CANCEL — a patient in
  // distress should not have to find a button. Each poll feeds foreign
  // clocks to the cuff ADC; the caller must cuffAdcResetSync() after.
  uint16_t xptTransfer(uint8_t cmd) {
    uint16_t v = 0;
    for (int i = 7; i >= 0; i--) {
      digitalWrite(SHARED_PIN_MOSI, (cmd >> i) & 1);
      digitalWrite(PIN_PRES_SCK, HIGH); delayMicroseconds(2);
      digitalWrite(PIN_PRES_SCK, LOW);  delayMicroseconds(2);
    }
    digitalWrite(SHARED_PIN_MOSI, LOW);
    for (int i = 0; i < 16; i++) {
      digitalWrite(PIN_PRES_SCK, HIGH); delayMicroseconds(2);
      v = (uint16_t)((v << 1) | (digitalRead(SHARED_PIN_MISO) ? 1 : 0));
      digitalWrite(PIN_PRES_SCK, LOW);  delayMicroseconds(2);
    }
    return (uint16_t)((v >> 4) & 0x0FFF);      // 12-bit result
  }

  bool touchCancelPoll() {
    // MOSI is already plain GPIO (the cycle ended the display SPI driver
    // and CuffAdcPinGuard configured the shared pins).
    digitalWrite(SHARED_PIN_TOUCH_CS, LOW);
    uint16_t z1 = xptTransfer(0xB1);           // pressure electrode 1
    uint16_t z2 = xptTransfer(0xC1);           // pressure electrode 2
    xptTransfer(0xD0);                         // power down between polls
    digitalWrite(SHARED_PIN_TOUCH_CS, HIGH);
    int z = (int)z1 + 4095 - (int)z2;
    return z > 900;                            // firm press anywhere
  }

  // ================= the real measurement (bus OWNED throughout) ========
  void runRealCycle() {
    digitalWrite(PIN_LED_BP, HIGH);
    cancelRequested();                                      // clear stale
    lastPressure_ = 0;

    if (!sensorPresent_) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not detected");
      digitalWrite(PIN_LED_BP, LOW);
      return;
    }

    // Let the UI paint the "display paused" measuring screen, then take
    // the whole shared bus for the duration (see file header).
    setPhase(BpPhase::ZEROING, 2);
    vTaskDelay(pdMS_TO_TICKS(450));
    if (xSemaphoreTake(g_spiBusMutex, pdMS_TO_TICKS(3000)) != pdTRUE) {
      setPhase(BpPhase::ERROR, 0, "Screen busy - try again");
      digitalWrite(PIN_LED_BP, LOW);
      return;
    }
    // Shut the display SPI driver down cleanly before bit-banging its
    // pins, and re-begin it afterwards. The v3.2.0 register-level pin
    // juggling left the SPI peripheral wedged: the FIRST display/touch
    // operation after the cycle spun forever (UI freeze, watchdog reboot
    // — observed live). end()/begin() is the driver-sanctioned hand-off.
    if (g_tftSpi) g_tftSpi->end();
    {
      CuffAdcPinGuard guard;
      runCycleOwned();
    }
    if (g_tftSpi) g_tftSpi->begin(PIN_PRES_SCK, SHARED_PIN_MISO, SHARED_PIN_MOSI, -1);
    xSemaphoreGive(g_spiBusMutex);
    digitalWrite(PIN_LED_BP, LOW);
  }

  void runCycleOwned() {
    uint32_t cycleStart = millis();

    // ---- Phase 0: sync + settle + sanity ----
    // Settle discards the first two conversions: the first-after-reset
    // sample framing-slipped live (an exactly-doubled reading, 311.6
    // with an empty cuff) and failed the sanity gate for nothing.
    if (!cuffAdcSyncSettle()) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
      finishSafe();
      return;
    }
    float p0;
    if (!readSample(p0, 700)) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
      finishSafe();
      return;
    }
    Serial.printf("[bp] sanity: cuff reads %.1f mmHg (zero raw %ld)\n", p0, (long)zeroRaw_);
    if (p0 > 30.0f || p0 < -30.0f) {
      char msg[40];
      snprintf(msg, sizeof(msg), "Cuff not empty? reads %.0f mmHg", p0);
      setPhase(BpPhase::ERROR, 0, msg);
      finishSafe();
      return;
    }

    // ---- Phase 1: pressure-feedback inflation ----
    setPhase(BpPhase::INFLATING, 5);
    motorInflate();
    uint32_t inflateStart = millis();
    float p = p0, lastP = p0;
    uint32_t lastRiseCheck = millis(), lastCancelPoll = millis();
    int misses = 0;

    float prevP = p0;
    for (;;) {
      if (!readSample(p, 120)) {
        if (++misses >= 10) { setPhase(BpPhase::ERROR, 0, "Pressure sensor stopped"); finishSafe(); return; }
        continue;
      }
      // bit-slip guard: a physically impossible jump between consecutive
      // samples (25 ms apart) is a framing error, not pressure — resync.
      if (fabsf(p - prevP) > 250.0f) {
        cuffAdcSyncSettle();
        if (++misses >= 10) { setPhase(BpPhase::ERROR, 0, "Pressure sensor unstable"); finishSafe(); return; }
        continue;
      }
      prevP = p;
      misses = 0;
      publishPressure(p);

      if (p >= BP_HARD_ABORT_MMHG) {                       // hard safety
        setPhase(BpPhase::ERROR, 0, "Overpressure — aborted");
        finishSafe(); return;
      }
      if (p >= BP_TARGET_INFLATE_MMHG) break;               // target reached
      if (millis() - inflateStart > BP_INFLATE_TIMEOUT_MS) {
        setPhase(BpPhase::ERROR, 0, "Inflation timeout — check cuff");
        finishSafe(); return;
      }
      if (millis() - lastRiseCheck > 3000) {                // stall detection
        if (p - lastP < 3.0f) {
          setPhase(BpPhase::ERROR, 0, "Cuff not inflating — check hose");
          finishSafe(); return;
        }
        lastP = p; lastRiseCheck = millis();
      }
      if (millis() - lastCancelPoll > 1200) {               // press-and-hold cancel
        lastCancelPoll = millis();
        bool cancel = touchCancelPoll() || cancelRequested();
        cuffAdcSyncSettle();
        if (cancel) {
          setPhase(BpPhase::ERROR, 0, "Cancelled — cuff deflated");
          finishSafe(); return;
        }
      }
    }
    motorStop();                                            // vent opens: passive deflation begins
    vTaskDelay(pdMS_TO_TICKS(300));                         // let pressure settle

    // ---- Phase 2: PASSIVE deflation + oscillation capture ----
    // The box vents through its bleed valve with the motor off (measured
    // live — there is no powered deflate; "reverse" pumps air IN). We
    // ride the natural bleed rate and merely police it: too slow means a
    // blocked vent, too fast means too few pulses to analyse.
    setPhase(BpPhase::MEASURING, 40);
    const int MAX_POINTS = 512;
    static float envP[MAX_POINTS];                          // pressure at sample
    static float envA[MAX_POINTS];                          // envelope amplitude
    int points = 0;

    if (!cuffAdcSyncSettle()) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor stopped");
      finishSafe(); return;
    }
    float baseline;
    if (!readSample(baseline, 700)) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor stopped");
      finishSafe(); return;
    }
    float envelope = 0;

    float startP = baseline;
    uint32_t lastRateCheck = millis();
    float rateRefP = baseline;
    float bleedRate = 0;                                    // mmHg/s, measured
    uint32_t lastRecord = 0;
    lastCancelPoll = millis();
    misses = 0;
    prevP = baseline;

    for (;;) {
      if (!readSample(p, 120)) {
        if (++misses >= 10) { setPhase(BpPhase::ERROR, 0, "Pressure sensor stopped"); finishSafe(); return; }
        continue;
      }
      if (fabsf(p - prevP) > 250.0f) {                      // bit-slip guard
        cuffAdcSyncSettle();
        if (++misses >= 10) { setPhase(BpPhase::ERROR, 0, "Pressure sensor unstable"); finishSafe(); return; }
        continue;
      }
      prevP = p;
      misses = 0;
      publishPressure(p);

      if (millis() - cycleStart > BP_MEASURE_TIMEOUT_MS) {
        setPhase(BpPhase::ERROR, 0, "Measurement timeout");
        finishSafe(); return;
      }
      if (p >= BP_HARD_ABORT_MMHG) {
        setPhase(BpPhase::ERROR, 0, "Overpressure — aborted");
        finishSafe(); return;
      }
      if (millis() - lastCancelPoll > 1200) {
        lastCancelPoll = millis();
        bool cancel = touchCancelPoll() || cancelRequested();
        cuffAdcSyncSettle();
        if (cancel) {
          setPhase(BpPhase::ERROR, 0, "Cancelled — cuff deflated");
          finishSafe(); return;
        }
        prevP = p;                                          // resync consumed the slot
        continue;
      }

      // slow baseline tracks the deflation ramp; the residual is the
      // arterial oscillation; its rectified EMA is the envelope.
      baseline += 0.05f * (p - baseline);
      float osc = p - baseline;
      envelope += 0.08f * (fabsf(osc) - envelope);

      // record (pressure, envelope) every 100 ms
      if (millis() - lastRecord >= 100 && points < MAX_POINTS) {
        envP[points] = baseline;
        envA[points] = envelope;
        points++;
        lastRecord = millis();
      }

      uint8_t prog = (uint8_t)(40 + 55.0f * constrain(
          (startP - p) / max(startP - BP_DEFLATE_FLOOR_MMHG, 1.0f), 0.0f, 1.0f));
      setPhase(BpPhase::MEASURING, prog);

      // police the natural bleed rate (no actuator to adjust — motor off)
      if (millis() - lastRateCheck >= 1000) {
        bleedRate = rateRefP - p;                           // mmHg over the last second
        if (bleedRate < 0.3f) {
          setPhase(BpPhase::ERROR, 0, "Cuff not venting — check valve");
          finishSafe(); return;
        }
        rateRefP = p; lastRateCheck = millis();
      }

      if (p <= BP_DEFLATE_FLOOR_MMHG) break;                // capture complete
    }
    motorStop();
    Serial.printf("[bp] capture done: %d envelope points, last bleed rate %.1f mmHg/s\n",
                  points, bleedRate);
    if (bleedRate > 12.0f) {
      // vented so fast the envelope can't contain enough pulses — say so
      // rather than emit a junk number (tighten the bleed screw if present)
      setPhase(BpPhase::ERROR, 0, "Deflated too fast — tighten valve, retry");
      finishSafe(); return;
    }

    // ---- Phase 3: oscillometric identification ----
    setPhase(BpPhase::COMPUTING, 96);
    int sys, dia, map;
    bool ok = computeOscillometric(envP, envA, points, sys, dia, map);
    finishSafe();

    if (!ok) {
      setPhase(BpPhase::ERROR, 0, "No pulse signal — reposition cuff, retry");
      return;
    }

    // ---- Phase 4: publish ----
    if (stateLock(100)) {
      g_state.bpLast.sys = sys; g_state.bpLast.dia = dia; g_state.bpLast.map = map;
      g_state.bpLast.at = g_state.clockSynced ? time(nullptr) : 0;
      g_state.bpLast.valid = true;
      // history ring (newest first)
      for (int i = BP_HISTORY_SIZE - 1; i > 0; i--) g_state.bpHistory[i] = g_state.bpHistory[i - 1];
      g_state.bpHistory[0] = g_state.bpLast;
      if (g_state.bpHistoryCount < BP_HISTORY_SIZE) g_state.bpHistoryCount++;
      g_state.bpPhase = BpPhase::DONE;
      g_state.bpProgress = 100;
      stateUnlock();
    }
    Serial.printf("[bp] RESULT %d/%d (MAP %d) from %d envelope points\n", sys, dia, map, points);
  }

  // EVERY cycle exit funnels through here. The box vents PASSIVELY when
  // the motor is off (measured live — the "reverse" H-bridge direction
  // pumps air IN; v3.2.0's active deflate was re-inflating the cuff!),
  // so safety here means: motor OFF, then simply watch the pressure
  // fall until the cuff is empty (or a hard time cap). Never drive the
  // motor from this function. Caller still owns the bus + pin guard.
  void finishSafe() {
    motorStop();
    cuffAdcSyncSettle();
    float p = 0;
    uint32_t start = millis();
    while (millis() - start < 20000) {
      if (!readSample(p, 200)) { vTaskDelay(pdMS_TO_TICKS(50)); continue; }
      publishPressure(p);
      if (p < 8.0f) break;
      vTaskDelay(pdMS_TO_TICKS(100));
    }
    Serial.printf("[bp] finishSafe done (cuff %.1f mmHg, motor off, passive vent)\n",
                  lastPressure_);
    digitalWrite(PIN_LED_BP, LOW);
  }

  bool computeOscillometric(const float *envP, const float *envA, int n,
                            int &sys, int &dia, int &map) {
    if (n < 20) return false;

    // Peak envelope, ignoring edge transients: search the middle band only.
    int peakIdx = -1; float peakAmp = 0;
    for (int i = 2; i < n - 2; i++) {
      if (envP[i] < BP_DEFLATE_FLOOR_MMHG + 10 || envP[i] > BP_TARGET_INFLATE_MMHG - 10) continue;
      if (envA[i] > peakAmp) { peakAmp = envA[i]; peakIdx = i; }
    }
    if (peakIdx < 0 || peakAmp < 0.15f) return false;       // no arterial signal

    map = (int)roundf(envP[peakIdx]);

    // Systolic: walking toward HIGHER pressure (earlier samples — deflation
    // records high→low), the first crossing below SYS_RATIO × peak.
    sys = 0;
    for (int i = peakIdx; i >= 0; i--) {
      if (envA[i] <= peakAmp * BP_SYS_RATIO) { sys = (int)roundf(envP[i]); break; }
    }
    // Diastolic: walking toward LOWER pressure.
    dia = 0;
    for (int i = peakIdx; i < n; i++) {
      if (envA[i] <= peakAmp * BP_DIA_RATIO) { dia = (int)roundf(envP[i]); break; }
    }

    // Plausibility validation — reject rather than report nonsense.
    if (sys < BP_SYS_MIN || sys > BP_SYS_MAX) return false;
    if (dia < BP_DIA_MIN || dia > BP_DIA_MAX) return false;
    if (sys - dia < 15) return false;
    if (map <= dia || map >= sys) map = (int)roundf(dia + (sys - dia) / 3.0f);
    return true;
  }

  // ================= simulation cycle (demo only, never transmitted) ====
  void runSimulatedCycle() {
    digitalWrite(PIN_LED_BP, HIGH);
    cancelRequested();                                      // clear any stale request
    setPhase(BpPhase::INFLATING, 5);
    for (int p = 0; p <= 180; p += 6) {
      if (cancelRequested()) {
        publishPressure(0);
        setPhase(BpPhase::ERROR, 0, "Cancelled");
        digitalWrite(PIN_LED_BP, LOW);
        return;
      }
      publishPressure(p);
      setPhase(BpPhase::INFLATING, 5 + (uint8_t)(35.0f * p / 180));
      vTaskDelay(pdMS_TO_TICKS(100));
    }
    setPhase(BpPhase::MEASURING, 40);
    for (int p = 180; p >= 35; p -= 3) {
      if (cancelRequested()) {
        publishPressure(0);
        setPhase(BpPhase::ERROR, 0, "Cancelled");
        digitalWrite(PIN_LED_BP, LOW);
        return;
      }
      publishPressure(p);
      setPhase(BpPhase::MEASURING, 40 + (uint8_t)(55.0f * (180 - p) / 145));
      vTaskDelay(pdMS_TO_TICKS(120));
    }
    publishPressure(0);
    int sys = 112 + random(-6, 10), dia = 74 + random(-6, 8);
    if (stateLock(100)) {
      g_state.bpLast = { sys, dia, (int)roundf(dia + (sys - dia) / 3.0f),
                         g_state.clockSynced ? time(nullptr) : 0, true };
      for (int i = BP_HISTORY_SIZE - 1; i > 0; i--) g_state.bpHistory[i] = g_state.bpHistory[i - 1];
      g_state.bpHistory[0] = g_state.bpLast;
      if (g_state.bpHistoryCount < BP_HISTORY_SIZE) g_state.bpHistoryCount++;
      stateUnlock();
    }
    // Route DONE through setPhase so the serial trail shows the cycle
    // completing — the sim path previously set DONE silently, which read
    // as "stuck in MEASURING" in a captured log.
    setPhase(BpPhase::DONE, 100);
    Serial.printf("[bp] SIM result %d/%d (demo numbers - never transmitted)\n", sys, dia);
    digitalWrite(PIN_LED_BP, LOW);
  }
};
