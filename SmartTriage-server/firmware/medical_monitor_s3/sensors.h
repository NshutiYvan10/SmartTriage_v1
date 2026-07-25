/*
 * sensors.h — continuous-vitals pipelines.
 *
 *  SpO2/HR  : MAX30102 via SparkFun MAX3010x library (the previous build
 *             used the MAX30100 library — a register-incompatible chip —
 *             which is why HR/SpO2 were permanently zero).
 *  Temp     : MAX30205 medical-grade contact sensor, raw I2C.
 *  ECG + RR : AD8232 at 250 Hz — baseline-wander removal, mains notch,
 *             adaptive R-peak HR, ECG-derived respiration.
 *
 *  Every output passes: plausibility clamp → outlier gate → median (HR)
 *  → EMA. A value that can't pass is REJECTED, never displayed.
 */
#pragma once
#include <Wire.h>
#include "MAX30105.h"
#include "heartRate.h"
#include "max30100.h"
#include "config.h"
#include "filters.h"
#include "state.h"

// =====================================================================
//  SpO2 / pulse pipeline — auto-detects MAX30102 vs MAX30100 silicon.
//  (The box's chip ACKs at 0x57 but failed the MAX3010x part-ID check,
//  and its LED lit under the original sketch's MAX30100 library — so
//  the fitted part is likely the 16-bit MAX30100. Both are supported;
//  the R-ratio math downstream is scale-invariant.)
// =====================================================================
class Spo2Pipeline {
public:
  bool begin() {
    // I2C_SPEED_STANDARD: the MAX30205 shares the bus and SMBus-era
    // parts are only safe at 100 kHz.
    if (sensor_.begin(Wire, I2C_SPEED_STANDARD)) {
      // LED 60 mA, avg 4, Red+IR, 100 Hz, 411 µs (18-bit), range 4096
      sensor_.setup(60, 4, 2, 100, 411, 4096);
      sensor_.setPulseAmplitudeRed(0x1F);
      sensor_.setPulseAmplitudeIR(0x1F);
      sensor_.setPulseAmplitudeGreen(0);
      chip_ = Chip::M30102;
      present_ = true;
      Serial.println("[spo2] MAX30102 detected (18-bit)");
      return true;
    }
    if (legacy_.begin(Wire)) {
      chip_ = Chip::M30100;
      present_ = true;
      Serial.println("[spo2] MAX30100 detected (legacy 16-bit part - the chip the original sketch drove)");
      return true;
    }
    // Neither driver claimed it — log the raw part ID for diagnosis.
    Wire.beginTransmission(0x57);
    Wire.write(0xFF);
    if (Wire.endTransmission(false) == 0 && Wire.requestFrom(0x57, 1) == 1) {
      Serial.printf("[spo2] chip at 0x57 rejected by both drivers (part id 0x%02X; 0x15=MAX30102, 0x11=MAX30100)\n",
                    Wire.read());
    }
    return false;
  }
  bool present() const { return present_; }

  // Drain the FIFO; call as often as possible from the sensor task.
  void poll(bool ecgLeadsOff) {
    if (!present_) return;
    if (chip_ == Chip::M30102) {
      sensor_.check();
      while (sensor_.available()) {
        long ir  = sensor_.getFIFOIR();
        long red = sensor_.getFIFORed();
        sensor_.nextSample();
        handleSample(ir, red, ecgLeadsOff);
      }
    } else {
      legacy_.check();
      while (legacy_.available()) {
        long ir  = legacy_.getFIFOIR();
        long red = legacy_.getFIFORed();
        legacy_.nextSample();
        handleSample(ir, red, ecgLeadsOff);
      }
    }

    // Compute SpO2 once per full-ish window
    uint32_t now = millis();
    if (fingerOn_ && sampleCount_ >= SPO2_MIN_SAMPLES && now - lastCalcMs_ >= 500) {
      lastCalcMs_ = now;
      computeSpo2();
    }

    // Finger removed → decay to unknown instead of freezing stale values
    if (!fingerOn_ && millis() - lastFingerMs_ > FINGER_LOST_RESET_MS) {
      reset();
    }

    publish(ecgLeadsOff);
  }

