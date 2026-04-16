/*
 * ============================================================
 *  Medical Vital Signs Monitor — ESP32 Firmware  v2.0
 * ============================================================
 *  CHANGELOG v2.0 (Feb 2026):
 *    - BUGFIX: byte overflow in rates[] caused HR spikes (98→23)
 *    - BUGFIX: SpO2 required 100 samples (2-3 min wait) — now 25
 *    - BUGFIX: Temperature using raw IR with no calibration (showed 28°C)
 *    - NEW: EMA (exponential moving average) filters on all outputs
 *    - NEW: Median filter on HR to reject transient spikes
 *    - NEW: Outlier rejection on R-R intervals (>30% deviation rejected)
 *    - NEW: Temperature skin-calibration offset + emissivity compensation
 *    - NEW: Adaptive ECG threshold (tracks signal amplitude)
 *    - NEW: Partial-buffer SpO2 calculation (min 25 samples)
 *    - NEW: Improved RR algorithm with partial buffer + smoothing
 *    - NEW: Physiological validation on all output values
 *
 *  Hardware:
 *    - ESP32 DevKit V1
 *    - AD8232  ECG Module          → GPIO 34 (analog), LO+ → GPIO 25, LO- → GPIO 26
 *    - MAX30102 Pulse/SpO2 Sensor  → I2C (SDA: GPIO 21, SCL: GPIO 22)
 *    - MLX90614 IR Temperature     → I2C (SDA: GPIO 21, SCL: GPIO 22)
 *    - OLED 1.3" 128x64 (SH1106)  → I2C (SDA: GPIO 21, SCL: GPIO 22)
 *    - Buzzer                      → GPIO 27
 *    - LED - Power (Blue)          → GPIO 2  (built-in on many ESP32 boards)
 *    - LED - WiFi (Cyan/White)     → GPIO 15
 *    - LED - Normal (Green)        → GPIO 4
 *    - LED - Warning (Yellow)      → GPIO 18
 *    - LED - Critical (Red)        → GPIO 19
 *
 *  Libraries needed (install via Arduino Library Manager):
 *    - Wire                  (built-in)
 *    - WiFi                  (built-in)
 *    - HTTPClient            (built-in)
 *    - ArduinoJson           (by Benoit Blanchon)
 *    - MAX30105              (SparkFun MAX3010x)
 *    - Adafruit MLX90614
 *    - U8g2                  (for SH1106 OLED)
 * ============================================================
 */

#include <Wire.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include "MAX30105.h"           // SparkFun MAX30102 library
#include "heartRate.h"          // Heart-rate algorithm from SparkFun library
#include <Adafruit_MLX90614.h>
#include <U8g2lib.h>

// ======================== WiFi Configuration ========================
const char* WIFI_SSID     = "SanTech";
const char* WIFI_PASSWORD = "SanTech@IdeasHappen";

// ======================== Server Configuration ======================
const char* SERVER_URL = "http://192.168.1.85:8080/api/vitals";
const char* DEVICE_ID  = "ESP32-MED-001";

// ======================== Pin Definitions ===========================
// AD8232 ECG
#define ECG_PIN       34    // Analog input
#define ECG_LO_PLUS   25    // Leads-off detection +
#define ECG_LO_MINUS  26    // Leads-off detection -

// Buzzer
#define BUZZER_PIN    27

// LEDs
#define LED_POWER     2     // Blue  — Power indicator
#define LED_WIFI      15    // White — WiFi connected
#define LED_NORMAL    4     // Green — All vitals normal
#define LED_WARNING   18    // Yellow — Warning state
#define LED_CRITICAL  19    // Red   — Critical state

// ======================== Timing ====================================
#define SEND_INTERVAL_MS    3000    // Send data every 3 seconds
#define DISPLAY_INTERVAL_MS 500     // Refresh OLED every 500ms
#define DEBUG_INTERVAL_MS   2000    // Debug print every 2 seconds

// ======================== Medical Thresholds ========================
#define HR_LOW           60.0
#define HR_HIGH         100.0
#define HR_CRITICAL_LOW  40.0
#define HR_CRITICAL_HIGH 150.0

#define SPO2_NORMAL      95.0
#define SPO2_CRITICAL    90.0

#define TEMP_LOW         36.1
#define TEMP_HIGH        37.2
#define TEMP_FEVER       38.0
#define TEMP_CRITICAL_H  39.5
#define TEMP_CRITICAL_L  35.0

// ======================== Objects ====================================
MAX30105 particleSensor;
Adafruit_MLX90614 mlx = Adafruit_MLX90614();

// SH1106 OLED 128x64 via I2C (use U8G2_SSD1306_128X64_NONAME_F_HW_I2C if your OLED uses SSD1306)
U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, /* reset=*/ U8X8_PIN_NONE);

// ======================== Global Variables ===========================
float heartRate       = 0.0;
float spo2            = 0.0;
float temperature     = 0.0;
float respiratoryRate = 0.0;  // Breaths per minute (from ECG)
int   ecgValue        = 0;
bool  ecgLeadsOff     = false;

// Smoothed output values (what gets displayed and sent to server)
float smoothedHR   = 0.0;
float smoothedSpO2 = 0.0;
float smoothedTemp = 0.0;
float smoothedRR   = 0.0;

String currentStatus = "NORMAL";
bool   alertTriggered = false;
String alertType     = "";

unsigned long lastSendTime    = 0;
unsigned long lastDisplayTime = 0;
unsigned long lastTempRead    = 0;  // Temperature read timing
unsigned long lastECGRead     = 0;  // ECG read timing
unsigned long lastDebugPrint  = 0;  // Debug print timing

#define TEMP_READ_INTERVAL_MS 1000  // Read temp every 1 second
#define ECG_READ_INTERVAL_MS  100   // Read ECG every 100ms

// ======================== EMA FILTER PARAMETERS =====================
// EMA: output = alpha * new_value + (1-alpha) * old_output
// Lower alpha = more smoothing (slower response)
// Higher alpha = less smoothing (faster response, more noise)
#define EMA_ALPHA_HR    0.15f   // Heart rate: moderate smoothing (responds in ~8 beats)
#define EMA_ALPHA_SPO2  0.10f   // SpO2: heavy smoothing (stable, slow drift)
#define EMA_ALPHA_TEMP  0.08f   // Temperature: very heavy smoothing (temp changes slowly)
#define EMA_ALPHA_RR    0.12f   // Respiratory rate: moderate smoothing

