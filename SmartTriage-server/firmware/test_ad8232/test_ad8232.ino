/*
 * AD8232 ECG - Diagnostic Test  v2.0
 * ESP32: ECG_OUT -> GPIO 34, LO+ -> GPIO 25, LO- -> GPIO 26
 *
 * v2.0 CHANGES (signal quality overhaul):
 *   - Added IIR bandpass filter (0.5-40 Hz) for clean waveform
 *   - Plotter shows FILTERED ECG centered at 0 (no ghost traces)
 *   - Longer baseline window (200 samples = 800ms) won't eat R-peaks
 *   - Peak detection operates on filtered signal (lower, cleaner thresholds)
 *   - Leads-off outputs 0 for all channels (same Y-scale, no ghost traces)
 *   - Added signal-power indicator to diagnose hardware issues
 *
 * Outputs:
 *   - Heart Rate (BPM) from R-peak detection with adaptive threshold
 *   - Respiratory Rate (breaths/min) from ECG-derived respiration (EDR)
 *   - Filtered ECG waveform (for Serial Plotter)
 *   - Signal quality metrics
 *
 * Two modes:
 *   PLOTTER_MODE = true  -> 2-channel plotter: Filtered ECG + Threshold
 *   PLOTTER_MODE = false -> text diagnostics: HR, RR, quality, HRV
 *
 * How to test:
 *   1. Attach 3 electrodes - TRY LIMB PLACEMENT FIRST (strongest signal)
 *   2. Flash this sketch
 *   3. PLOTTER_MODE=true:  Tools -> Serial Plotter at 115200
 *      PLOTTER_MODE=false: Serial Monitor at 115200
 *   4. Stay completely still for 15-20 seconds
 *   5. Look for sharp upward spikes ~1/second (R-peaks = heartbeat)
 *
 * Electrode placement - LIMB LEAD (try this first!):
 *   Red  (RA): inner right wrist/forearm (flat on skin)
 *   Yellow(LA): inner left wrist/forearm (flat on skin)
 *   Green (RL): inner right ankle OR right hip
 *
 * If limb lead works, then try chest:
 *   Red  (RA): right below collarbone
 *   Yellow(LA): left below collarbone
 *   Green (RL): lower left ribcage
 *
 * Wiring:
 *   AD8232 OUTPUT -> GPIO 34
 *   AD8232 LO+    -> GPIO 25
 *   AD8232 LO-    -> GPIO 26
 *   AD8232 3.3V   -> ESP32 3.3V
 *   AD8232 GND    -> ESP32 GND
 */

// ======================== MODE SELECTION =============================
// true  = Serial Plotter (2-channel: filtered ECG + threshold)
// false = Serial Monitor (text diagnostics with HR, RR, quality)
#define PLOTTER_MODE  true

// ======================== PIN DEFINITIONS ============================
#define ECG_PIN       34    // Analog input (ADC1_CH6) - must be ADC1!
#define ECG_LO_PLUS   25    // Leads-off detection +
#define ECG_LO_MINUS  26    // Leads-off detection -

// ======================== SAMPLING ===================================
#define SAMPLE_RATE_HZ    250            // Standard clinical ECG rate
#define SAMPLE_INTERVAL_US (1000000UL / SAMPLE_RATE_HZ)  // 4000us
unsigned long lastSampleMicros = 0;

// ======================== IIR BANDPASS FILTER =========================
// Two cascaded 1st-order IIR filters = effective 2nd-order bandpass
//
// HIGH-PASS at 0.5 Hz (removes DC drift / baseline wander):
//   alpha_hp = RC / (RC + dt)
//   RC = 1/(2*PI*0.5) = 0.31831,  dt = 1/250 = 0.004
//   alpha_hp = 0.31831 / 0.32231 = 0.9876
//
// LOW-PASS at 40 Hz (removes HF noise from ESP32 ADC + muscle EMG):
//   alpha_lp = dt / (RC + dt)
//   RC = 1/(2*PI*40) = 0.003979,  dt = 0.004
//   alpha_lp = 0.004 / 0.007979 = 0.5013
//
#define HP_ALPHA  0.9876f
#define LP_ALPHA  0.5013f

float hpPrevOut = 0.0f;    // High-pass previous output
float hpPrevIn  = 0.0f;    // High-pass previous input (raw ADC)
float lpOut     = 0.0f;    // Low-pass output = final filtered ECG