  float irFallbackBpm() const { return irBeatBpm_; }
  bool  fingerOn() const { return fingerOn_; }

private:
  // One FIFO sample, either chip (MAX30100 samples arrive pre-scaled x4
  // into the 18-bit-ish range these thresholds were tuned for).
  void handleSample(long ir, long red, bool ecgLeadsOff) {
    fingerOn_ = ir > FINGER_IR_THRESHOLD;
    if (!fingerOn_) return;
    lastFingerMs_ = millis();

    // pleth trace for the waveform page (AC component around DC)
    float ac = plethDc_.highpass((float)ir, 0.02f);
    uint16_t h = (uint16_t)((g_plethWaveHead + 1) % ECG_WAVE_RING);
    g_plethWave[h] = (int16_t)constrain(ac / 8.0f, -2000.0f, 2000.0f);
    g_plethWaveHead = h;

    irBuf_[bufIdx_]  = ir;
    redBuf_[bufIdx_] = red;
    bufIdx_ = (bufIdx_ + 1) % SPO2_BUFFER_SIZE;
    if (sampleCount_ < SPO2_BUFFER_SIZE) sampleCount_++;

    // HR fallback from the IR beat detector while ECG can't provide it
    if (ecgLeadsOff && checkForBeat(ir)) {
      uint32_t now = millis();
      if (lastIrBeatMs_ > 0) {
        long delta = (long)(now - lastIrBeatMs_);
        if (delta > 300 && delta < 2000) irBeatBpm_ = 60000.0f / delta;
      }
      lastIrBeatMs_ = now;
    }
  }

  void computeSpo2() {
    int n = min(sampleCount_, (int)SPO2_BUFFER_SIZE);
    long irSum = 0, redSum = 0;
    long irMax = irBuf_[0], irMin = irBuf_[0];
    long redMax = redBuf_[0], redMin = redBuf_[0];
    for (int i = 0; i < n; i++) {
      irSum += irBuf_[i]; redSum += redBuf_[i];
      if (irBuf_[i] > irMax) irMax = irBuf_[i];
      if (irBuf_[i] < irMin) irMin = irBuf_[i];
      if (redBuf_[i] > redMax) redMax = redBuf_[i];
      if (redBuf_[i] < redMin) redMin = redBuf_[i];
    }
    float irDC = (float)irSum / n, redDC = (float)redSum / n;
    if (irDC <= 0 || redDC <= 0) return;

    float irAC = (float)(irMax - irMin), redAC = (float)(redMax - redMin);
    if (irAC < 200 || redAC < 200) return;              // no pulsatile signal

    perfusion_ = irAC / irDC;
    if (perfusion_ < 0.004f) return;                    // perfusion too weak

    float R = (redAC / redDC) / (irAC / irDC);
    if (R < 0.2f || R > 1.0f) return;                   // implausible ratio

    // Median across R windows — the key stability mechanism: outlier
    // windows are rejected without biasing the ratio.
    rHist_.push(R);
    float rMed = rHist_.median();
    float raw = constrain(110.0f - 25.0f * rMed, SPO2_MIN, SPO2_MAX);

    if (ema_.primed && outlierAbsolute(raw, ema_.value, SPO2_OUTLIER_ABS)) return;
    ema_.update(raw, EMA_ALPHA_SPO2);
  }

  void publish(bool ecgLeadsOff) {
    if (!stateLock(5)) return;
    g_state.chSpo2 = !present_ ? Chan::ABSENT
                   : fingerOn_ ? Chan::OK : Chan::NO_CONTACT;
    g_state.spo2 = ema_.primed ? ema_.value : 0.0f;
    g_state.perfusionIndex = fingerOn_ ? perfusion_ : 0.0f;
    stateUnlock();
    (void)ecgLeadsOff;
  }

  void reset() {
    sampleCount_ = 0; bufIdx_ = 0;
    rHist_.reset(); ema_.reset();
    irBeatBpm_ = 0; lastIrBeatMs_ = 0;
    perfusion_ = 0;
  }

  MAX30105 sensor_;
  Max30100Raw legacy_;
  enum class Chip { NONE, M30102, M30100 };
  Chip  chip_ = Chip::NONE;
  bool  present_ = false, fingerOn_ = false;
  long  irBuf_[SPO2_BUFFER_SIZE] = {0}, redBuf_[SPO2_BUFFER_SIZE] = {0};
  int   bufIdx_ = 0, sampleCount_ = 0;
  MedianRing<R_RATIO_HIST_SIZE> rHist_;
  Ema   ema_;
  DcTracker plethDc_;
  float perfusion_ = 0, irBeatBpm_ = 0;
  uint32_t lastFingerMs_ = 0, lastIrBeatMs_ = 0, lastCalcMs_ = 0;
};