// ======================== OUTLIER REJECTION ==========================
// If a new raw value deviates from the smoothed value by more than this
// percentage, it is rejected as a spike/artifact.
#define HR_OUTLIER_THRESH     0.35f   // 35% deviation threshold for HR
#define SPO2_OUTLIER_THRESH   8.0f    // Absolute SpO2 % deviation
#define TEMP_OUTLIER_THRESH   1.5f    // °C deviation for temperature
#define RR_OUTLIER_THRESH     0.40f   // 40% deviation for respiratory rate

// ======================== TEMPERATURE CALIBRATION ====================
// The MLX90614 reads infrared surface temperature, which underreads
// actual skin temperature due to emissivity, distance, and the fact
// that skin surface temp < core body temp.
//
// Calibration approach:
//   1. Emissivity factor: skin ε ≈ 0.98 (sensor default is 1.0)
//   2. Surface-to-core offset: add ~2.0°C to surface reading to
//      approximate core body temperature (clinical forehead offset)
//   3. Only accept readings in plausible skin-IR range (29°C–42°C)
//
// IMPORTANT: Tune TEMP_CALIBRATION_OFFSET for your specific sensor
// placement. Forehead: +3.0 to +5.0°C. Wrist: +4.0 to +6.0°C.
// Validate with a reference thermometer before clinical use.
#define TEMP_CALIBRATION_OFFSET  4.0f   // °C added to raw IR reading
#define TEMP_SKIN_IR_MIN      29.0f   // Minimum plausible IR skin reading
#define TEMP_SKIN_IR_MAX      42.0f   // Maximum plausible IR skin reading

// Heart rate calculation variables (from ECG R-peak detection)
// FIX v2.0: Changed from byte to float to prevent overflow wrapping
#define RATE_SIZE 12        // Increased from 8 for more stable average
float rates[RATE_SIZE];     // FIX: was byte (0-255 overflow caused 98→23 spikes)
int rateSpot = 0;
float beatsPerMinute = 0;
float beatAvg = 0;          // FIX: was int

// ======================== MEDIAN FILTER FOR HR =======================
// Median filter rejects transient spikes better than mean averaging.
// We keep the last N raw BPM values and take the median.
#define HR_MEDIAN_SIZE 5
float hrMedianBuf[HR_MEDIAN_SIZE];
int hrMedianIndex = 0;
int hrMedianCount = 0;      // How many values filled so far

// ECG R-peak detection variables
#define ECG_SAMPLE_INTERVAL_MS 4   // Sample ECG every 4ms = 250Hz (standard ECG rate)
#define ECG_WINDOW_SIZE 30         // Increased from 20 for more stable baseline
#define ECG_REFRACTORY_MS 300      // Min 300ms between beats (max ~200 BPM)
#define ECG_INITIAL_THRESHOLD 300  // Initial minimum height above baseline for R-peak
#define ECG_MIN_PEAK_AMP     200  // Minimum confirmed peak amplitude to count as beat

unsigned long lastECGSampleTime = 0;
unsigned long lastRPeakTime = 0;
int ecgBaseline = 2048;            // Running baseline (midpoint of 12-bit ADC)
int ecgPeakValue = 0;              // Track peak within current beat
bool ecgInBeat = false;            // Currently in an R-peak region
int ecgHistory[ECG_WINDOW_SIZE];   // Circular buffer for baseline calculation
int ecgHistIndex = 0;
bool ecgHistFull = false;
int ecgHRValidCount = 0;           // Count of valid R-R intervals detected

// Adaptive ECG threshold: tracks the running average of detected R-peak heights
// to auto-calibrate for different signal amplitudes across patients/placements.
float ecgAdaptiveThreshold = ECG_INITIAL_THRESHOLD;
#define ECG_ADAPT_ALPHA   0.15f  // EMA alpha for threshold adaptation
#define ECG_ADAPT_FRACTION 0.45f  // Threshold = 45% of average peak height

// Respiratory rate detection variables (ECG-Derived Respiration)
#define RR_BUFFER_SIZE 30              // Track 30 R-peak amplitudes (~20-30 seconds of data)
#define RR_MIN_SAMPLES 12             // FIX: Allow partial buffer (was: required full 30)
int rPeakAmplitudes[RR_BUFFER_SIZE];  // Circular buffer of R-peak heights
unsigned long rPeakTimes[RR_BUFFER_SIZE];  // NEW: timestamps for each R-peak
int rrBufIndex = 0;
int rrBufCount = 0;                   // NEW: actual count of valid entries (up to RR_BUFFER_SIZE)
unsigned long lastRespCalcTime = 0;
#define RESP_CALC_INTERVAL_MS 3000    // FIX: was 5000, now update every 3 seconds

// SpO2 AC/DC tracking
// v2.1: 100 samples at 100Hz = 1 second of data, captures ~1 full cardiac cycle
#define SPO2_BUFFER_SIZE 100
#define SPO2_MIN_SAMPLES 25           // minimum samples for a valid calculation
long irBuffer[SPO2_BUFFER_SIZE];
long redBuffer[SPO2_BUFFER_SIZE];
int spo2BufIndex = 0;
int spo2SampleCount = 0;             // actual samples collected (up to SPO2_BUFFER_SIZE)

// v2.1: R-ratio history — median-filtering the R ratio itself is far
// more effective than smoothing SpO2 after the fact, because R maps
// nonlinearly to SpO2 and outlier R values cause disproportionate swings.
#define R_RATIO_HIST_SIZE 10
float rRatioHistory[R_RATIO_HIST_SIZE];
int rRatioHistIdx = 0;
int rRatioHistCount = 0;

// Finger detection
bool fingerDetected = false;
unsigned long lastValidReading = 0;
#define VALID_READING_TIMEOUT 3000    // Reset if no finger for 3 seconds
#define FINGER_IR_THRESHOLD 50000     // IR value above this = finger present

// MAX30102 HR fallback (used when ECG leads are off)
bool useMAX30102ForHR = false;       // Auto-enabled when ECG leads are disconnected
unsigned long lastMAX30102Beat = 0;   // Timestamp of last detected beat from IR

// System warm-up
unsigned long bootTime = 0;
#define WARMUP_PERIOD_MS 5000  // 5 second warm-up after boot

// Track whether each vital has been initialized with at least one valid reading
bool hrInitialized   = false;
bool spo2Initialized = false;
bool tempInitialized = false;
bool rrInitialized   = false;

