/*
 * MAX30102 Standalone Test — Raw Readings  v2.2
 * Prints IR, Red, SpO2, Heart Rate, R-ratio, and signal quality.
 * Open Serial Monitor at 115200 baud.
 *
 *  v2.2 CHANGELOG:
 *    - Added Perfusion Index (PI) check: AC/DC must be >= 0.4% on both
 *      channels. Below this, noise dominates the pulsatile signal and
 *      the R-ratio is meaningless. Typical PI for good contact: 1-5%.
 *    - Added R-ratio bounds [0.2, 1.0]: R > 1.0 means redAC > irAC,
 *      which is physiologically impossible for SpO2 > 85%. In a
 *      non-ICU prototype setting this always indicates artifact
 *      (excess finger pressure, motion, poor contact).
 *    - Print PI% column so the user can see signal quality and adjust
 *      finger pressure in real time (lighter = better).
 *
 *  v2.1: R-ratio median filter, max-min AC, buffer warm-up.
 */

#include <Wire.h>
#include "MAX30105.h"
#include "heartRate.h"

MAX30105 sensor;

// SpO2 buffer — 100 samples at 100Hz = 1 second of data
#define BUF_SIZE 100
long irBuf[BUF_SIZE], redBuf[BUF_SIZE];
int bufIdx = 0, sampleCount = 0;

// R-ratio history for stable SpO2 output
#define R_HIST_SIZE 10
float rHist[R_HIST_SIZE];
int rHistIdx = 0, rHistCount = 0;
float lastValidSpO2 = 0;

// Buffer warm-up after finger re-placement
#define MIN_FRESH_AFTER_REDETECT 50   // 0.5s of fresh data
int freshSamplesSinceDetect = 0;

// Signal quality thresholds
#define MIN_PERFUSION_INDEX  0.004f   // 0.4% — minimum AC/DC ratio for reliable SpO2
#define MIN_R_RATIO          0.2f     // R below 0.2 → SpO2 > 105% (impossible, noise)
#define MAX_R_RATIO          1.0f     // R above 1.0 → SpO2 < 85% (artifact in prototype use)

// HR tracking
float rates[10];
int rateIdx = 0, rateCount = 0;
unsigned long lastBeat = 0;

// ----------------------------------------------------------------
//  Helper: median of a small float array
// ----------------------------------------------------------------
float medianFloat(float arr[], int count) {
  if (count <= 0) return 0;
  float s[R_HIST_SIZE];
  for (int i = 0; i < count; i++) s[i] = arr[i];
  for (int i = 1; i < count; i++) {
    float key = s[i]; int j = i - 1;
    while (j >= 0 && s[j] > key) { s[j + 1] = s[j]; j--; }
    s[j + 1] = key;
  }
  return s[count / 2];
}

void setup() {
  Serial.begin(115200);
  Serial.println("\n=== MAX30102 Test v2.2 ===");
  Serial.println("TIP: Use LIGHT finger pressure. Heavy pressure compresses");
  Serial.println("     capillaries and destroys the pulsatile signal.\n");

  Wire.begin(21, 22);
  Wire.setClock(100000);
  delay(100);

  if (!sensor.begin(Wire, I2C_SPEED_STANDARD)) {
    Serial.println("ERROR: MAX30102 not found! Check wiring.");
    while (1) delay(1000);
  }

  sensor.setup(60, 4, 2, 100, 411, 4096);
  sensor.setPulseAmplitudeRed(0x1F);
  sensor.setPulseAmplitudeIR(0x1F);
  sensor.setPulseAmplitudeGreen(0);

  Serial.println("MAX30102 ready. Place finger on sensor.\n");
  Serial.println("IR\t\tRed\t\tHR\tSpO2\tR\tPI%\tQuality");
  Serial.println("--------\t--------\t----\t-----\t-----\t-----\t-------");
}