// =====================================================================
//  Temperature pipeline (MAX30205 contact sensor)
// =====================================================================
class TempPipeline {
public:
  void begin() {
    Wire.beginTransmission(MAX30205_ADDR);
    present_ = (Wire.endTransmission() == 0);
  }
  bool present() const { return present_; }

  void poll() {
    uint32_t now = millis();
    if (!present_) {
      // Publish the absent state at 1 Hz, NOT every 2 ms tick — the
      // unthrottled version hammered the state mutex ~500x/s from core 1
      // and visibly degraded UI responsiveness on core 0.
      if (now - lastReadMs_ >= 1000) { lastReadMs_ = now; publish(Chan::ABSENT, 0); }
      return;
    }
    if (now - lastReadMs_ < TEMP_READ_INTERVAL_MS) return;
    lastReadMs_ = now;

    float raw = readRaw();
    if (isnan(raw)) {
      if (++faults_ >= 5) publish(Chan::FAULT, ema_.primed ? ema_.value : 0);
      return;
    }
    faults_ = 0;

    if (raw < TEMP_RAW_MIN) {                 // sensor not against skin
      noContactSince_ = noContactSince_ ? noContactSince_ : now;
      if (now - noContactSince_ > 5000) { ema_.reset(); publish(Chan::NO_CONTACT, 0); }
      return;
    }
    noContactSince_ = 0;
    if (raw > TEMP_RAW_MAX) return;           // implausible — reject

    float calibrated = raw + TEMP_SITE_OFFSET_C;
    calibrated = constrain(calibrated, TEMP_MIN, TEMP_MAX);
    if (ema_.primed && outlierAbsolute(calibrated, ema_.value, TEMP_OUTLIER_ABS)) return;
    ema_.update(calibrated, EMA_ALPHA_TEMP);
    publish(Chan::OK, ema_.value);
  }

private:
  float readRaw() {
    Wire.beginTransmission(MAX30205_ADDR);
    Wire.write(0x00);
    if (Wire.endTransmission(false) != 0) return NAN;
    if (Wire.requestFrom((int)MAX30205_ADDR, 2) < 2) return NAN;
    uint8_t msb = Wire.read(), lsb = Wire.read();
    return ((int16_t)((msb << 8) | lsb)) * 0.00390625f;
  }
  void publish(Chan c, float t) {
    if (!stateLock(5)) return;
    g_state.chTemp = c;
    g_state.temp = t;
    stateUnlock();
  }
  bool present_ = false;
  Ema ema_;
  int faults_ = 0;
  uint32_t lastReadMs_ = 0, noContactSince_ = 0;
};

// =====================================================================
//  ECG pipeline (AD8232) — HR + respiration + waveform
// =====================================================================
class EcgPipeline {
public:
  void begin() {
    pinMode(PIN_ECG_LO_P, INPUT);
    pinMode(PIN_ECG_LO_N, INPUT);
    analogReadResolution(12);
    notch_.configure(ECG_MAINS_HZ, 1000.0f / ECG_SAMPLE_INTERVAL_MS);
  }

  bool leadsOff() const { return leadsOff_; }

  // Call every SENSOR_TASK_TICK_MS; samples internally at 250 Hz.
  void poll(float irFallbackBpm, bool fingerOn) {
    uint32_t now = millis();
    if (now - lastSampleMs_ < ECG_SAMPLE_INTERVAL_MS) return;
    lastSampleMs_ = now;

    // Leads-off with DEBOUNCE: the AD8232 LO pins chatter when electrode
    // contact is marginal — a single noisy sample must not blank the HR
    // ("sometimes a reading shows, sometimes it doesn't" — field report).
    bool loRaw = digitalRead(PIN_ECG_LO_P) == HIGH || digitalRead(PIN_ECG_LO_N) == HIGH;
    if (loRaw) {
      loLowSince_ = 0;
      if (!loHighSince_) loHighSince_ = now;
      if (!leadsOff_ && now - loHighSince_ >= ECG_LO_ON_MS) leadsOff_ = true;
    } else {
      loHighSince_ = 0;
      if (!loLowSince_) loLowSince_ = now;
      if (leadsOff_ && now - loLowSince_ >= ECG_LO_OFF_MS) leadsOff_ = false;
    }
    if (leadsOff_) {
      onLeadsOff(now, irFallbackBpm, fingerOn);
      return;
    }
    if (loRaw) return;   // connected, but this sample is contact noise — skip it
    leadsOnSince_ = leadsOnSince_ ? leadsOnSince_ : now;

    int raw = analogRead(PIN_ECG);
    // baseline-wander removal (≈0.4 Hz HPF) then mains notch
    float hp = dc_.highpass((float)raw, ECG_BASELINE_ALPHA);
    float filtered = notch_.process(hp);

    // waveform ring for UI + payload export
    uint16_t h = (uint16_t)((g_ecgWaveHead + 1) % ECG_WAVE_RING);
    g_ecgWave[h] = (int16_t)constrain(filtered, -2047.0f, 2047.0f);
    g_ecgWaveHead = h;
    finalizeBeatExport(h);

    detectRPeak(filtered, now);
    maybeComputeRespiration(now);
    publish(now);
  }