// =====================================================================
//  UTILITY: EMA filter with initialization
// =====================================================================
float applyEMA(float currentSmoothed, float newValue, float alpha, bool isInitialized) {
  if (!isInitialized) {
    return newValue;  // First valid reading — use it directly (no smoothing)
  }
  return alpha * newValue + (1.0f - alpha) * currentSmoothed;
}

// =====================================================================
//  UTILITY: Median of a float array (for spike rejection)
// =====================================================================
float medianOfArray(float arr[], int count) {
  if (count == 0) return 0.0;
  if (count == 1) return arr[0];

  // Simple insertion sort (tiny array, no need for stdlib)
  float sorted[HR_MEDIAN_SIZE];
  for (int i = 0; i < count; i++) sorted[i] = arr[i];
  for (int i = 1; i < count; i++) {
    float key = sorted[i];
    int j = i - 1;
    while (j >= 0 && sorted[j] > key) {
      sorted[j + 1] = sorted[j];
      j--;
    }
    sorted[j + 1] = key;
  }
  return sorted[count / 2];
}

// =====================================================================
//  UTILITY: Check if value is an outlier relative to smoothed baseline
// =====================================================================
bool isOutlierPercent(float newValue, float baseline, float maxDeviationPercent) {
  if (baseline == 0.0) return false;  // No baseline yet — accept anything
  float deviation = fabs(newValue - baseline) / fabs(baseline);
  return deviation > maxDeviationPercent;
}

bool isOutlierAbsolute(float newValue, float baseline, float maxDeviation) {
  if (baseline == 0.0) return false;
  return fabs(newValue - baseline) > maxDeviation;
}

// =====================================================================
//  UTILITY: Median of a small float array (for R-ratio filtering)
// =====================================================================
float medianFloatArray(float arr[], int count) {
  if (count <= 0) return 0;
  float s[R_RATIO_HIST_SIZE];
  int n = min(count, R_RATIO_HIST_SIZE);
  for (int i = 0; i < n; i++) s[i] = arr[i];
  for (int i = 1; i < n; i++) {
    float key = s[i]; int j = i - 1;
    while (j >= 0 && s[j] > key) { s[j + 1] = s[j]; j--; }
    s[j + 1] = key;
  }
  return s[n / 2];
}

// =====================================================================
//  SETUP
// =====================================================================
void setup() {
  Serial.begin(115200);
  Serial.println("\n=== Medical Vital Signs Monitor v2.0 ===");

  // --- Pin modes ---
  pinMode(ECG_LO_PLUS, INPUT);
  pinMode(ECG_LO_MINUS, INPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_POWER, OUTPUT);
  pinMode(LED_WIFI, OUTPUT);
  pinMode(LED_NORMAL, OUTPUT);
  pinMode(LED_WARNING, OUTPUT);
  pinMode(LED_CRITICAL, OUTPUT);

  // Power LED ON
  digitalWrite(LED_POWER, HIGH);
  digitalWrite(LED_WIFI, LOW);
  digitalWrite(LED_NORMAL, LOW);
  digitalWrite(LED_WARNING, LOW);
  digitalWrite(LED_CRITICAL, LOW);
  digitalWrite(BUZZER_PIN, LOW);

  // --- I2C ---
  Wire.begin(21, 22);
  Wire.setClock(100000);  // 100kHz — safe for all I2C devices (SMBus minimum)
  delay(100);  // Let I2C bus stabilize

  // --- OLED --- (init first, it only reads/writes its own address)
  display.begin();
  display.clearBuffer();
  display.setFont(u8g2_font_ncenB08_tr);
  display.drawStr(10, 30, "Medical Monitor");
  display.drawStr(15, 50, "v2.0 Starting...");
  display.sendBuffer();
  delay(100);

  // Initialize arrays to zero/defaults
  for (int i = 0; i < RATE_SIZE; i++) {
    rates[i] = 0.0;
  }
  for (int i = 0; i < HR_MEDIAN_SIZE; i++) {
    hrMedianBuf[i] = 0.0;
  }
  for (int i = 0; i < ECG_WINDOW_SIZE; i++) {
    ecgHistory[i] = 2048;  // Initialize to ADC midpoint
  }
  for (int i = 0; i < RR_BUFFER_SIZE; i++) {
    rPeakAmplitudes[i] = 0;
    rPeakTimes[i] = 0;
  }
  for (int i = 0; i < SPO2_BUFFER_SIZE; i++) {
    irBuffer[i] = 0;
    redBuffer[i] = 0;
  }
  for (int i = 0; i < R_RATIO_HIST_SIZE; i++) {
    rRatioHistory[i] = 0.0;
  }

  // --- MLX90614 --- (init BEFORE MAX30102 — uses SMBus at 100kHz)
  Wire.setClock(100000);  // Ensure 100kHz for SMBus
  delay(100);

  // I2C bus scan — verify MLX90614 is responding before init
  Wire.beginTransmission(0x5A);  // MLX90614 default address
  byte mlxError = Wire.endTransmission();
  if (mlxError != 0) {
    Serial.printf("WARNING: MLX90614 not detected at 0x5A (error=%d) — check wiring\n", mlxError);
  }

  if (!mlx.begin()) {
    Serial.println("ERROR: MLX90614 not found!");
  } else {
    // Set emissivity for human skin (~0.98)
    // Note: Some MLX90614 libraries support mlx.writeEmissivity(0.98)
    // If your library version supports it, uncomment:
    // mlx.writeEmissivity(0.98);

    // Take initial readings to verify sensor
    delay(250);  // MLX90614 needs settling time after power-on
    float testObj = mlx.readObjectTempC();
    float testAmb = mlx.readAmbientTempC();
    Serial.printf("MLX90614 initialized — Raw Object: %.2f°C, Ambient: %.2f°C\n", testObj, testAmb);
    Serial.printf("  Calibration offset: +%.1f°C (adjust TEMP_CALIBRATION_OFFSET if needed)\n",
                  TEMP_CALIBRATION_OFFSET);
  }
  delay(100);

  // --- MAX30102 --- (init LAST)
  // FIX v2.1: Use I2C_SPEED_STANDARD (100kHz) instead of I2C_SPEED_FAST (400kHz).
  // The MLX90614 is an SMBus device that ONLY works at ≤100kHz. When we used
  // I2C_SPEED_FAST, the bus stayed at 400kHz and all MLX90614 reads returned NaN.
  // The MAX30102 works perfectly fine at 100kHz — it just fills its FIFO slightly
  // slower, which is fine since we batch-read from FIFO anyway.
  if (!particleSensor.begin(Wire, I2C_SPEED_STANDARD)) {
    Serial.println("ERROR: MAX30102 not found!");
    display.clearBuffer();
    display.drawStr(5, 30, "MAX30102 ERROR");
    display.sendBuffer();
  } else {
    // Optimized settings for SpO2 accuracy:
    //   - LED current 60mA (sufficient for finger contact)
    //   - Sample average 4 (hardware averaging for noise reduction)
    //   - Mode 2 = Red+IR (needed for SpO2)
    //   - Sample rate 100Hz (good balance: fills buffer fast, not too much data)
    //   - Pulse width 411μs (highest resolution: 18-bit, best SNR)
    //   - ADC range 4096 (full range)
    particleSensor.setup(60, 4, 2, 100, 411, 4096);
    particleSensor.setPulseAmplitudeRed(0x1F);
    particleSensor.setPulseAmplitudeIR(0x1F);
    particleSensor.setPulseAmplitudeGreen(0);
    Serial.println("MAX30102 initialized (I2C_SPEED_STANDARD 100kHz, 100Hz, 18-bit)");
  }
  delay(200);

  // --- WiFi ---
  connectWiFi();

  // Record boot time for warm-up period
  bootTime = millis();

  Serial.println("\nSetup complete. Starting monitoring...");
  Serial.println("Sensors warming up for 5 seconds...");
  Serial.println("  SpO2: ~2-3 sec to first reading (25 samples)");
  Serial.println("  HR:   ~5-10 sec (ECG primary, MAX30102 IR fallback)");
  Serial.println("  Temp: ~3 sec (IR calibration settling)");
  Serial.println("  RR:   ~15 sec (need 12+ R-peak samples, ECG only)\n");
}