// ======================== BASELINE TRACKING ==========================
// Moving average of FILTERED signal over 800ms window (200 samples).
// Long enough that R-peaks (~80-120ms wide) don't get absorbed.
// This tracks slow respiratory modulation for peak-relative detection.
#define BASELINE_WINDOW   200
float baselineHistory[BASELINE_WINDOW];
int   baselineIndex = 0;
bool  baselineFull  = false;
float baselineSum   = 0.0f;
float ecgBaseline   = 0.0f;

// ======================== R-PEAK DETECTION ===========================
// Working on FILTERED signal (centered ~0, much smaller amplitude).
// Typical filtered R-peak amplitude:
//   Limb lead:  30-200 ADC units
//   Chest lead: 15-100 ADC units
#define REFRACTORY_MS     300    // Min 300ms between beats (max ~200 BPM)
#define INITIAL_THRESHOLD 40.0f  // Starting threshold for filtered signal
#define MIN_THRESHOLD     15.0f  // Floor - catches even weak chest signals
#define MIN_PEAK_AMP      20.0f  // Minimum filtered peak height to accept

// Adaptive: only updates on CONFIRMED peaks (noise can't drag it down)
#define ADAPT_ALPHA       0.15f  // EMA speed for tracking peak heights
#define ADAPT_FRACTION    0.40f  // Threshold = 40% of average peak height

float adaptiveThreshold = INITIAL_THRESHOLD;
unsigned long lastRPeakTime = 0;
float peakValue     = 0.0f;     // Track peak amplitude within current beat
float peakFiltered  = 0.0f;     // Filtered peak value
bool  inBeat        = false;

// ======================== HEART RATE =================================
#define HR_HISTORY_SIZE   12
float hrHistory[HR_HISTORY_SIZE];
int   hrHistIndex = 0;
int   hrHistCount = 0;

#define HR_MEDIAN_SIZE    5
float hrMedianBuf[HR_MEDIAN_SIZE];

float instantBPM  = 0.0f;
float smoothedHR  = 0.0f;
bool  hrInitialized = false;

#define HR_EMA_ALPHA      0.15f
#define HR_OUTLIER_PCT    0.35f

// ======================== RESPIRATORY RATE (EDR) =====================
#define RR_BUFFER_SIZE    30
#define RR_MIN_SAMPLES    12

float rrAmplitudes[RR_BUFFER_SIZE];
unsigned long rrTimes[RR_BUFFER_SIZE];
int   rrBufIndex = 0;
int   rrBufCount = 0;

float respiratoryRate = 0.0f;
float smoothedRR      = 0.0f;
bool  rrInitialized   = false;
unsigned long lastRRCalcTime = 0;
#define RR_CALC_INTERVAL_MS 3000

#define RR_EMA_ALPHA      0.12f
#define RR_OUTLIER_PCT    0.40f

// ======================== SIGNAL QUALITY =============================
int   sampleCount    = 0;
int   leadsOffCount  = 0;
int   rPeakDetections = 0;
unsigned long statsStartTime = 0;
#define STATS_INTERVAL_MS 2000

// Noise estimator: EMA of consecutive filtered-sample differences
float noiseEstimate = 0.0f;
#define NOISE_EMA_ALPHA   0.01f

// Signal presence: is there ANY ECG-like activity?
float signalPower   = 0.0f;
#define SIGNAL_EMA_ALPHA  0.005f

// ======================== HRV ========================================
#define HRV_BUFFER_SIZE   10
float rrIntervals[HRV_BUFFER_SIZE];
int   hrvBufIndex = 0;
int   hrvBufCount = 0;

// Debug/display
float lastConfirmedPeakAmp = 0.0f;

// =====================================================================
//  UTILITY: Median of float array
// =====================================================================
float medianOfArray(float arr[], int count) {
  if (count == 0) return 0.0f;
  if (count == 1) return arr[0];

  float sorted[HR_MEDIAN_SIZE];
  int n = min(count, HR_MEDIAN_SIZE);
  for (int i = 0; i < n; i++) sorted[i] = arr[i];
  for (int i = 1; i < n; i++) {
    float key = sorted[i];
    int j = i - 1;
    while (j >= 0 && sorted[j] > key) {
      sorted[j + 1] = sorted[j];
      j--;
    }
    sorted[j + 1] = key;
  }
  return (n % 2 == 1) ? sorted[n / 2]
                       : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0f;
}

// =====================================================================
//  UTILITY: RMSSD for HRV
// =====================================================================
float calculateRMSSD() {
  int count = min(hrvBufCount, HRV_BUFFER_SIZE);
  if (count < 4) return 0.0f;

  float sumSqDiff = 0.0f;
  int pairs = 0;
  for (int i = 1; i < count; i++) {
    int prev = (i - 1 + HRV_BUFFER_SIZE) % HRV_BUFFER_SIZE;
    float diff = rrIntervals[i] - rrIntervals[prev];
    sumSqDiff += diff * diff;
    pairs++;
  }
  return (pairs > 0) ? sqrtf(sumSqDiff / pairs) : 0.0f;
}