  // One representative beat as CSV for DeviceVitalPayload.ecgWaveform.
  // Returns bytes written (0 = no beat captured yet).
  size_t exportBeatCsv(char *out, size_t cap) {
    if (!beatReady_) return 0;
    size_t off = 0;
    for (int i = 0; i < ECG_EXPORT_SAMPLES && off + 8 < cap; i++) {
      off += snprintf(out + off, cap - off, i ? ",%d" : "%d", (int)beatExport_[i]);
    }
    return off;
  }

private:
  void onLeadsOff(uint32_t now, float irFallbackBpm, bool fingerOn) {
    leadsOnSince_ = 0;
    inBeat_ = false;
    // trace flatlines visibly (UI overlays "LEADS OFF")
    uint16_t h = (uint16_t)((g_ecgWaveHead + 1) % ECG_WAVE_RING);
    g_ecgWave[h] = 0;
    g_ecgWaveHead = h;

    // HR falls back to the IR beat detector when a finger is on the pulse-ox
    if (fingerOn && irFallbackBpm > 0) acceptHr(irFallbackBpm, false);
    updateQualityAndHold(now);
    publish(now);
  }

  // ---- beat detection + VALIDATION (v3.4.0) ----
  // A structural peak is only a HEARTBEAT if its R-R interval fits the
  // established rhythm; a genuine rate change must prove itself with
  // ECG_RHYTHM_N consecutive mutually-consistent intervals. This is what
  // stops T-waves and motion artifacts from bouncing the display
  // (field report: HR jumping 115 ↔ 96).
  void detectRPeak(float filtered, uint32_t now) {
    float threshold = max(adaptive_ * ECG_ADAPT_FRACTION, 150.0f);
    // Dynamic refractory: 40% of the rhythm's median R-R (never below the
    // hard floor) keeps the detector out of T-wave territory at slow rates.
    uint32_t refractory = ECG_REFRACTORY_MS;
    if (rrCnt_ >= 4) {
      refractory = (uint32_t)constrain(0.4f * rrMedian(), (float)ECG_REFRACTORY_MS, 600.0f);
    }
    bool pastRefractory = (now - lastCandidateMs_) > refractory;

    if (filtered > threshold && pastRefractory) {
      if (!inBeat_) { inBeat_ = true; peakVal_ = filtered; peakHead_ = g_ecgWaveHead; }
      else if (filtered > peakVal_) { peakVal_ = filtered; peakHead_ = g_ecgWaveHead; }
    } else if (inBeat_ && filtered < threshold * 0.5f) {
      inBeat_ = false;
      if (peakVal_ < ECG_MIN_PEAK_AMP) return;              // noise, not a beat

      long candRr = lastCandidateMs_ > 0 ? (long)(now - lastCandidateMs_) : 0;
      lastCandidateMs_ = now;
      qualTotal_++;

      if (validateBeat(candRr, peakVal_)) {
        qualOk_++;
        adaptive_ = ECG_ADAPT_ALPHA * peakVal_ + (1.0f - ECG_ADAPT_ALPHA) * adaptive_;
        ampEma_ = ampEma_ > 0 ? 0.2f * peakVal_ + 0.8f * ampEma_ : peakVal_;
        scheduleBeatExport(peakHead_);

        // respiration inputs — ACCEPTED beats only (artifact amplitudes
        // were corrupting the EDR estimate too)
        rAmp_[rIdx_] = peakVal_;
        rAt_[rIdx_]  = now;
        rIdx_ = (rIdx_ + 1) % RR_BUFFER_SIZE;
        if (rCount_ < RR_BUFFER_SIZE) rCount_++;

        refreshDisplayedHr(now);
      }
    }
    updateQualityAndHold(now);
  }