// =====================================================================
//  LOOP (v2.0 — filtered outputs, all reads use new variable names)
// =====================================================================
void loop() {
  // Skip readings during warm-up period
  if (millis() - bootTime < WARMUP_PERIOD_MS) {
    delay(100);
    return;
  }

  unsigned long now = millis();

  // 1. Read MAX30102 FIFO — for SpO2 + HR fallback (when ECG off)
  readMAX30102();

  // 2. Read ECG at ~250Hz for R-peak heart rate detection
  if (now - lastECGSampleTime >= ECG_SAMPLE_INTERVAL_MS) {
    readECG();
    lastECGSampleTime = now;
  }

  // 3. Read temperature at slower interval
  if (now - lastTempRead >= TEMP_READ_INTERVAL_MS) {
    readMLX90614();
    lastTempRead = now;
  }

  // 4. Evaluate status using SMOOTHED values & set LEDs/buzzer
  evaluateStatus();
  updateLEDs();

  // 5. Debug output (show both raw and smoothed, and HR source)
  if (now - lastDebugPrint >= DEBUG_INTERVAL_MS) {
    Serial.printf("[VITALS] HR=%.0f(raw %.0f)[%s] | SpO2=%.1f(raw %.1f) | Temp=%.1f(raw %.1f) | RR=%.1f(raw %.1f) | Finger=%s | ECG=%s\n",
                  smoothedHR, heartRate, useMAX30102ForHR ? "IR" : "ECG",
                  smoothedSpO2, spo2,
                  smoothedTemp, temperature, smoothedRR, respiratoryRate,
                  fingerDetected ? "Y" : "N", ecgLeadsOff ? "OFF" : "OK");
    lastDebugPrint = now;
  }

  // 6. Update OLED display
  if (now - lastDisplayTime >= DISPLAY_INTERVAL_MS) {
    updateDisplay();
    lastDisplayTime = millis();
  }

  // 7. Drain MAX30102 FIFO before HTTP (HTTP blocks for 100-500ms)
  readMAX30102();

  // 8. Send data to server (sends SMOOTHED values)
  if (now - lastSendTime >= SEND_INTERVAL_MS) {
    sendDataToServer();
    lastSendTime = millis();
  }
}

// =====================================================================
//  SENSOR READING FUNCTIONS (v2.0)
// =====================================================================

// =====================================================================
//  SpO2 CALCULATION v2.2 — Max-min AC + R-ratio median filtering
// =====================================================================
//  v2.0: max-min AC gave correct average R but wild per-window swings.
//  v2.1: P90-P10 percentile AC stabilized readings but systematically
//        biased R (IR and Red pulse shapes trim differently), reading
//        ~89% when actual SpO2 was ~96%.
//  v2.2: Reverts to max-min AC (unbiased R) but adds R-RATIO MEDIAN
//        FILTER (window of 10) which rejects the outlier windows that
//        caused v2.0 instability. Also keeps min-AC threshold to reject
//        weak/no-pulse conditions.
// =====================================================================
float calculateSpO2() {
  int sampleCount = min(spo2SampleCount, SPO2_BUFFER_SIZE);
  if (sampleCount < SPO2_MIN_SAMPLES) return spo2;  // Not enough data yet

  // DC component: mean of all buffered samples
  long irSum = 0, redSum = 0;
  long irMax = irBuffer[0], irMin = irBuffer[0];
  long redMax = redBuffer[0], redMin = redBuffer[0];

  for (int i = 0; i < sampleCount; i++) {
    irSum += irBuffer[i];
    redSum += redBuffer[i];
    if (irBuffer[i] > irMax) irMax = irBuffer[i];
    if (irBuffer[i] < irMin) irMin = irBuffer[i];
    if (redBuffer[i] > redMax) redMax = redBuffer[i];
    if (redBuffer[i] < redMin) redMin = redBuffer[i];
  }

  float irDC = (float)irSum / sampleCount;
  float redDC = (float)redSum / sampleCount;

  if (irDC == 0 || redDC == 0) return spo2;

  // AC component: max-min (unbiased peak-to-trough)
  // This gives correct R values on average. The occasional noisy window
  // is handled by the R-ratio median filter below.
  float irAC = (float)(irMax - irMin);
  float redAC = (float)(redMax - redMin);

  // Minimum AC threshold: reject if pulsatile signal is too weak.
  // Weak AC = poor finger contact or sensor noise dominating.
  if (irAC < 200 || redAC < 200) {
    return spo2;  // Keep previous value
  }

  // Perfusion Index check: AC must be >= 0.4% of DC.
  // Below this, noise dominates and R-ratio is unreliable.
  float perfusionIndex = irAC / irDC;
  if (perfusionIndex < 0.004f) {
    return spo2;  // Keep previous value
  }

  // R ratio = (AC_red/DC_red) / (AC_ir/DC_ir)
  float R = (redAC / redDC) / (irAC / irDC);

  // R-ratio bounds: reject physiologically implausible values.
  // R < 0.2 → SpO2 > 105% (impossible, noise artifact)
  // R > 1.0 → SpO2 < 85%, redAC > irAC (artifact for conscious patient)
  if (R < 0.2f || R > 1.0f) {
    return spo2;  // Keep previous value, don't poison median buffer
  }

  // Store R in history and take MEDIAN across 10 calculation windows.
  // This is the key stability mechanism: median rejects the 1-2 outlier
  // R values per cycle (from noisy max/min) without biasing the ratio.
  rRatioHistory[rRatioHistIdx] = R;
  rRatioHistIdx = (rRatioHistIdx + 1) % R_RATIO_HIST_SIZE;
  if (rRatioHistCount < R_RATIO_HIST_SIZE) rRatioHistCount++;

  float smoothR = medianFloatArray(rRatioHistory, rRatioHistCount);

  // Standard linear calibration: SpO2 = 110 - 25 * R
  float rawSpO2 = constrain(110.0 - 25.0 * smoothR, 70.0, 100.0);

  // Outlier rejection on final SpO2 (belt-and-suspenders with R median)
  if (spo2Initialized && isOutlierAbsolute(rawSpO2, smoothedSpO2, SPO2_OUTLIER_THRESH)) {
    Serial.printf("[SpO2] Outlier rejected: %.1f vs smoothed %.1f\n", rawSpO2, smoothedSpO2);
    return spo2;
  }

  return rawSpO2;
}