void loop() {
  sensor.check();

  while (sensor.available()) {
    long ir = sensor.getFIFOIR();
    long red = sensor.getFIFORed();

    bool finger = (ir > 50000);

    if (finger) {
      irBuf[bufIdx] = ir;
      redBuf[bufIdx] = red;
      bufIdx = (bufIdx + 1) % BUF_SIZE;
      if (sampleCount < BUF_SIZE) sampleCount++;
      freshSamplesSinceDetect++;

      if (checkForBeat(ir)) {
        unsigned long now = millis();
        if (lastBeat > 0) {
          long delta = now - lastBeat;
          if (delta > 300 && delta < 2000) {
            float bpm = 60000.0 / (float)delta;
            rates[rateIdx] = bpm;
            rateIdx = (rateIdx + 1) % 10;
            if (rateCount < 10) rateCount++;
          }
        }
        lastBeat = now;
      }
    } else {
      sampleCount = 0;
      bufIdx = 0;
      rateCount = 0;
      rateIdx = 0;
      lastBeat = 0;
      rHistCount = 0;
      rHistIdx = 0;
      lastValidSpO2 = 0;
      freshSamplesSinceDetect = 0;
    }

    sensor.nextSample();

    static int printCounter = 0;
    printCounter++;
    if (printCounter >= 50) {
      printCounter = 0;

      if (!finger) {
        Serial.println("No finger detected -- place finger on sensor");
        continue;
      }

      // Average HR
      float avgHR = 0;
      if (rateCount > 0) {
        float sum = 0;
        for (int i = 0; i < rateCount; i++) sum += rates[i];
        avgHR = sum / rateCount;
      }

      float spo2 = lastValidSpO2;
      float lastR = 0;
      float perfusionIndex = 0;
      const char* quality = "WAIT";

      if (sampleCount >= 25 && freshSamplesSinceDetect >= MIN_FRESH_AFTER_REDETECT) {
        // DC: mean of buffered samples
        long irSum = 0, redSum = 0;
        long irMax = irBuf[0], irMin = irBuf[0];
        long redMax = redBuf[0], redMin = redBuf[0];
        for (int i = 0; i < sampleCount; i++) {
          irSum += irBuf[i];
          redSum += redBuf[i];
          if (irBuf[i] > irMax) irMax = irBuf[i];
          if (irBuf[i] < irMin) irMin = irBuf[i];
          if (redBuf[i] > redMax) redMax = redBuf[i];
          if (redBuf[i] < redMin) redMin = redBuf[i];
        }
        float irDC = (float)irSum / sampleCount;
        float redDC = (float)redSum / sampleCount;
        float irAC = (float)(irMax - irMin);
        float redAC = (float)(redMax - redMin);

        // Perfusion Index = AC/DC (IR channel, which has stronger pulse)
        // This is the standard metric for signal quality in pulse oximetry.
        // PI < 0.4%: too weak (excess pressure, poor contact, low perfusion)
        // PI 0.4-1%: marginal, readings may be noisy
        // PI 1-5%:   good signal
        // PI > 5%:   excellent
        perfusionIndex = (irDC > 0) ? (irAC / irDC) : 0;

        if (perfusionIndex < MIN_PERFUSION_INDEX) {
          // Signal too weak — don't compute R, keep last valid SpO2
          quality = "LOW PI";
        } else if (irDC > 0 && redDC > 0 && irAC > 200 && redAC > 200) {
          float R = (redAC / redDC) / (irAC / irDC);
          lastR = R;

          // R-ratio bounds check: reject physiologically implausible values
          // R < 0.2 → SpO2 > 105% (impossible)
          // R > 1.0 → SpO2 < 85%, redAC > irAC (artifact for conscious patient)
          if (R < MIN_R_RATIO || R > MAX_R_RATIO) {
            quality = "BAD R";
            // Don't add to median buffer — would poison the history
          } else {
            rHist[rHistIdx] = R;
            rHistIdx = (rHistIdx + 1) % R_HIST_SIZE;
            if (rHistCount < R_HIST_SIZE) rHistCount++;

            float smoothR = medianFloat(rHist, rHistCount);
            spo2 = constrain(110.0 - 25.0 * smoothR, 70.0, 100.0);
            lastValidSpO2 = spo2;

            quality = (perfusionIndex >= 0.01) ? "GOOD" :
                      (perfusionIndex >= 0.004) ? "OK" : "LOW PI";
          }
        }
      }

      Serial.printf("%ld\t\t%ld\t\t%.0f\t%.1f\t%.3f\t%.2f\t%s\n",
                    ir, red, avgHR, spo2, lastR, perfusionIndex * 100.0, quality);
    }
  }
}