  // Candidate R-R (measured candidate-to-candidate) against the rhythm.
  bool validateBeat(long rr, float amp) {
    if (rr <= 0) return true;                               // first anchor beat
    if (rr < 300 || rr > 2000) { pendingCnt_ = 0; return false; }

    if (rrCnt_ < 4) { pushRr(rr); pendingCnt_ = 0; return true; }   // bootstrap

    float med = rrMedian();
    if (fabsf((float)rr - med) <= ECG_RR_TOL_FRAC * med) {
      pushRr(rr);
      pendingCnt_ = 0;
      return true;
    }
    // Classic T-wave signature: clearly early AND clearly smaller than
    // the running R amplitude — reject outright, don't even let it argue
    // for a "rhythm change".
    if ((float)rr < 0.6f * med && amp < ECG_TWAVE_AMP_FRAC * ampEma_) {
      return false;
    }
    // Rhythm-change gate: N consecutive off-rhythm intervals that agree
    // WITH EACH OTHER become the new rhythm (a real HR change tracks
    // within ~2-3 beats; scattered artifacts never agree).
    pendingRr_[pendingCnt_ % ECG_RHYTHM_N] = (float)rr;
    pendingCnt_++;
    if (pendingCnt_ >= ECG_RHYTHM_N) {
      float mean = 0;
      for (int i = 0; i < ECG_RHYTHM_N; i++) mean += pendingRr_[i];
      mean /= ECG_RHYTHM_N;
      bool agree = true;
      for (int i = 0; i < ECG_RHYTHM_N; i++) {
        if (fabsf(pendingRr_[i] - mean) > ECG_RHYTHM_TOL_FRAC * mean) { agree = false; break; }
      }
      if (agree) {
        rrCnt_ = 0; rrIdx_ = 0;                             // adopt the new rhythm
        for (int i = 0; i < ECG_RHYTHM_N; i++) pushRr((long)pendingRr_[i]);
        pendingCnt_ = 0;
        return true;
      }
      pendingCnt_ = 0;                                      // disagreeing noise
    }
    return false;
  }

  void pushRr(long rr) {
    rrBuf_[rrIdx_] = (float)rr;
    rrIdx_ = (rrIdx_ + 1) % ECG_RR_BUF;
    if (rrCnt_ < ECG_RR_BUF) rrCnt_++;
  }

  float rrMedian() {
    float tmp[ECG_RR_BUF];
    int n = rrCnt_;
    for (int i = 0; i < n; i++) tmp[i] = rrBuf_[i];
    for (int i = 1; i < n; i++) {                           // insertion sort (n ≤ 8)
      float v = tmp[i]; int j = i - 1;
      while (j >= 0 && tmp[j] > v) { tmp[j + 1] = tmp[j]; j--; }
      tmp[j + 1] = v;
    }
    return n ? tmp[n / 2] : 0;
  }

  // Displayed HR = median of the accepted rhythm, with hysteresis — the
  // number a clinician sees moves when the RHYTHM moves, not per beat.
  void refreshDisplayedHr(uint32_t now) {
    if (rrCnt_ < 4) return;
    float bpm = 60000.0f / rrMedian();
    if (bpm < HR_MIN || bpm > HR_MAX) return;
    if (displayedHr_ <= 0 || fabsf(bpm - displayedHr_) >= ECG_HR_HYSTERESIS_BPM) {
      displayedHr_ = bpm;
    }
    hrFromEcg_ = true;
    lastHrMs_ = now;
  }

  // Fallback path (pulse-ox IR beats while ECG leads are off).
  void acceptHr(float bpm, bool fromEcg) {
    if (bpm < HR_MIN || bpm > HR_MAX) return;
    if (displayedHr_ <= 0 || fabsf(bpm - displayedHr_) >= ECG_HR_HYSTERESIS_BPM) {
      displayedHr_ = bpm;
    }
    hrFromEcg_ = fromEcg;
    lastHrMs_ = millis();
  }