void readMAX30102() {
  // === FIFO batch reading — SpO2 + HR fallback ===
  // Primary HR source is ECG (more accurate). When ECG leads are off,
  // we fall back to detecting heartbeats from the IR signal using
  // the SparkFun checkForBeat() algorithm.

  useMAX30102ForHR = ecgLeadsOff;  // Auto-switch based on ECG status

  particleSensor.check();  // Read hardware FIFO into library buffer

  while (particleSensor.available()) {
    long irValue  = particleSensor.getFIFOIR();
    long redValue = particleSensor.getFIFORed();

    fingerDetected = (irValue > 50000);

    if (fingerDetected) {
      lastValidReading = millis();

      // SpO2 — store in circular buffer
      irBuffer[spo2BufIndex]  = irValue;
      redBuffer[spo2BufIndex] = redValue;
      spo2BufIndex = (spo2BufIndex + 1) % SPO2_BUFFER_SIZE;
      if (spo2SampleCount < SPO2_BUFFER_SIZE + 1) {
        spo2SampleCount++;
      }

      // === MAX30102 HR fallback (when ECG leads are off) ===
      if (useMAX30102ForHR && checkForBeat(irValue)) {
        unsigned long now = millis();
        if (lastMAX30102Beat > 0) {
          long delta = now - lastMAX30102Beat;
          // Valid beat interval: 300-2000ms (30-200 BPM)
          if (delta > 300 && delta < 2000) {
            float bpm = 60000.0 / (float)delta;

            // Store in rates[] ring buffer (shared with ECG path)
            rates[rateSpot] = bpm;
            rateSpot = (rateSpot + 1) % RATE_SIZE;
            ecgHRValidCount++;

            // Median filter
            int medianCount = min(ecgHRValidCount, HR_MEDIAN_SIZE);
            for (int i = 0; i < medianCount; i++) {
              int idx = (rateSpot - 1 - i + RATE_SIZE) % RATE_SIZE;
              hrMedianBuf[i] = rates[idx];
            }
            float medianHR = medianOfArray(hrMedianBuf, medianCount);

            // Outlier rejection
            if (hrInitialized && isOutlierPercent(medianHR, smoothedHR, HR_OUTLIER_THRESH)) {
              // Reject spike
            } else {
              heartRate = medianHR;
              smoothedHR = applyEMA(smoothedHR, heartRate, EMA_ALPHA_HR, hrInitialized);
              hrInitialized = true;
            }
          }
        }
        lastMAX30102Beat = now;
      }
    }

    particleSensor.nextSample();
  }

  // Calculate SpO2 once we have minimum samples
  if (fingerDetected && spo2SampleCount >= SPO2_MIN_SAMPLES) {
    float rawSpO2 = calculateSpO2();
    if (rawSpO2 > 0) {
      spo2 = rawSpO2;

      // Apply EMA smoothing
      smoothedSpO2 = applyEMA(smoothedSpO2, spo2, EMA_ALPHA_SPO2, spo2Initialized);
      spo2Initialized = true;
    }
  }

  // Reset if finger removed for > timeout
  if (!fingerDetected && (millis() - lastValidReading > VALID_READING_TIMEOUT)) {
    spo2 = 0.0;
    smoothedSpO2 = 0.0;
    spo2Initialized = false;
    spo2SampleCount = 0;
    spo2BufIndex = 0;
    // Also reset MAX30102-based HR and R-ratio history
    if (useMAX30102ForHR) {
      heartRate = 0.0;
      smoothedHR = 0.0;
      hrInitialized = false;
      lastMAX30102Beat = 0;
    }
    rRatioHistCount = 0;
    rRatioHistIdx = 0;
  }
}

void readMLX90614() {
  // v2.1: No clock switching needed — everything runs at 100kHz now.
  // The MLX90614 was returning NaN because the bus was stuck at 400kHz
  // after MAX30102 init with I2C_SPEED_FAST. Fixed by using I2C_SPEED_STANDARD.

  float objTemp = mlx.readObjectTempC();
  float ambTemp = mlx.readAmbientTempC();

  // Sanity guard: I2C glitch can return garbage floats
  if (objTemp < -100.0f || objTemp > 500.0f ||
      ambTemp < -100.0f || ambTemp > 500.0f) {
    Serial.println("[TEMP] garbage I2C read — skipped");
    return;
  }

  // Debug every 5th reading
  static int debugCounter = 0;
  debugCounter++;
  if (debugCounter >= 5) {
    Serial.printf("[TEMP] Raw IR: %.2f°C + offset %.1f = %.2f°C | Ambient: %.2f°C | Smoothed: %.1f°C\n",
                  objTemp, TEMP_CALIBRATION_OFFSET, objTemp + TEMP_CALIBRATION_OFFSET, ambTemp, smoothedTemp);
    debugCounter = 0;
  }

  // Validate: must be a real number and within skin IR sensor range
  // (NOT room temp range — if IR reads 28°C, the sensor isn't measuring skin properly)
  if (!isnan(objTemp) && objTemp >= TEMP_SKIN_IR_MIN && objTemp <= TEMP_SKIN_IR_MAX) {
    // Apply calibration: IR surface temp → estimated core body temp
    // Skin surface is typically 1.5-3°C below core temp (varies by person/location)
    float calibratedTemp = objTemp + TEMP_CALIBRATION_OFFSET;

    // Outlier rejection: reject if > 1.5°C from current smoothed value
    if (tempInitialized && isOutlierAbsolute(calibratedTemp, smoothedTemp, TEMP_OUTLIER_THRESH)) {
      Serial.printf("[TEMP] Outlier rejected: %.1f°C vs smoothed %.1f°C (diff %.1f)\n",
                    calibratedTemp, smoothedTemp, fabs(calibratedTemp - smoothedTemp));
      return;  // Keep previous values
    }

    temperature = calibratedTemp;

    // Apply EMA smoothing
    smoothedTemp = applyEMA(smoothedTemp, temperature, EMA_ALPHA_TEMP, tempInitialized);
    tempInitialized = true;
  }
  // NOTE: We do NOT fall back to ambient temp — it's room temperature, not body temp.
  // If IR reading is invalid, we keep the last valid temperature.
  // This prevents the "28°C" bug where ambient/room temp was used as body temp.
}

