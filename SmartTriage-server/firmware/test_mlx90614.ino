/*
 * MLX90614 IR Temperature — Diagnostic Test  v2.0
 * ESP32: SDA=21, SCL=22, 100kHz I2C (SMBus)
 *
 * Displays:
 *   - Raw object temperature (IR surface reading)
 *   - Calibrated temperature (raw + offset → estimated core body temp)
 *   - EMA-smoothed temperature (what the main firmware sends to server)
 *   - Ambient temperature (room reference)
 *   - Signal quality: checks for NaN, out-of-range, and sensor stability
 *
 * How to test:
 *   1. Flash this sketch, open Serial Monitor at 115200 baud
 *   2. Let sensor read room temp for 10s (verify Ambient is reasonable)
 *   3. Point at your forehead from ~2-5cm distance
 *   4. Raw should read ~32-36°C, Calibrated ~34-38°C
 *   5. Compare Calibrated reading with a reference thermometer
 *   6. Adjust CALIBRATION_OFFSET if needed (printed at boot)
 *
 * Calibration guide:
 *   OFFSET = reference_thermometer_reading - raw_IR_reading
 *   Forehead typical offset: +3.0 to +5.0°C (depends on distance & ambient)
 *   Wrist typical offset:    +4.0 to +6.0°C
 */

#include <Wire.h>
#include <Adafruit_MLX90614.h>

Adafruit_MLX90614 mlx = Adafruit_MLX90614();

// ======================== CALIBRATION ================================
// Adjust this to match your reference thermometer.
// This is the same value used in medical_monitor.ino.
#define CALIBRATION_OFFSET    4.0f    // °C added to raw IR reading

// Valid raw IR skin reading range (before calibration offset)
// Below 29°C: sensor is reading room temp / no skin contact
// Above 42°C: implausible or sensor malfunction
#define SKIN_IR_MIN           29.0f
#define SKIN_IR_MAX           42.0f

// ======================== EMA SMOOTHING ==============================
#define EMA_ALPHA             0.08f   // Same as medical_monitor.ino
float smoothedTemp = 0.0;
bool  tempInitialized = false;

// ======================== STABILITY TRACKING =========================
#define STABILITY_WINDOW      10
float recentReadings[STABILITY_WINDOW];
int   readingIndex = 0;
int   readingCount = 0;

// Track consecutive valid/invalid readings
int consecutiveValid = 0;
int consecutiveInvalid = 0;

void setup() {
  Serial.begin(115200);
  delay(2000);  // Extra delay for Serial Monitor to connect
  Serial.println("\n=== MLX90614 IR Temperature Test v2.0 ===");
  Serial.flush();

  Serial.println("Initializing I2C...");
  Serial.flush();
  Wire.begin(21, 22);
  Wire.setClock(100000);  // 100kHz — required for MLX90614 (SMBus device)
  delay(100);

  Serial.println("Scanning I2C bus...");
  Serial.flush();
  // Quick I2C scan to verify sensor is on the bus
  Wire.beginTransmission(0x5A);  // MLX90614 default address
  byte error = Wire.endTransmission();
  if (error == 0) {
    Serial.println("  MLX90614 found at 0x5A");
  } else {
    Serial.printf("  MLX90614 NOT found at 0x5A (error=%d)\n", error);
    Serial.println("  Check wiring:");
    Serial.println("    SDA -> GPIO 21");
    Serial.println("    SCL -> GPIO 22");
    Serial.println("    VCC -> 3.3V");
    Serial.println("    GND -> GND");
    Serial.flush();
  }

  Serial.println("Calling mlx.begin()...");
  Serial.flush();
  if (!mlx.begin()) {
    Serial.println("ERROR: MLX90614 init failed!");
    Serial.flush();
    while (1) delay(1000);
  }

  // Let sensor settle after power-on
  delay(250);
  float testObj = mlx.readObjectTempC();
  float testAmb = mlx.readAmbientTempC();

  Serial.printf("MLX90614 ready.\n");
  Serial.printf("  Initial Object (raw):  %.2f°C\n", testObj);
  Serial.printf("  Initial Ambient:       %.2f°C\n", testAmb);
  Serial.printf("  Calibration offset:    +%.1f°C\n", CALIBRATION_OFFSET);
  Serial.printf("  Valid skin IR range:   %.0f-%.0f°C (raw)\n\n", SKIN_IR_MIN, SKIN_IR_MAX);

  Serial.println("Raw°C\tCalib°C\tSmooth°C\tAmbient°C\tStability\tStatus");
  Serial.println("------\t-------\t--------\t---------\t---------\t------");
}