  // Signal-quality ladder + hold-last-good. A brief noisy patch DIMS the
  // number instead of blanking it; only ECG_HOLD_LAST_MS of silence
  // clears it (a value flickering in and out helps nobody at a bedside).
  void updateQualityAndHold(uint32_t now) {
    if (now - qualWindowStart_ >= 10000) {
      float expected = rrCnt_ >= 4 ? 10000.0f / rrMedian() : 0;
      bool fresh = now - lastHrMs_ < ECG_HR_TIMEOUT_MS;
      if (displayedHr_ <= 0 || !fresh) {
        quality_ = displayedHr_ > 0 ? 1 : 0;                // holding stale / nothing
      } else if (expected > 0 && qualOk_ >= 0.8f * expected && qualOk_ >= qualTotal_ * 0.8f) {
        quality_ = 3;
      } else if (qualOk_ >= 4) {
        quality_ = 2;
      } else {
        quality_ = 1;
      }
      qualOk_ = 0; qualTotal_ = 0; qualWindowStart_ = now;
    }
    if (displayedHr_ > 0 && now - lastHrMs_ > ECG_HOLD_LAST_MS) {
      displayedHr_ = 0;
      quality_ = 0;
      rrCnt_ = 0; rrIdx_ = 0; pendingCnt_ = 0;
      rrEma_.reset(); rCount_ = 0;
    }
  }

  void maybeComputeRespiration(uint32_t now) {
    if (now - lastRespMs_ < RESP_CALC_INTERVAL_MS || rCount_ < RR_MIN_SAMPLES) return;
    lastRespMs_ = now;

    int n = min(rCount_, (int)RR_BUFFER_SIZE);
    float mean = 0;
    for (int i = 0; i < n; i++) mean += rAmp_[i];
    mean /= n;

    int crossings = 0;
    bool above = rAmp_[0] > mean;
    for (int i = 1; i < n; i++) {
      bool a = rAmp_[i] > mean;
      if (a != above) { crossings++; above = a; }
    }
    float breaths = crossings / 2.0f;

    // true buffer duration from R-peak timestamps
    uint32_t oldest = rAt_[(rIdx_ + RR_BUFFER_SIZE - n) % RR_BUFFER_SIZE];
    float seconds = (now - oldest) / 1000.0f;
    if (seconds <= 1.0f) return;

    float rr = breaths * 60.0f / seconds;
    if (rr < RR_MIN || rr > RR_MAX) return;
    if (rrEma_.primed && outlierPercent(rr, rrEma_.value, RR_OUTLIER_FRAC)) return;
    rrEma_.update(rr, EMA_ALPHA_RR);
  }

  // ---- beat export: capture ±(window/2) samples around a confirmed peak ----
  void scheduleBeatExport(uint16_t peakHead) {
    exportPeakHead_ = peakHead;
    samplesUntilExport_ = ECG_EXPORT_SAMPLES / 2;   // wait for the tail half
  }
  void finalizeBeatExport(uint16_t /*head*/) {
    if (samplesUntilExport_ <= 0) return;
    if (--samplesUntilExport_ > 0) return;
    // copy window centred on the peak out of the ring
    int start = (int)exportPeakHead_ - ECG_EXPORT_SAMPLES / 2;
    for (int i = 0; i < ECG_EXPORT_SAMPLES; i++) {
      beatExport_[i] = g_ecgWave[(start + i + ECG_WAVE_RING) % ECG_WAVE_RING];
    }
    beatReady_ = true;
  }

  void publish(uint32_t now) {
    if (now - lastPublishMs_ < 100) return;   // 10 Hz is plenty for numbers
    lastPublishMs_ = now;
    if (!stateLock(5)) return;
    g_state.chEcg = leadsOff_ ? Chan::NO_CONTACT : Chan::OK;
    g_state.hr = displayedHr_;
    g_state.hrFromEcg = hrFromEcg_;
    g_state.ecgQuality = quality_;
    g_state.rr = rrEma_.primed ? rrEma_.value : 0.0f;
    stateUnlock();
  }