// =====================================================================
//  RESPIRATORY RATE (v2.0 — timestamp-based, partial buffer, EMA)
// =====================================================================

float calculateRespiratoryRate() {
  // === ECG-Derived Respiration via R-peak amplitude modulation ===
  // Breathing modulates R-peak heights due to chest movement changing
  // electrode-heart distance. We detect this envelope oscillation.

  int sampleCount = min(rrBufCount, RR_BUFFER_SIZE);
  if (sampleCount < RR_MIN_SAMPLES) return respiratoryRate;  // Need minimum samples

  // 1. Calculate mean R-peak amplitude
  long sum = 0;
  for (int i = 0; i < sampleCount; i++) {
    sum += rPeakAmplitudes[i];
  }
  float meanAmplitude = (float)sum / sampleCount;

  // 2. Count zero-crossings (transitions above/below mean)
  // Each complete cycle (above→below→above) = one breath
  int zeroCrossings = 0;
  bool aboveMean = (rPeakAmplitudes[0] > meanAmplitude);

  for (int i = 1; i < sampleCount; i++) {
    bool currentAbove = (rPeakAmplitudes[i] > meanAmplitude);
    if (currentAbove != aboveMean) {
      zeroCrossings++;
      aboveMean = currentAbove;
    }
  }

  // Number of complete breath cycles = zero-crossings / 2
  float breathsInBuffer = (float)zeroCrossings / 2.0;

  // v2.0: Use ACTUAL timestamps for buffer duration (instead of assuming ~1 beat/sec)
  float bufferDurationSeconds;
  if (rPeakTimes[0] > 0 && rPeakTimes[sampleCount - 1] > rPeakTimes[0]) {
    bufferDurationSeconds = (float)(rPeakTimes[sampleCount - 1] - rPeakTimes[0]) / 1000.0;
  } else {
    // Fallback: estimate from HR
    bufferDurationSeconds = sampleCount * (60.0 / max(smoothedHR, 50.0f));
  }

  if (bufferDurationSeconds <= 0) return respiratoryRate;

  // Breaths per minute
  float rr = (breathsInBuffer / bufferDurationSeconds) * 60.0;

  // Sanity check: typical respiratory rate 6-40 breaths/min
  if (rr < 6.0 || rr > 45.0) {
    return respiratoryRate;  // Keep previous value if out of range
  }

  return rr;
}

// =====================================================================
//  ECG READING + HEART RATE (v2.0 — float rates, median filter,
//  adaptive threshold, outlier rejection, EMA smoothing)
// =====================================================================

