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
#include <Preferences.h>
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

    // Pressure scale: prefer the value measured on THIS box by the
    // guided pump calibration (survives reboots/reflashes); the config
    // default is only a first-boot placeholder.
    prefs_.begin("bp", false);
    countsPerMmHg_ = prefs_.getFloat("scale", BP_COUNTS_PER_MMHG);
    // Anchor migration: a stored scale derived under an older clip-anchor
    // assumption is rescaled to the current (reference-validated) anchor.
    // Scales saved before the anchor was recorded were clip-anchored 140.
    if (prefs_.isKey("scale")) {
      float storedAnchor = prefs_.getFloat("anchor", 140.0f);
      if (storedAnchor != 170.0f && fabsf(storedAnchor - BP_CLIP_ANCHOR_MMHG) > 0.5f) {
        float old = countsPerMmHg_;
        countsPerMmHg_ = old * storedAnchor / BP_CLIP_ANCHOR_MMHG;
        prefs_.putFloat("scale", countsPerMmHg_);
        prefs_.putFloat("anchor", BP_CLIP_ANCHOR_MMHG);
        Serial.printf("[bp] scale migrated %.0f -> %.0f counts/mmHg "
                      "(clip anchor %.0f -> %.0f, reference-validated)\n",
                      old, countsPerMmHg_, storedAnchor, BP_CLIP_ANCHOR_MMHG);
      }
    }
    Serial.printf("[bp] pressure scale: %.0f counts/mmHg (%s)\n", countsPerMmHg_,
                  prefs_.isKey("scale") ? "measured, stored on device"
                                        : "factory default - run CAL PUMP on the BP page");

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
      bool calRequested = false;
      if (stateLock()) {
        calRequested = g_state.bpCalRequested;
        g_state.bpCalRequested = false;
        stateUnlock();
      }
      if (calRequested) { runCalibrationPump(); continue; }
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
  Preferences prefs_;
  int32_t zeroRaw_ = 0;
  float countsPerMmHg_ = BP_COUNTS_PER_MMHG;
  bool sensorPresent_ = false;
  float lastPressure_ = 0;

  float countsToMmHg(int32_t raw) {
    return (float)((double)(raw - zeroRaw_) / countsPerMmHg_);
  }

  // One fresh sample. Caller owns the bus + pin guard. false = not ready
  // within capMs (sensor silent or conversion still running).
  bool readSample(float &mmHg, uint32_t capMs) {
    if (!cuffAdcWaitReady(capMs)) return false;
    mmHg = countsToMmHg(cuffAdcClockOut24());
    lastPressure_ = mmHg;
    return true;
  }

  // Probe the ADC and capture the zero point. PRECONDITION: caller owns
  // the bus and a CuffAdcPinGuard is active. Generous timing (some
  // HX710/HX711 variants convert at only 10 samples/s) + one internal
  // retry — a single missed conversation must never condemn the sensor.
  bool probeAndZeroOwned() {
    for (int attempt = 0; attempt < 2; attempt++) {
      cuffAdcResetSync();
      if (!cuffAdcWaitReady(900)) continue;        // first conv after reset is slow
      (void)cuffAdcClockOut24();                   // discard (framing slip)
      if (cuffAdcWaitReady(400)) (void)cuffAdcClockOut24();  // discard #2
      int got = 0;
      int64_t sum = 0;
      for (int i = 0; i < 12; i++) {
        if (!cuffAdcWaitReady(400)) continue;
        sum += cuffAdcClockOut24();
        got++;
      }
      if (got >= 6) {
        zeroRaw_ = (int32_t)(sum / got);
        sensorPresent_ = true;
        Serial.printf("[bp] zero-cal: ok - pressure ADC answering (samples %d, zero raw %ld)\n",
                      got, (long)zeroRaw_);
        if (stateLock()) { g_state.chBp = Chan::OK; stateUnlock(); }
        return true;
      }
    }
    sensorPresent_ = false;
    Serial.println("[bp] zero-cal: FAULT - pressure ADC not responding (DOUT never ready)");
    if (stateLock()) { g_state.chBp = Chan::FAULT; stateUnlock(); }
    return false;
  }

  void zeroCalibrate() {
    // Runs at boot BEFORE the UI task exists (deliberate .ino ordering),
    // so the bus is quiet; the mutex take is form, not necessity.
    xSemaphoreTake(g_spiBusMutex, portMAX_DELAY);
    {
      CuffAdcPinGuard guard;
      probeAndZeroOwned();
    }
    xSemaphoreGive(g_spiBusMutex);
  }

  // ================= motor =================
  // HARDWARE TRUTH (measured live, v3.2.0 log): driving the H-bridge in
  // "reverse" ALSO pumps air IN — pressure rose 312→366 mmHg during what
  // the code believed was active deflation, while the user watched the
  // cuff fill. There is NO powered deflate on this box: it vents
  // PASSIVELY through its bleed valve whenever the motor is off (the
  // cuff emptied as soon as the motor stopped). So: deflation == stop.
  bool motorOn_ = false;
  void motorInflate() {
    digitalWrite(PIN_MOTOR_IN1, HIGH); digitalWrite(PIN_MOTOR_IN2, LOW);
    ledcWrite(PIN_MOTOR_ENA, BP_INFLATE_PWM);
    if (!motorOn_) { motorOn_ = true; Serial.println("[bp] motor ON (pumping)"); }
  }
  void motorStop() {
    digitalWrite(PIN_MOTOR_IN1, LOW); digitalWrite(PIN_MOTOR_IN2, LOW);
    ledcWrite(PIN_MOTOR_ENA, 0);
    if (motorOn_) { motorOn_ = false; Serial.println("[bp] motor OFF (venting)"); }
  }

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
    return (uint16_t)((v >> 3) & 0x0FFF);      // 12-bit result (busy bit + 12 data)
  }

  // A press must LOOK like a press, twice. The first field run of this
  // detector cancelled two measurements at the very first poll: a
  // garbage read (all zeros) computed as z = 0 + 4095 - 0 = "maximum
  // pressure". Real fingers give mid-range z1 AND pull z2 down from its
  // rail; rail/zero values are electrical noise, not skin. Two reads
  // must agree — noise isn't stable across 2 ms, a held finger is.
  bool touchCancelPoll() {
    auto readZ = [&](uint16_t &z1, uint16_t &z2) {
      digitalWrite(SHARED_PIN_TOUCH_CS, LOW);
      z1 = xptTransfer(0xB1);                  // pressure electrode 1
      z2 = xptTransfer(0xC1);                  // pressure electrode 2
      xptTransfer(0xD0);                       // power down between polls
      digitalWrite(SHARED_PIN_TOUCH_CS, HIGH);
    };
    auto firm = [](uint16_t z1, uint16_t z2) {
      if (z1 < 120 || z1 > 4000) return false; // rail / open-circuit garbage
      if (z2 == 0 || z2 > 4060) return false;  // rail / open-circuit garbage
      return (int)z1 + 4095 - (int)z2 > 1100;
    };
    uint16_t z1a, z2a, z1b, z2b;
    readZ(z1a, z2a);
    delayMicroseconds(2000);
    readZ(z1b, z2b);
    bool cancel = firm(z1a, z2a) && firm(z1b, z2b)
               && abs((int)z1a - (int)z1b) < 400;
    if (cancel) {
      Serial.printf("[bp] cancel touch confirmed (z1 %u/%u, z2 %u/%u)\n", z1a, z1b, z2a, z2b);
    }
    return cancel;
  }

  // ================= the real measurement (bus OWNED throughout) ========
  void runRealCycle() {
    digitalWrite(PIN_LED_BP, HIGH);
    cancelRequested();                                      // clear stale
    lastPressure_ = 0;

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

  // ============ guided pump-scale calibration (BP page → CAL PUMP) ======
  // The factory counts-per-mmHg guess was ~10x off on the real box: the
  // display claimed 183 mmHg after 3 s while the cuff sat flat and
  // gripless. This mode measures the true scale on THIS hardware: wrap
  // the cuff on an arm, press CAL PUMP — the motor runs on a pure TIME
  // budget (raw counts only, no trusted pressure), and the user presses
  // and HOLDS the screen the moment the cuff grips clinic-tight (the
  // ~170 mmHg anchor every clinician knows by feel). raw-delta / 170 =
  // counts per mmHg, stored in flash. Crude (±20%), but it turns a 10x
  // error into a working monitor; reference-gauge validation refines it.
  void runCalibrationPump() {
    Serial.println("[bp] CAL PUMP started - press & HOLD the screen when the cuff is firmly tight");
    digitalWrite(PIN_LED_BP, HIGH);
    cancelRequested();                                      // clear stale
    setPhase(BpPhase::ZEROING, 2);
    vTaskDelay(pdMS_TO_TICKS(450));                         // UI paints the paused screen
    if (xSemaphoreTake(g_spiBusMutex, pdMS_TO_TICKS(3000)) != pdTRUE) {
      setPhase(BpPhase::ERROR, 0, "Screen busy - try again");
      digitalWrite(PIN_LED_BP, LOW);
      return;
    }
    if (g_tftSpi) g_tftSpi->end();
    {
      CuffAdcPinGuard guard;
      calibrationOwned();
    }
    if (g_tftSpi) g_tftSpi->begin(PIN_PRES_SCK, SHARED_PIN_MISO, SHARED_PIN_MOSI, -1);
    xSemaphoreGive(g_spiBusMutex);
    digitalWrite(PIN_LED_BP, LOW);
  }

  void calibrationOwned() {
    if (!sensorPresent_ && !probeAndZeroOwned()) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
      return;
    }
    if (!cuffAdcSyncSettle()) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
      return;
    }
    // fresh zero (drift!)
    {
      int64_t sum = 0; int got = 0;
      for (int i = 0; i < 8; i++) {
        if (!cuffAdcWaitReady(400)) continue;
        sum += cuffAdcClockOut24(); got++;
      }
      if (got < 4) { setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding"); return; }
      zeroRaw_ = (int32_t)(sum / got);
    }

    // Remember what a TRULY EMPTY cuff reads (used to detect residual
    // pressure at future measurement starts).
    prefs_.putInt("zempty", (int32_t)zeroRaw_);

    setPhase(BpPhase::INFLATING, 20);
    motorInflate();
    uint32_t start = millis(), lastCancelPoll = millis();
    int32_t raw = zeroRaw_;
    int32_t maxDelta = 0;
    uint32_t lastGrowth = millis();
    int lastLoggedSec = -1;
    bool stopped = false, clipped = false;

    for (;;) {
      if (millis() - start > 30000) break;                  // hard time budget
      if (cuffAdcWaitReady(150)) {
        int32_t r = cuffAdcClockOut24();
        // ignore framing slips (doubled values) for the captured maximum
        if (labs((long)(r - raw)) < labs((long)(raw - zeroRaw_)) + 400000) raw = r;
        int32_t d = raw - zeroRaw_;
        if (d > maxDelta + 30000) { maxDelta = d; lastGrowth = millis(); }
        // Plateau while pumping = the ADC hit its saturation ceiling
        // (measured live: frozen at exactly +8388607 counts while the
        // pump kept running). That plateau IS the calibration anchor —
        // stop, don't keep pumping into a blind sensor.
        if (maxDelta > 500000 && millis() - lastGrowth > 1500) { clipped = true; break; }
      }
      int sec = (int)((millis() - start) / 1000);
      if (sec != lastLoggedSec) {
        lastLoggedSec = sec;
        Serial.printf("[bp] cal t=%ds raw delta %ld\n", sec, (long)(raw - zeroRaw_));
      }
      if (millis() - lastCancelPoll > 800) {                // user says "tight now"
        lastCancelPoll = millis();
        bool stop = touchCancelPoll() || cancelRequested();
        cuffAdcSyncSettle();
        if (stop) { stopped = true; break; }
      }
    }
    motorStop();

    int32_t delta = raw - zeroRaw_;
    Serial.printf("[bp] cal finished (%s): raw delta %ld\n",
                  clipped ? "ADC ceiling" : stopped ? "user stop" : "time budget", (long)delta);
    if (delta > 20000) {
      // Anchor: the ADC clip plateau is a repeatable physical constant
      // (~BP_CLIP_ANCHOR_MMHG); subjective "felt tight" (~170) is the
      // fallback when the ceiling was never reached.
      float anchor = clipped ? BP_CLIP_ANCHOR_MMHG : 170.0f;
      float newScale = constrain((float)delta / anchor, 1000.0f, 200000.0f);
      countsPerMmHg_ = newScale;
      prefs_.putFloat("scale", newScale);
      prefs_.putFloat("anchor", anchor);   // recorded for future anchor migrations
      Serial.printf("[bp] SCALE CALIBRATED: %.0f counts/mmHg stored (anchor: %s ~%.0f mmHg)\n",
                    newScale, clipped ? "ADC ceiling" : "cuff felt tight", anchor);
      finishSafe();
      setPhase(BpPhase::IDLE, 0);
    } else {
      setPhase(BpPhase::ERROR, 0, "Calibration: no pressure rise");
      finishSafe();
    }
  }

  void runCycleOwned() {
    uint32_t cycleStart = millis();

    // ---- Phase 0: probe (if needed) + sync + settle + sanity ----
    // A failed BOOT probe no longer condemns the sensor forever: every
    // START re-probes fresh. One bad boot handshake used to mean
    // "Pressure sensor not detected" until the next power cycle.
    if (!sensorPresent_) {
      Serial.println("[bp] sensor was absent at boot - re-probing now");
      if (!probeAndZeroOwned()) {
        setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
        return;                                    // motor never started; nothing to vent
      }
    }
    // Settle discards the first two conversions: the first-after-reset
    // sample framing-slipped live (an exactly-doubled reading, 311.6
    // with an empty cuff) and failed the sanity gate for nothing.
    if (!cuffAdcSyncSettle()) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
      finishSafe();
      return;
    }

    // PER-CYCLE AUTO-ZERO. The bridge's zero drifted by ~60 mmHg worth
    // of counts between boots on the battery-fed module (observed live:
    // boot zeros 1.437M → 1.722M, an empty cuff then "reading" -14),
    // so a boot-time zero is worthless minutes later. Like commercial
    // monitors: the cuff is empty at START — capture zero right now.
    {
      int64_t sum = 0; int got = 0;
      for (int i = 0; i < 8; i++) {
        if (!cuffAdcWaitReady(400)) continue;
        sum += cuffAdcClockOut24();
        got++;
      }
      if (got < 4) {
        setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
        finishSafe();
        return;
      }
      int32_t newZero = (int32_t)(sum / got);
      Serial.printf("[bp] auto-zero: raw %ld (drift %+.1f mmHg since previous zero)\n",
                    (long)newZero, (float)((double)(newZero - zeroRaw_) / countsPerMmHg_));
      zeroRaw_ = newZero;
      // Residual-pressure check vs the truly-empty zero captured at
      // calibration: auto-zeroing on a half-full cuff silently eats the
      // ADC's headroom above the zero (observed live: +190 mmHg of
      // "drift" that was really trapped air, ceiling down to 148).
      int32_t zEmpty = prefs_.getInt("zempty", newZero);
      float residual = (float)((double)(newZero - zEmpty) / countsPerMmHg_);
      if (residual > 15.0f) {
        Serial.printf("[bp] warning: cuff holds ~%.0f mmHg residual air at start "
                      "(loosen/empty the cuff for full range)\n", residual);
      }
    }
    float p0;
    if (!readSample(p0, 700)) {
      setPhase(BpPhase::ERROR, 0, "Pressure sensor not responding");
      finishSafe();
      return;
    }
    // Post-auto-zero the empty cuff must read ~0 by construction; a big
    // residual now means the signal itself is unstable, not "not empty".
    if (fabsf(p0) > 15.0f) {
      setPhase(BpPhase::ERROR, 0, "Pressure signal unstable - retry");
      finishSafe();
      return;
    }

    // ---- Phase 1: pressure-feedback inflation (clip-aware) ----
    // The ADC saturates at BP_ADC_MAX_COUNTS — pressure above that is
    // invisible. Inflate to the configured target OR just below the
    // ceiling this cycle's zero leaves us, whichever is lower.
    float ceiling = (float)((double)(BP_ADC_MAX_COUNTS - zeroRaw_) / countsPerMmHg_);
    float target = min((float)BP_TARGET_INFLATE_MMHG, ceiling - 6.0f);
    Serial.printf("[bp] inflation target %.0f mmHg (ADC ceiling %.0f)\n", target, ceiling);
    if (target < BP_MIN_USABLE_TARGET) {
      setPhase(BpPhase::ERROR, 0, "Empty the cuff fully, then retry");
      finishSafe();
      return;
    }
    setPhase(BpPhase::INFLATING, 5);
    motorInflate();
    uint32_t inflateStart = millis();
    float p = p0, lastP = p0;
    uint32_t lastRiseCheck = millis(), lastCancelPoll = millis();
    int misses = 0;

    float prevP = p0;
    for (;;) {
      if (!readSample(p, 150)) {
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
      if (p >= target) {                                    // target reached —
        float confirm;                                      // confirm it isn't a
        if (readSample(confirm, 200)                        // doubled sample
            && confirm >= target - 20.0f) break;
        continue;
      }
      if (millis() - inflateStart > BP_INFLATE_TIMEOUT_MS) {
        setPhase(BpPhase::ERROR, 0, "Inflation timeout — check cuff");
        finishSafe(); return;
      }
      if (millis() - lastRiseCheck > 3000) {                // stall detection
        if (p - lastP < 3.0f) {
          // Pegged just under the ceiling is CLIP, not a hose problem —
          // proceed to measure with what we have.
          if (p >= ceiling - 12.0f) {
            Serial.printf("[bp] stopping at ADC ceiling (%.0f mmHg) - measuring from here\n", p);
            break;
          }
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
    float inflateSecs = (millis() - inflateStart) / 1000.0f;
    Serial.printf("[bp] inflated to %.0f mmHg in %.1f s\n", p, inflateSecs);
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
    uint32_t measureStart = millis();
    uint32_t lastRateCheck = millis();
    float rateRefP = baseline;
    float bleedRate = 0;                                    // mmHg/s, measured
    int stallWindows = 0;
    uint32_t lastRecord = 0;
    lastCancelPoll = millis();
    misses = 0;
    prevP = baseline;

    for (;;) {
      if (!readSample(p, 150)) {
        if (++misses >= 10) { setPhase(BpPhase::ERROR, 0, "Pressure sensor stopped"); finishSafe(); return; }
        continue;
      }
      // Bit-slip guard, measure-phase edition: during passive deflation
      // pressure only FALLS, a few mmHg/s, oscillating ±5. A jump of
      // >25 mmHg between consecutive samples is a framing glitch — at
      // low pressures a DOUBLED sample (66 → "133") slipped under the
      // old 250 gate, made one rate window read negative, and aborted a
      // perfectly-venting run with "Cuff not venting" (observed live).
      if (fabsf(p - prevP) > 25.0f) {
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

      // police the natural bleed rate (no actuator to adjust — motor off).
      // A stall verdict needs THREE consecutive stalled seconds: one
      // corrupt sample must never abort a healthy run again.
      if (millis() - lastRateCheck >= 1000) {
        bleedRate = rateRefP - p;                           // mmHg over the last second
        if (bleedRate < 0.3f) {
          if (++stallWindows >= 3) {
            setPhase(BpPhase::ERROR, 0, "Cuff not venting — check valve");
            finishSafe(); return;
          }
        } else {
          stallWindows = 0;
        }
        rateRefP = p; lastRateCheck = millis();
      }

      if (p <= BP_DEFLATE_FLOOR_MMHG) break;                // capture complete
    }
    motorStop();
    // Judge "too fast" on the WHOLE capture, not one window.
    float captureSecs = (millis() - measureStart) / 1000.0f;
    float avgBleed = captureSecs > 1.0f ? (startP - p) / captureSecs : 0;
    Serial.printf("[bp] capture done: %d envelope points in %.0f s, avg bleed %.1f mmHg/s\n",
                  points, captureSecs, avgBleed);
    if (avgBleed > 12.0f) {
      // Vented so fast the envelope can't contain enough pulses. The
      // combination fast-fill + fast-collapse means the air only ever
      // pressurised the hose stub, not the cuff (kinked / slipped hose —
      // diagnosed live: 183 mmHg in 3 s, then 30 mmHg/s collapse, while
      // the user watched the cuff stay flat).
      bool pneumatic = inflateSecs < 4.0f;
      setPhase(BpPhase::ERROR, 0,
               pneumatic ? "Air not reaching cuff — check hose"
                         : "Deflated too fast — tighten valve, retry");
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