float getStability() {
  if (readingCount < 3) return 99.0;  // Not enough data
  int count = min(readingCount, STABILITY_WINDOW);
  float minVal = recentReadings[0], maxVal = recentReadings[0];
  for (int i = 1; i < count; i++) {
    if (recentReadings[i] < minVal) minVal = recentReadings[i];
    if (recentReadings[i] > maxVal) maxVal = recentReadings[i];
  }
  return maxVal - minVal;  // Spread of recent readings in °C
}

void loop() {
  float objTemp = mlx.readObjectTempC();
  float ambTemp = mlx.readAmbientTempC();

  // Sanity guard: I2C glitch can return garbage floats
  if (objTemp < -100.0f || objTemp > 500.0f ||
      ambTemp < -100.0f || ambTemp > 500.0f) {
    Serial.println("[GLITCH] garbage I2C read — skipped");
    delay(200);
    return;
  }

  const char* status;
  float calibrated = 0;
  float stability = 0;

  // === Validate reading ===
  if (isnan(objTemp) || isnan(ambTemp)) {
    status = "NaN ERROR";
    consecutiveInvalid++;
    consecutiveValid = 0;
    if (consecutiveInvalid >= 5) {
      Serial.println("[WARN] 5+ consecutive NaN readings — check I2C wiring/bus speed");
    }
  } else if (objTemp < SKIN_IR_MIN) {
    // Below skin range — likely reading room temp (no forehead present)
    calibrated = objTemp + CALIBRATION_OFFSET;
    status = "NO SKIN";
    consecutiveValid = 0;
    // Don't update EMA with non-skin readings

    stability = getStability();
    Serial.printf("%.2f\t%.2f\t%.2f\t\t%.2f\t\t%.2f°C\t\t%s\n",
                  objTemp, calibrated,
                  tempInitialized ? smoothedTemp : 0.0,
                  ambTemp, stability, status);
    delay(1000);
    return;

  } else if (objTemp > SKIN_IR_MAX) {
    status = "TOO HIGH";
    consecutiveInvalid++;
    consecutiveValid = 0;
  } else {
    // Valid skin reading
    calibrated = objTemp + CALIBRATION_OFFSET;
    consecutiveValid++;
    consecutiveInvalid = 0;

    // EMA smoothing (same as medical_monitor.ino)
    if (!tempInitialized) {
      smoothedTemp = calibrated;
      tempInitialized = true;
    } else {
      smoothedTemp = EMA_ALPHA * calibrated + (1.0f - EMA_ALPHA) * smoothedTemp;
    }

    // Track for stability measurement
    recentReadings[readingIndex] = calibrated;
    readingIndex = (readingIndex + 1) % STABILITY_WINDOW;
    if (readingCount < STABILITY_WINDOW) readingCount++;

    stability = getStability();

    // Classify signal quality
    if (stability < 0.3) {
      status = "STABLE";        // < 0.3°C spread — excellent
    } else if (stability < 0.8) {
      status = "OK";            // < 0.8°C spread — acceptable
    } else {
      status = "UNSTABLE";      // > 0.8°C spread — moving or bad contact
    }

    // Clinical range feedback
    if (calibrated >= 36.1 && calibrated <= 37.2) {
      // Normal — no extra note
    } else if (calibrated > 37.2 && calibrated <= 38.0) {
      status = "ELEVATED";
    } else if (calibrated > 38.0) {
      status = "FEVER";
    } else if (calibrated < 35.0) {
      status = "LOW TEMP";
    }
  }

  Serial.printf("%.2f\t%.2f\t%.2f\t\t%.2f\t\t%.2f°C\t\t%s\n",
                objTemp, calibrated,
                tempInitialized ? smoothedTemp : 0.0,
                ambTemp, stability, status);

  delay(1000);  // MLX90614 update rate is ~10Hz, 1s is plenty
}