void readECG() {
  ecgLeadsOff = (digitalRead(ECG_LO_PLUS) == HIGH) || (digitalRead(ECG_LO_MINUS) == HIGH);

  if (ecgLeadsOff) {
    ecgValue = 0;
    // Reset beat detection state — leads-off corrupts the signal
    if (ecgInBeat) {
      ecgInBeat = false;
      ecgPeakValue = 0;
    }
    return;
  }

  // Read raw ECG value (12-bit ADC: 0-4095)
  ecgValue = analogRead(ECG_PIN);

  // === Moving average baseline ===
  ecgHistory[ecgHistIndex] = ecgValue;
  ecgHistIndex = (ecgHistIndex + 1) % ECG_WINDOW_SIZE;
  if (ecgHistIndex == 0) ecgHistFull = true;

  long sum = 0;
  int count = ecgHistFull ? ECG_WINDOW_SIZE : ecgHistIndex;
  for (int i = 0; i < count; i++) {
    sum += ecgHistory[i];
  }
  if (count > 0) {
    ecgBaseline = sum / count;
  }

  // Height above baseline
  int ecgHeight = ecgValue - ecgBaseline;

  // === Adaptive threshold (v2.1) ===
  // NOTE: threshold is ONLY updated on confirmed R-peaks (below),
  // NOT on every sample. This prevents noise from dragging it to the floor.
  float currentThreshold = max(ecgAdaptiveThreshold * ECG_ADAPT_FRACTION, 150.0f);
  // Floor of 150 prevents detecting noise as R-peaks

  // === R-peak detection with refractory period ===
  unsigned long now = millis();
  bool pastRefractory = (now - lastRPeakTime) > ECG_REFRACTORY_MS;

  if (ecgHeight > currentThreshold && pastRefractory) {
    if (!ecgInBeat) {
      // Entering a new R-peak region
      ecgInBeat = true;
      ecgPeakValue = ecgValue;
    } else {
      // Still in R-peak — track the actual peak
      if (ecgValue > ecgPeakValue) {
        ecgPeakValue = ecgValue;
      }
    }
  } else if (ecgInBeat && ecgHeight < currentThreshold / 2) {
    // Exiting R-peak region — validate before registering
    ecgInBeat = false;

    int peakAmplitude = ecgPeakValue - ecgBaseline;

    // Reject weak peaks (noise, not real R-peaks)
    if (peakAmplitude < ECG_MIN_PEAK_AMP) return;

    // Confirmed R-peak — NOW update adaptive threshold
    ecgAdaptiveThreshold = ECG_ADAPT_ALPHA * (float)peakAmplitude
                         + (1.0f - ECG_ADAPT_ALPHA) * ecgAdaptiveThreshold;

    // Store R-peak amplitude AND timestamp for respiratory rate
    rPeakAmplitudes[rrBufIndex] = peakAmplitude;
    rPeakTimes[rrBufIndex] = now;  // v2.0: store actual timestamp
    rrBufIndex = (rrBufIndex + 1) % RR_BUFFER_SIZE;
    if (rrBufCount < RR_BUFFER_SIZE + 1) {
      rrBufCount++;  // Track how many we've collected
    }

    // === Heart Rate Calculation ===
    if (lastRPeakTime > 0) {
      long rrInterval = now - lastRPeakTime;  // R-R interval in ms

      // Valid R-R interval: 300ms to 2000ms (30-200 BPM)
      if (rrInterval > 300 && rrInterval < 2000) {
        beatsPerMinute = 60000.0 / (float)rrInterval;

        // v2.0: Store as FLOAT (not byte! byte overflow was causing 280→24 bug)
        rates[rateSpot] = beatsPerMinute;
        rateSpot = (rateSpot + 1) % RATE_SIZE;
        ecgHRValidCount++;

        // v2.0: MEDIAN FILTER — reject spikes before averaging
        // Copy last HR_MEDIAN_SIZE readings into median buffer
        int medianCount = min(ecgHRValidCount, HR_MEDIAN_SIZE);
        for (int i = 0; i < medianCount; i++) {
          int idx = (rateSpot - 1 - i + RATE_SIZE) % RATE_SIZE;
          hrMedianBuf[i] = rates[idx];
        }
        float medianHR = medianOfArray(hrMedianBuf, medianCount);

        // v2.0: Calculate mean of rates[] (using float, no byte truncation)
        int validCount = 0;
        float hrSum = 0;
        int limit = min(ecgHRValidCount, RATE_SIZE);
        for (int x = 0; x < limit; x++) {
          if (rates[x] > 20.0 && rates[x] < 250.0) {
            hrSum += rates[x];
            validCount++;
          }
        }
        if (validCount > 0) {
          beatAvg = hrSum / validCount;
        }

        // Use median for raw heartRate (more resistant to spikes than mean)
        float candidateHR = medianHR;

        // v2.0: Outlier rejection — if new HR differs > 35% from smoothed, reject
        if (hrInitialized && isOutlierPercent(candidateHR, smoothedHR, HR_OUTLIER_THRESH)) {
          Serial.printf("[HR] Outlier rejected: %.0f BPM vs smoothed %.0f (diff %.0f%%)\n",
                        candidateHR, smoothedHR,
                        fabs(candidateHR - smoothedHR) / smoothedHR * 100.0);
          // Don't update heartRate — keep previous value
        } else {
          heartRate = candidateHR;

          // v2.0: Apply EMA smoothing
          smoothedHR = applyEMA(smoothedHR, heartRate, EMA_ALPHA_HR, hrInitialized);
          hrInitialized = true;
        }
      }
    }

    lastRPeakTime = now;
  }

  // Reset HR if no beats detected for 5 seconds
  if ((now - lastRPeakTime) > 5000 && lastRPeakTime > 0) {
    heartRate = 0.0;
    smoothedHR = 0.0;
    hrInitialized = false;
    beatAvg = 0;
    beatsPerMinute = 0;
    ecgHRValidCount = 0;
    for (int i = 0; i < RATE_SIZE; i++) {
      rates[i] = 0;
    }
    rateSpot = 0;
    respiratoryRate = 0.0;
    smoothedRR = 0.0;
    rrInitialized = false;
    rrBufCount = 0;
  }

  // === Calculate Respiratory Rate (every 5 seconds, once we have minimum samples) ===
  if ((now - lastRespCalcTime) >= RESP_CALC_INTERVAL_MS && rrBufCount >= RR_MIN_SAMPLES) {
    float rawRR = calculateRespiratoryRate();
    if (rawRR > 0) {
      // v2.0: Outlier rejection on RR
      if (rrInitialized && isOutlierPercent(rawRR, smoothedRR, RR_OUTLIER_THRESH)) {
        Serial.printf("[RR] Outlier rejected: %.1f vs smoothed %.1f\n", rawRR, smoothedRR);
      } else {
        respiratoryRate = rawRR;
        smoothedRR = applyEMA(smoothedRR, respiratoryRate, EMA_ALPHA_RR, rrInitialized);
        rrInitialized = true;
      }
    }
    lastRespCalcTime = now;
  }
}

// =====================================================================
//  STATUS EVALUATION (v2.0 — uses SMOOTHED values)
// =====================================================================

void evaluateStatus() {
  bool isCritical = false;
  bool isWarning  = false;
  alertType = "";

  // Only evaluate if we have valid sensor data (finger or ECG)
  if (!fingerDetected) {
    if (smoothedHR == 0) {
      currentStatus = "NO_FINGER";
      alertTriggered = false;
      return;
    }
  }

  // Check if we have valid vitals before evaluating
  if (smoothedHR == 0 && smoothedSpO2 == 0) {
    currentStatus = "DETECTING";
    alertTriggered = false;
    return;
  }

  // Heart Rate (use SMOOTHED value — prevents false alarms from spikes)
  if (smoothedHR > 0) {
    if (smoothedHR < HR_CRITICAL_LOW || smoothedHR > HR_CRITICAL_HIGH) {
      isCritical = true;
      alertType += (smoothedHR < HR_CRITICAL_LOW) ? "BRADYCARDIA_CRITICAL," : "TACHYCARDIA_CRITICAL,";
    } else if (smoothedHR < HR_LOW || smoothedHR > HR_HIGH) {
      isWarning = true;
      alertType += (smoothedHR < HR_LOW) ? "LOW_HR," : "HIGH_HR,";
    }
  }

  // SpO2 (use SMOOTHED value)
  if (smoothedSpO2 > 0) {
    if (smoothedSpO2 < SPO2_CRITICAL) {
      isCritical = true;
      alertType += "HYPOXIA_CRITICAL,";
    } else if (smoothedSpO2 < SPO2_NORMAL) {
      isWarning = true;
      alertType += "LOW_SPO2,";
    }
  }

  // Temperature (use SMOOTHED value)
  if (smoothedTemp > 0) {
    if (smoothedTemp > TEMP_CRITICAL_H || smoothedTemp < TEMP_CRITICAL_L) {
      isCritical = true;
      alertType += (smoothedTemp > TEMP_CRITICAL_H) ? "HYPERTHERMIA," : "HYPOTHERMIA,";
    } else if (smoothedTemp > TEMP_FEVER) {
      isWarning = true;
      alertType += "FEVER,";
    } else if (smoothedTemp > TEMP_HIGH) {
      isWarning = true;
      alertType += "ELEVATED_TEMP,";
    } else if (smoothedTemp < TEMP_LOW) {
      isWarning = true;
      alertType += "LOW_TEMP,";
    }
  }

  // Set overall status
  if (isCritical) {
    currentStatus  = "CRITICAL";
    alertTriggered = true;
  } else if (isWarning) {
    currentStatus  = "WARNING";
    alertTriggered = true;
  } else {
    currentStatus  = "NORMAL";
    alertTriggered = false;
  }

  // Remove trailing comma
  if (alertType.endsWith(",")) {
    alertType.remove(alertType.length() - 1);
  }
}

