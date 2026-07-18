/*
 * bp.h — oscillometric blood-pressure module.
 *
 * SAFETY CONTRACT (the previous firmware had NONE of this — it ran the
 * pump open-loop for 3 seconds and, in real mode, produced no result):
 *   - Inflation is PRESSURE-FEEDBACK controlled: stop at BP_TARGET_INFLATE_MMHG.
 *   - BP_HARD_ABORT_MMHG or any timeout/stall/sensor-fault → immediate
 *     abort; EVERY exit path runs finishSafe() = motor stop + full deflate.
 *   - The whole cycle is bounded by BP_MEASURE_TIMEOUT_MS.
 *
 * MEASUREMENT (fixed-ratio oscillometric, the industry-standard method):
 *   controlled deflation ~3 mmHg/s while sampling cuff pressure at 50 Hz;
 *   oscillation = pressure − slow baseline; its smoothed envelope peaks at
 *   MAP; systolic/diastolic are where the envelope crosses 55%/75% of the
 *   peak on the high-/low-pressure side.
 *
 * CALIBRATION: the pressure zero-point is auto-zeroed at boot (cuff open).
 * The SCALE (BP_PRES_SCALE) must be validated against a reference gauge —
 * until then results carry bpCalibrated=false and the UI shows UNCALIBRATED.
 */
#pragma once
#include <Arduino.h>
#include "soc/gpio_struct.h"   // GPIO output-matrix routing save/restore (shared clock pin)
#include "config.h"
#include "state.h"