// =====================================================================
//  RESPIRATORY RATE (zero-crossing of R-peak amplitude envelope)
// =====================================================================
float calculateRespiratoryRate() {
  int sCount = min(rrBufCount, RR_BUFFER_SIZE);
  if (sCount < RR_MIN_SAMPLES) return respiratoryRate;

  float sum = 0.0f;
  for (int i = 0; i < sCount; i++) sum += rrAmplitudes[i];
  float meanAmp = sum / sCount;

  int zeroCrossings = 0;
  bool aboveMean = (rrAmplitudes[0] > meanAmp);
  for (int i = 1; i < sCount; i++) {
    bool currentAbove = (rrAmplitudes[i] > meanAmp);
    if (currentAbove != aboveMean) {
      zeroCrossings++;
      aboveMean = currentAbove;
    }
  }

  float breathsInBuffer = (float)zeroCrossings / 2.0f;

  int oldest = (rrBufIndex - sCount + RR_BUFFER_SIZE) % RR_BUFFER_SIZE;
  int newest = (rrBufIndex - 1 + RR_BUFFER_SIZE) % RR_BUFFER_SIZE;
  float bufDurSec = 0.0f;
  if (rrTimes[newest] > rrTimes[oldest] && rrTimes[oldest] > 0) {
    bufDurSec = (float)(rrTimes[newest] - rrTimes[oldest]) / 1000.0f;
  } else {
    bufDurSec = sCount * (60.0f / max(smoothedHR, 50.0f));
  }
  if (bufDurSec <= 0) return respiratoryRate;

  float rr = (breathsInBuffer / bufDurSec) * 60.0f;
  if (rr < 6.0f || rr > 45.0f) return respiratoryRate;
  return rr;
}

// =====================================================================
//  SETUP
// =====================================================================
void setup() {
  Serial.begin(115200);
  delay(2000);

  pinMode(ECG_LO_PLUS, INPUT);
  pinMode(ECG_LO_MINUS, INPUT);
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);

  // Pre-fill baseline with 0 (filtered signal centers around 0)
  for (int i = 0; i < BASELINE_WINDOW; i++) {
    baselineHistory[i] = 0.0f;
  }

  // Prime the filters with a few readings to avoid startup transient
  for (int i = 0; i < 50; i++) {
    float raw = (float)analogRead(ECG_PIN);
    float hpOut = HP_ALPHA * (hpPrevOut + raw - hpPrevIn);
    hpPrevIn = raw;
    hpPrevOut = hpOut;
    lpOut = LP_ALPHA * hpOut + (1.0f - LP_ALPHA) * lpOut;
    delayMicroseconds(SAMPLE_INTERVAL_US);
  }

  if (!PLOTTER_MODE) {
    Serial.println("\n=== AD8232 ECG Diagnostic Test v2.0 ===");
    Serial.println("Pins:  ECG_OUT=GPIO34, LO+=GPIO25, LO-=GPIO26");
    Serial.println("Rate:  250 Hz  |  Filter: 0.5-40 Hz bandpass (IIR)");
    Serial.printf("Threshold: adaptive (start=%.0f, floor=%.0f, min_peak=%.0f)\n",
                  INITIAL_THRESHOLD, MIN_THRESHOLD, MIN_PEAK_AMP);
    Serial.println("\nTip: Try LIMB placement first (wrists + ankle) for strongest signal.");
    Serial.println("     Clean skin with alcohol wipe. Press pads firmly. Stay still.\n");
    Serial.println("Time(s)\tHR(bpm)\tRR(brpm)\tFiltered\tBaseline\tThresh\tPeakAmp\tNoise\tSigPwr\tQuality\t\tHRV(ms)");
    Serial.println("-------\t-------\t--------\t--------\t--------\t------\t-------\t-----\t------\t-------\t\t-------");
  }

  statsStartTime = millis();
  lastSampleMicros = micros();
}