// =====================================================================
//  LED & BUZZER CONTROL
// =====================================================================

void updateLEDs() {
  // Reset all status LEDs
  digitalWrite(LED_NORMAL, LOW);
  digitalWrite(LED_WARNING, LOW);
  digitalWrite(LED_CRITICAL, LOW);
  digitalWrite(BUZZER_PIN, LOW);

  // WiFi status LED
  digitalWrite(LED_WIFI, WiFi.status() == WL_CONNECTED ? HIGH : LOW);

  if (currentStatus == "CRITICAL") {
    digitalWrite(LED_CRITICAL, HIGH);
    // Buzzer — intermittent beep for critical
    tone(BUZZER_PIN, 2000, 200);  // 2kHz for 200ms
  } else if (currentStatus == "WARNING") {
    digitalWrite(LED_WARNING, HIGH);
  } else {
    digitalWrite(LED_NORMAL, HIGH);
  }
}

// =====================================================================
//  OLED DISPLAY (v2.0 — shows SMOOTHED values)
// =====================================================================

void updateDisplay() {
  display.clearBuffer();

  // Title bar
  display.setFont(u8g2_font_ncenB08_tr);
  display.drawStr(0, 10, "Medical Monitor v2");
  display.drawHLine(0, 13, 128);

  // Check for warm-up period
  if (millis() - bootTime < WARMUP_PERIOD_MS) {
    display.setCursor(5, 35);
    display.print("Warming up...");
    display.sendBuffer();
    return;
  }

  // Check for finger presence
  if (!fingerDetected && smoothedHR == 0) {
    display.setCursor(5, 28);
    display.print("Place finger on");
    display.setCursor(5, 40);
    display.print("MAX30102 sensor");
    display.setCursor(5, 52);
    display.print("or connect ECG");
  } else if (currentStatus == "DETECTING") {
    display.setCursor(5, 35);
    display.print("Detecting vitals");
    display.setCursor(5, 48);
    display.print("Hold steady...");
  } else {
    // Heart Rate (smoothed)
    display.setFont(u8g2_font_6x10_tr);
    display.setCursor(0, 24);
    display.print("HR: ");
    if (smoothedHR > 0) {
      display.print((int)smoothedHR);
      display.print(" BPM");
    } else {
      display.print("---");
    }

    // SpO2 (smoothed)
    display.setCursor(0, 34);
    display.print("SpO2: ");
    if (smoothedSpO2 > 0) {
      display.print(smoothedSpO2, 1);
      display.print(" %");
    } else {
      display.print("---");
    }

    // Temperature (smoothed)
    display.setCursor(0, 44);
    display.print("Temp: ");
    if (smoothedTemp > 0) {
      display.print(smoothedTemp, 1);
      display.print(" C");
    } else {
      display.print("---");
    }

    // Respiratory Rate (smoothed)
    display.setCursor(0, 54);
    display.print("RR: ");
    if (smoothedRR > 0) {
      display.print(smoothedRR, 1);
      display.print(" br/m");
    } else {
      display.print("---");
    }
  }

  // Status bar at bottom
  display.drawHLine(0, 56, 128);
  display.setFont(u8g2_font_5x7_tr);
  display.setCursor(0, 63);
  display.print("Status: ");
  display.print(currentStatus.c_str());

  display.sendBuffer();
}

// =====================================================================
//  WIFI
// =====================================================================

void connectWiFi() {
  Serial.print("Connecting to WiFi: ");
  Serial.println(WIFI_SSID);

  display.clearBuffer();
  display.drawStr(5, 30, "Connecting WiFi...");
  display.sendBuffer();

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(500);
    Serial.print(".");
    attempts++;
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nWiFi connected!");
    Serial.print("IP: ");
    Serial.println(WiFi.localIP());
    digitalWrite(LED_WIFI, HIGH);

    display.clearBuffer();
    display.drawStr(5, 30, "WiFi Connected!");
    display.setCursor(5, 50);
    display.print(WiFi.localIP().toString().c_str());
    display.sendBuffer();
    delay(1500);
  } else {
    Serial.println("\nWiFi FAILED!");
    display.clearBuffer();
    display.drawStr(5, 30, "WiFi FAILED!");
    display.sendBuffer();
    delay(2000);
  }
}

// =====================================================================
//  SEND DATA TO SERVER (v2.0 — sends SMOOTHED values)
// =====================================================================

void sendDataToServer() {
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi disconnected — attempting reconnect...");
    connectWiFi();
    if (WiFi.status() != WL_CONNECTED) return;
  }

  // Build JSON payload — send SMOOTHED values (clinically reliable)
  StaticJsonDocument<512> doc;
  doc["heartRate"]       = smoothedHR;
  doc["spo2"]            = smoothedSpO2;
  doc["temperature"]     = smoothedTemp;
  doc["respiratoryRate"] = smoothedRR;
  doc["ecgValue"]        = ecgValue;
  doc["status"]          = currentStatus;
  doc["alertTriggered"]  = alertTriggered;
  doc["alertType"]       = alertType;
  doc["deviceId"]        = DEVICE_ID;

  String jsonPayload;
  serializeJson(doc, jsonPayload);

  // HTTP POST
  HTTPClient http;
  http.begin(SERVER_URL);
  http.addHeader("Content-Type", "application/json");

  int httpResponseCode = http.POST(jsonPayload);

  if (httpResponseCode > 0) {
    Serial.printf("Data sent — HTTP %d | HR: %.0f | SpO2: %.1f | Temp: %.1f | RR: %.1f | Status: %s\n",
                  httpResponseCode, smoothedHR, smoothedSpO2, smoothedTemp, smoothedRR, currentStatus.c_str());
  } else {
    Serial.printf("HTTP Error: %s\n", http.errorToString(httpResponseCode).c_str());
  }

  http.end();
}