class BpModule {
public:
  void begin() {
    pinMode(PIN_PRES_CS, OUTPUT);
    pinMode(PIN_PRES_MISO, INPUT);
    digitalWrite(PIN_PRES_CS, HIGH);
    // PIN_PRES_SCK deliberately NOT configured here: it belongs to the
    // display's SPI peripheral; readPressureMmHg() borrows and returns it.

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
  // ================= pressure sensor =================
  // GPIO 12 is SHARED with the display's SPI clock (fixed wiring — the
  // sensor is soldered in). Sharing a clock is legitimate SPI bus design:
  // the display ignores edges while TFT_CS is high. Two things make it
  // safe in software:
  //   1. g_spiBusMutex — the UI is never mid-draw while we clock the
  //      sensor (and we never clock while the UI owns the bus);
  //   2. the ESP32 routes pin 12 to the SPI peripheral through its GPIO
  //      output matrix, where digitalWrite has no effect — so we save
  //      the pin's matrix routing, take the pin as plain GPIO for the
  //      ~70 µs read, then restore the routing byte-for-byte. The SPI
  //      peripheral gets its clock pin back exactly as it left it.
  float readPressureMmHg() {
    if (xSemaphoreTake(g_spiBusMutex, pdMS_TO_TICKS(50)) != pdTRUE) {
      return lastPressure_;               // UI hogged the bus — keep last sample
    }
    uint32_t savedRouting = GPIO.func_out_sel_cfg[PIN_PRES_SCK].val;
    pinMode(PIN_PRES_SCK, OUTPUT);        // detach from SPI matrix → plain GPIO
    digitalWrite(PIN_PRES_SCK, LOW);

    uint16_t raw = 0;
    digitalWrite(PIN_PRES_CS, LOW);
    delayMicroseconds(10);
    for (int i = 15; i >= 0; i--) {
      digitalWrite(PIN_PRES_SCK, LOW);  delayMicroseconds(2);
      digitalWrite(PIN_PRES_SCK, HIGH); delayMicroseconds(2);
      if (digitalRead(PIN_PRES_MISO)) raw |= (1 << i);
    }
    digitalWrite(PIN_PRES_CS, HIGH);
    digitalWrite(PIN_PRES_SCK, LOW);      // leave the line idle-low (SPI mode 0)

    GPIO.func_out_sel_cfg[PIN_PRES_SCK].val = savedRouting;   // hand pin 12 back to SPI
    xSemaphoreGive(g_spiBusMutex);

    lastPressure_ = (raw / 16383.0f) * 300.0f * BP_PRES_SCALE - zeroOffset_;
    return lastPressure_;
  }

  void zeroCalibrate() {
    // Cuff open to air at boot → whatever we read IS zero.
    float sum = 0;
    for (int i = 0; i < 20; i++) { sum += readPressureMmHg() + zeroOffset_; delay(10); }
    zeroOffset_ = sum / 20.0f;
    bool fault = fabsf(zeroOffset_) > 150.0f;   // sensor missing/shorted
    // Raw ~0 or ~300 (all-zeros / all-ones on the data line) also means
    // nothing coherent is answering on the pressure ADC.
    Serial.printf("[bp] zero-cal: raw %.1f mmHg -> offset %.1f | %s\n",
                  zeroOffset_, zeroOffset_,
                  fault ? "FAULT (implausible - check pressure ADC wiring)" : "ok");
    if (stateLock()) {
      g_state.chBp = fault ? Chan::FAULT : Chan::OK;
      stateUnlock();
    }
  }

  // ================= motor =================
  void motorInflate() { digitalWrite(PIN_MOTOR_IN1, HIGH); digitalWrite(PIN_MOTOR_IN2, LOW);  ledcWrite(PIN_MOTOR_ENA, BP_INFLATE_PWM); }
  void motorDeflate(uint8_t pwm) { digitalWrite(PIN_MOTOR_IN1, LOW); digitalWrite(PIN_MOTOR_IN2, HIGH); ledcWrite(PIN_MOTOR_ENA, pwm); }
  void motorStop()    { digitalWrite(PIN_MOTOR_IN1, LOW);  digitalWrite(PIN_MOTOR_IN2, LOW);  ledcWrite(PIN_MOTOR_ENA, 0); }

  // EVERY cycle exit funnels through here: stop, then actively deflate
  // until the cuff is empty (or 15 s — then stop regardless).
  void finishSafe() {
    motorStop();
    uint32_t start = millis();
    motorDeflate(255);
    while (millis() - start < 15000) {
      if (readPressureMmHg() < 8.0f) break;
      vTaskDelay(pdMS_TO_TICKS(100));
    }
    motorStop();
    digitalWrite(PIN_LED_BP, LOW);
  }

  void setPhase(BpPhase p, uint8_t progress, const char *err = nullptr) {
    // Serial trail of the measurement cycle — "the button does nothing"
    // and "the cycle failed at step X" look identical on screen from a
    // distance; the log tells them apart.
    if (p != lastLoggedPhase_) {
      lastLoggedPhase_ = p;
      Serial.printf("[bp] phase=%d%s%s (cuff %.1f mmHg)\n", (int)p,
                    err ? " err=" : "", err ? err : "", g_state.cuffPressure);
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

  // ================= the real measurement =================
  void runRealCycle() {
    digitalWrite(PIN_LED_BP, HIGH);
    uint32_t cycleStart = millis();

    // ---- Phase 0: sanity ----
    setPhase(BpPhase::ZEROING, 2);
    float p0 = readPressureMmHg();
    if (p0 > 30.0f || p0 < -30.0f) {
      setPhase(BpPhase::ERROR, 0, "Cuff not empty / sensor fault");
      finishSafe();
      return;
    }

    // ---- Phase 1: pressure-feedback inflation ----
    setPhase(BpPhase::INFLATING, 5);
    motorInflate();
    uint32_t inflateStart = millis();
    float lastP = p0;
    uint32_t lastRiseCheck = millis();

    for (;;) {
      vTaskDelay(pdMS_TO_TICKS(BP_SAMPLE_INTERVAL_MS));
      float p = readPressureMmHg();
      publishPressure(p);
      setPhase(BpPhase::INFLATING, (uint8_t)(5 + 35.0f * min(p / BP_TARGET_INFLATE_MMHG, 1.0f)));

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
    }
    motorStop();
    vTaskDelay(pdMS_TO_TICKS(300));                         // let pressure settle

    // ---- Phase 2: controlled deflation + oscillation capture ----
    setPhase(BpPhase::MEASURING, 40);
    const int MAX_POINTS = 512;
    static float envP[MAX_POINTS];                          // pressure at sample
    static float envA[MAX_POINTS];                          // envelope amplitude
    int points = 0;

    float baseline = readPressureMmHg();
    float envelope = 0;
    uint8_t pwm = BP_DEFLATE_PWM;
    motorDeflate(pwm);

    float startP = baseline;
    uint32_t lastRateCheck = millis();
    float rateRefP = baseline;
    uint32_t lastRecord = 0;

    for (;;) {
      vTaskDelay(pdMS_TO_TICKS(BP_SAMPLE_INTERVAL_MS));
      float p = readPressureMmHg();
      publishPressure(p);

      if (millis() - cycleStart > BP_MEASURE_TIMEOUT_MS) {
        setPhase(BpPhase::ERROR, 0, "Measurement timeout");
        finishSafe(); return;
      }
      if (p >= BP_HARD_ABORT_MMHG) {
        setPhase(BpPhase::ERROR, 0, "Overpressure — aborted");
        finishSafe(); return;
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

      // deflation-rate control toward ~3 mmHg/s
      if (millis() - lastRateCheck >= 1000) {
        float rate = rateRefP - p;                          // mmHg over the last second
        if (rate < 0.5f) {
          // not deflating (valve stuck / pinched hose) — escalate then abort
          if (pwm >= 250) { setPhase(BpPhase::ERROR, 0, "Deflation failure"); finishSafe(); return; }
          pwm = min((int)pwm + 40, 255);
        }
        else if (rate < 2.0f) pwm = min((int)pwm + 10, 255);
        else if (rate > 4.5f) pwm = max((int)pwm - 10, 20);
        motorDeflate(pwm);
        rateRefP = p; lastRateCheck = millis();
      }

      if (p <= BP_DEFLATE_FLOOR_MMHG) break;                // capture complete
    }
    motorStop();

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
    setPhase(BpPhase::INFLATING, 5);
    for (int p = 0; p <= 180; p += 6) {
      publishPressure(p);
      setPhase(BpPhase::INFLATING, 5 + (uint8_t)(35.0f * p / 180));
      vTaskDelay(pdMS_TO_TICKS(100));
    }
    setPhase(BpPhase::MEASURING, 40);
    for (int p = 180; p >= 35; p -= 3) {
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

  float zeroOffset_ = 0;
  float lastPressure_ = 0;
};