  DcTracker dc_;
  NotchFilter notch_;
  bool  leadsOff_ = true, inBeat_ = false, hrFromEcg_ = false, beatReady_ = false;
  float peakVal_ = 0, adaptive_ = ECG_INITIAL_THRESHOLD;
  uint16_t peakHead_ = 0, exportPeakHead_ = 0;
  int   samplesUntilExport_ = 0;
  int16_t beatExport_[ECG_EXPORT_SAMPLES] = {0};
  Ema   rrEma_;
  float rAmp_[RR_BUFFER_SIZE] = {0};
  uint32_t rAt_[RR_BUFFER_SIZE] = {0};
  int   rIdx_ = 0, rCount_ = 0;
  // beat-validation state (v3.4.0)
  float rrBuf_[ECG_RR_BUF] = {0};
  int   rrIdx_ = 0, rrCnt_ = 0;
  float pendingRr_[ECG_RHYTHM_N] = {0};
  int   pendingCnt_ = 0;
  float ampEma_ = 0, displayedHr_ = 0;
  uint8_t quality_ = 0;
  int   qualOk_ = 0, qualTotal_ = 0;
  uint32_t qualWindowStart_ = 0, lastCandidateMs_ = 0;
  uint32_t loHighSince_ = 0, loLowSince_ = 0;
  uint32_t lastSampleMs_ = 0, lastHrMs_ = 0,
           lastRespMs_ = 0, lastPublishMs_ = 0, leadsOnSince_ = 0;
};

// =====================================================================
//  Simulation source — DEMO ONLY.
//  Generates coherent vitals + synthetic waveforms. While simulation is
//  active the network layer sends NOTHING (fake vitals must never reach
//  a clinical system) and the UI shows a permanent amber banner.
// =====================================================================
class SimSource {
public:
  void poll() {
    uint32_t now = millis();

    // gentle random-walk vitals every 1.2 s
    if (now - lastVitalsMs_ >= 1200) {
      lastVitalsMs_ = now;
      hr_   = drift(hr_,   72, 3, 55, 110);
      spo2_ = drift(spo2_, 97, 0.4f, 93, 100);
      temp_ = drift(temp_, 36.7f, 0.05f, 36.2f, 37.6f);
      rr_   = drift(rr_,   16, 0.8f, 10, 24);
      if (stateLock(10)) {
        g_state.hr = hr_; g_state.spo2 = spo2_; g_state.temp = temp_; g_state.rr = rr_;
        g_state.perfusionIndex = 0.02f;
        g_state.hrFromEcg = true;
        g_state.ecgQuality = 3;                 // demo signal is always "good"
        g_state.chSpo2 = g_state.chTemp = g_state.chEcg = Chan::OK;
        stateUnlock();
      }
    }

    // synthetic waveforms at 250 Hz, beat rate synced to hr_
    if (now - lastWaveMs_ >= ECG_SAMPLE_INTERVAL_MS) {
      lastWaveMs_ = now;
      float beatMs = 60000.0f / hr_;
      phase_ += ECG_SAMPLE_INTERVAL_MS / beatMs;
      if (phase_ >= 1.0f) phase_ -= 1.0f;

      uint16_t h = (uint16_t)((g_ecgWaveHead + 1) % ECG_WAVE_RING);
      g_ecgWave[h] = (int16_t)(ecgShape(phase_) * 900);
      g_ecgWaveHead = h;

      uint16_t p = (uint16_t)((g_plethWaveHead + 1) % ECG_WAVE_RING);
      g_plethWave[p] = (int16_t)(plethShape(phase_) * 800);
      g_plethWaveHead = p;
    }
  }

private:
  static float drift(float v, float target, float step, float lo, float hi) {
    v += (random(-100, 101) / 100.0f) * step + (target - v) * 0.02f;
    return constrain(v, lo, hi);
  }
  // stylized PQRST
  static float ecgShape(float t) {
    if (t < 0.08f) return 0.12f * sinf(t / 0.08f * PI);                // P
    if (t < 0.12f) return 0.0f;
    if (t < 0.14f) return -0.15f;                                      // Q
    if (t < 0.18f) return 1.0f * sinf((t - 0.14f) / 0.04f * PI);       // R
    if (t < 0.22f) return -0.25f * sinf((t - 0.18f) / 0.04f * PI);     // S
    if (t < 0.45f) return 0.0f;
    if (t < 0.60f) return 0.25f * sinf((t - 0.45f) / 0.15f * PI);      // T
    return 0.0f;
  }
  static float plethShape(float t) {
    // systolic upstroke + dicrotic notch
    if (t < 0.15f) return sinf(t / 0.15f * PI * 0.5f);
    return expf(-(t - 0.15f) * 4.0f) * (1.0f + 0.15f * sinf((t - 0.15f) * 25.0f));
  }
  float hr_ = 75, spo2_ = 97, temp_ = 36.7f, rr_ = 16, phase_ = 0;
  uint32_t lastVitalsMs_ = 0, lastWaveMs_ = 0;
};