// =====================================================================
//  MAIN LOOP - 250 Hz ECG sampling with digital bandpass filter
// =====================================================================
void loop() {
  unsigned long nowMicros = micros();
  if ((nowMicros - lastSampleMicros) < SAMPLE_INTERVAL_US) return;
  lastSampleMicros = nowMicros;

  unsigned long now = millis();

  // === Leads-off check ===
  bool leadsOff = (digitalRead(ECG_LO_PLUS) == HIGH) ||
                  (digitalRead(ECG_LO_MINUS) == HIGH);

  if (leadsOff) {
    leadsOffCount++;
    sampleCount++;

    if (inBeat) { inBeat = false; peakValue = 0; }

    if (PLOTTER_MODE) {
      // Output 0 for all channels (same Y-scale as filtered -> no ghost traces)
      Serial.println("0\t0");
    }
    return;
  }

  // === Read raw ADC ===
  float rawADC = (float)analogRead(ECG_PIN);
  sampleCount++;

  // === IIR HIGH-PASS FILTER (0.5 Hz) - removes DC offset & baseline wander ===
  // y[n] = alpha * (y[n-1] + x[n] - x[n-1])
  float hpOut = HP_ALPHA * (hpPrevOut + rawADC - hpPrevIn);
  hpPrevIn  = rawADC;
  hpPrevOut = hpOut;

  // === IIR LOW-PASS FILTER (40 Hz) - removes HF noise ===
  // y[n] = alpha * x[n] + (1-alpha) * y[n-1]
  lpOut = LP_ALPHA * hpOut + (1.0f - LP_ALPHA) * lpOut;

  // filtered = final bandpass-filtered ECG (centered ~0)
  float filtered = lpOut;

  // === Update baseline (slow moving average of filtered signal, 800ms) ===
  baselineSum -= baselineHistory[baselineIndex];
  baselineHistory[baselineIndex] = filtered;
  baselineSum += filtered;
  baselineIndex = (baselineIndex + 1) % BASELINE_WINDOW;
  if (!baselineFull && baselineIndex == 0) baselineFull = true;

  int bCount = baselineFull ? BASELINE_WINDOW : max(baselineIndex, 1);
  ecgBaseline = baselineSum / bCount;

  // Height above baseline (for peak detection)
  float ecgHeight = filtered - ecgBaseline;

  // === Noise estimation ===
  static float prevFiltered = 0.0f;
  float noiseDiff = fabsf(filtered - prevFiltered);
  noiseEstimate = NOISE_EMA_ALPHA * noiseDiff + (1.0f - NOISE_EMA_ALPHA) * noiseEstimate;
  prevFiltered = filtered;

  // === Signal power (is there ANY signal at all?) ===
  signalPower = SIGNAL_EMA_ALPHA * (filtered * filtered)
              + (1.0f - SIGNAL_EMA_ALPHA) * signalPower;

  // === Adaptive threshold ===
  float currentThreshold = max(adaptiveThreshold * ADAPT_FRACTION, MIN_THRESHOLD);

  // === R-peak detection on filtered signal ===
  bool pastRefractory = (now - lastRPeakTime) > REFRACTORY_MS;

  if (ecgHeight > currentThreshold && pastRefractory) {
    if (!inBeat) {
      inBeat = true;
      peakFiltered = filtered;
      peakValue = ecgHeight;
    } else {
      if (ecgHeight > peakValue) {
        peakValue = ecgHeight;
        peakFiltered = filtered;
      }
    }
  } else if (inBeat && ecgHeight < currentThreshold * 0.5f) {
    // Exiting R-peak region - validate
    inBeat = false;
    float peakAmplitude = peakValue;

    if (peakAmplitude < MIN_PEAK_AMP) {
      peakValue = 0;
      return;
    }

    lastConfirmedPeakAmp = peakAmplitude;

    // Update adaptive threshold (ONLY on confirmed peaks)
    adaptiveThreshold = ADAPT_ALPHA * peakAmplitude
                      + (1.0f - ADAPT_ALPHA) * adaptiveThreshold;

    rPeakDetections++;

    // Store for respiratory rate
    rrAmplitudes[rrBufIndex] = peakAmplitude;
    rrTimes[rrBufIndex] = now;
    rrBufIndex = (rrBufIndex + 1) % RR_BUFFER_SIZE;
    if (rrBufCount < RR_BUFFER_SIZE + 1) rrBufCount++;

    // === Heart Rate from R-R interval ===
    if (lastRPeakTime > 0) {
      long rrInterval = now - lastRPeakTime;

      if (rrInterval > 300 && rrInterval < 2000) {
        instantBPM = 60000.0f / (float)rrInterval;

        hrHistory[hrHistIndex] = instantBPM;
        hrHistIndex = (hrHistIndex + 1) % HR_HISTORY_SIZE;
        if (hrHistCount < HR_HISTORY_SIZE) hrHistCount++;

        int medCount = min(hrHistCount, HR_MEDIAN_SIZE);
        for (int i = 0; i < medCount; i++) {
          int idx = (hrHistIndex - 1 - i + HR_HISTORY_SIZE) % HR_HISTORY_SIZE;
          hrMedianBuf[i] = hrHistory[idx];
        }
        float medianHR = medianOfArray(hrMedianBuf, medCount);

        if (hrInitialized &&
            fabsf(medianHR - smoothedHR) / smoothedHR > HR_OUTLIER_PCT) {
          // Reject outlier
        } else {
          smoothedHR = hrInitialized
            ? (HR_EMA_ALPHA * medianHR + (1.0f - HR_EMA_ALPHA) * smoothedHR)
            : medianHR;
          hrInitialized = true;
        }

        rrIntervals[hrvBufIndex] = (float)rrInterval;
        hrvBufIndex = (hrvBufIndex + 1) % HRV_BUFFER_SIZE;
        if (hrvBufCount < HRV_BUFFER_SIZE) hrvBufCount++;
      }
    }
    lastRPeakTime = now;
    peakValue = 0;
  }

  // === Timeout: reset if no beats for 5 seconds ===
  if (lastRPeakTime > 0 && (now - lastRPeakTime) > 5000) {
    smoothedHR = 0;
    hrInitialized = false;
    hrHistCount = 0;
    hrHistIndex = 0;
    instantBPM = 0;
    respiratoryRate = 0;
    smoothedRR = 0;
    rrInitialized = false;
    rrBufCount = 0;
    hrvBufCount = 0;
  }

  // === Respiratory Rate ===
  if ((now - lastRRCalcTime) >= RR_CALC_INTERVAL_MS && rrBufCount >= RR_MIN_SAMPLES) {
    float rawRR = calculateRespiratoryRate();
    if (rawRR > 0) {
      if (rrInitialized &&
          fabsf(rawRR - smoothedRR) / smoothedRR > RR_OUTLIER_PCT) {
        // Reject
      } else {
        respiratoryRate = rawRR;
        smoothedRR = rrInitialized
          ? (RR_EMA_ALPHA * rawRR + (1.0f - RR_EMA_ALPHA) * smoothedRR)
          : rawRR;
        rrInitialized = true;
      }
    }
    lastRRCalcTime = now;
  }

  // === OUTPUT ===
  if (PLOTTER_MODE) {
    // 2-channel output for Serial Plotter (both centered near 0):
    //   Channel 1: Filtered ECG (bandpass 0.5-40Hz, centered at 0)
    //   Channel 2: Detection threshold above baseline
    // Real ECG = sharp spikes ~1/second. Noise = random jitter.
    Serial.printf("%.1f\t%.1f\n", filtered, ecgBaseline + currentThreshold);
  } else {
    // Text diagnostic output
    if ((now - statsStartTime) >= STATS_INTERVAL_MS) {
      float leadsOffPct = (sampleCount > 0)
        ? ((float)leadsOffCount / sampleCount * 100.0f) : 0.0f;

      const char* quality;
      float sigRMS = sqrtf(signalPower);
      if (leadsOffPct > 20.0f) {
        quality = "LEADS_OFF";
      } else if (sigRMS < 3.0f) {
        quality = "NO_SIGNAL";
      } else if (noiseEstimate > 15.0f) {
        quality = "NOISY";
      } else if (noiseEstimate > 8.0f) {
        quality = "OK";
      } else if (hrInitialized) {
        quality = "GOOD";
      } else {
        quality = "CALIBRATING";
      }

      const char* hrLabel = "";
      if (smoothedHR > 0) {
        if (smoothedHR < 60) hrLabel = " [BRADY]";
        else if (smoothedHR > 100) hrLabel = " [TACHY]";
      }

      const char* rrLabel = "";
      if (smoothedRR > 0) {
        if (smoothedRR < 12) rrLabel = " [SLOW]";
        else if (smoothedRR > 20) rrLabel = " [FAST]";
      }

      float rmssd = calculateRMSSD();

      Serial.printf("%.1f\t%.0f%s\t%.1f%s\t\t%.1f\t\t%.1f\t\t%.0f\t%.0f\t%.1f\t%.1f\t%s\t\t%.0f\n",
                    (float)now / 1000.0f,
                    smoothedHR, hrLabel,
                    smoothedRR, rrLabel,
                    filtered,
                    ecgBaseline,
                    currentThreshold,
                    lastConfirmedPeakAmp,
                    noiseEstimate,
                    sigRMS,
                    quality,
                    rmssd);

      sampleCount = 0;
      leadsOffCount = 0;
      rPeakDetections = 0;
      statsStartTime = now;
    }
  }
}
