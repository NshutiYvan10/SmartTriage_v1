/*
 * ============================================================
 *  SmartTriage Medical Monitor — configuration
 *  (ESP32-S3 · TFT_eSPI SPI touchscreen · MAX30102 · MAX30205 · AD8232 · oscillometric BP)
 * ============================================================
 *  Every tunable lives here: pins, clinical thresholds, filter
 *  constants, calibration values, network identity.
 *
 *  PROVISIONING (do once per device):
 *    1. Register the device in SmartTriage (admin → IoT devices) and
 *       copy its serial number + API key into DEVICE_SERIAL / DEVICE_API_KEY.
 *    2. Set WIFI_SSID / WIFI_PASSWORD for the site network.
 *    3. Set SERVER_BASE to the SmartTriage backend origin.
 *  SECURITY: commit this file ONLY with the placeholder values below.
 *  Real credentials are a local, uncommitted change.
 * ============================================================
 */
#pragma once

// ======================== IDENTITY / NETWORK =========================
#define FIRMWARE_VERSION   "s3-3.1.4"

#define WIFI_SSID          "YOUR_WIFI_SSID"
#define WIFI_PASSWORD      "YOUR_WIFI_PASSWORD"
#define SERVER_BASE        "http://192.168.1.100:8080"   // SmartTriage backend origin
#define DEVICE_SERIAL      "ESP32S3-MON-001"             // must match the registered device
#define DEVICE_API_KEY     "PASTE_DEVICE_API_KEY_HERE"   // from device registration

#define INGEST_PATH        "/api/v1/iot/stream/ingest"
#define HEARTBEAT_PATH     "/api/v1/iot/stream/heartbeat"

// Transmission cadence. 5 s matches the backend's default device data
// interval and the bedside pipeline's expectations: fast enough that a
// deteriorating SpO2/HR reaches the ED dashboard well inside one alarm
// cycle, slow enough that HTTP overhead never starves the samplers.
#define TX_INTERVAL_MS         5000UL
#define HEARTBEAT_INTERVAL_MS 20000UL   // when we have nothing new to send
#define HTTP_TIMEOUT_MS        3000UL
#define BACKEND_LOST_ALARM_MS 60000UL   // no ACK for >60 s → alarm (per spec)

// Offline buffer: readings retained while the backend is unreachable,
// flushed oldest-first on reconnect (backend accepts capturedAt).
#define OFFLINE_BUFFER_SIZE 60          // 60 × 5 s = 5 minutes of history

// NTP (capturedAt must be real UTC time for the clinical record)
#define NTP_SERVER_1 "pool.ntp.org"
#define NTP_SERVER_2 "time.google.com"

// ======================== PINS (ESP32-S3) ============================
// I2C bus — MAX30102 (0x57) + MAX30205 (0x48)
#define PIN_I2C_SDA    6
#define PIN_I2C_SCL    7
#define MAX30205_ADDR  0x48

// AD8232 ECG
#define PIN_ECG        1     // analog in
#define PIN_ECG_LO_P   14    // leads-off detect +
#define PIN_ECG_LO_N   15    // leads-off detect -

// Cuff pressure ADC (bit-banged 16-bit, HX710-style)
// PIN_PRES_SCK is GPIO 12 — the SAME wire as the display's SPI clock
// (TFT_SCLK). That is legal SPI-style bus sharing (the display ignores
// clock edges while TFT_CS is high), but it needs software arbitration,
// which bp.h provides: every pressure read (a) takes the shared
// g_spiBusMutex so the UI is never mid-draw, and (b) saves GPIO 12's
// output-matrix routing, bit-bangs the ~70 µs read, and restores the
// routing so the SPI peripheral gets its clock pin back. Do NOT read
// the sensor outside bp.h's guarded readPressureMmHg().
#define PIN_PRES_CS    2
#define PIN_PRES_MISO  4
#define PIN_PRES_SCK   12

// Pump H-bridge
#define PIN_MOTOR_IN1  16
#define PIN_MOTOR_IN2  17
#define PIN_MOTOR_ENA  18
#define MOTOR_PWM_FREQ 1000
#define MOTOR_PWM_RES  8

// Indicators
#define PIN_LED_NORMAL   21
#define PIN_LED_WARNING  47
#define PIN_LED_CRITICAL 45
#define PIN_LED_BP       35
#define PIN_LED_HEART    37
#define PIN_BUZZER       19

// Touch (TFT pins live in TFT_eSPI's User_Setup.h — the library owns them)
//
// TOUCH_CAL is only the FACTORY-DEFAULT calibration. The monitor stores
// its real calibration in flash (NVS): run "CALIBRATE TOUCH" on the
// Device page once, tap the four corner arrows, and the values persist
// across reboots + reflashes (NVS survives a sketch upload). The stored
// values are also printed to serial as a TOUCH_CAL line — paste it here
// if you ever want the default to match.
//
// v3.1.0 note: this default is suspect — its X span (337..1099) covers
// only ~20% of the XPT2046 ADC range, which matches the observed "only
// one spot on the right responds" behaviour. Run the on-device
// calibration; it takes ten seconds and fixes swipe + buttons together.
#define TOUCH_CAL { 337, 1099, 317, 3355, 7 }

// Gesture tuning
#define SWIPE_MIN_PX        50    // horizontal travel that flips a page
#define TOUCH_Z_PRESS      240    // raw pressure to ENTER a touch (idle gate)
#define TOUCH_Z_TRACK      140    // lighter gate while a finger is tracking

// ======================== SAMPLING CADENCE ===========================
#define ECG_SAMPLE_INTERVAL_MS   4      // 250 Hz — standard monitor rate
#define TEMP_READ_INTERVAL_MS 1000
#define UI_FRAME_MS             33      // ~30 fps UI task
#define SENSOR_TASK_TICK_MS      2

// ======================== CLINICAL ALARM THRESHOLDS ==================
// Per the ratified spec. WARNING bands sit inside the alarm bands so the
// display can go yellow before it goes red.
#define ALM_SPO2_CRIT      90.0f   // SpO2 < 90 → alarm
#define ALM_SPO2_WARN      94.0f
#define ALM_HR_CRIT_LOW    40.0f   // HR < 40 or > 150 → alarm
#define ALM_HR_CRIT_HIGH  150.0f
#define ALM_HR_WARN_LOW    50.0f
#define ALM_HR_WARN_HIGH  120.0f
#define ALM_TEMP_CRIT_HIGH 39.5f   // Temp > 39.5 or < 35.5 → alarm
#define ALM_TEMP_CRIT_LOW  35.5f
#define ALM_TEMP_WARN_HIGH 38.0f
#define ALM_TEMP_WARN_LOW  36.0f
#define ALM_SYS_CRIT_HIGH 180
#define ALM_SYS_CRIT_LOW   80
#define ALM_RR_WARN_LOW     8.0f
#define ALM_RR_WARN_HIGH   28.0f

#define ALARM_SILENCE_MS  120000UL  // touch-silence duration (standard monitor behaviour)

// ======================== PLAUSIBILITY CLAMPS ========================
// Values outside these are rejected as artifact, never displayed/sent.
#define HR_MIN    30.0f
#define HR_MAX   250.0f
#define SPO2_MIN  70.0f
#define SPO2_MAX 100.0f
#define TEMP_MIN  30.0f    // accept below adult band so hypothermia still displays
#define TEMP_MAX  43.0f
#define RR_MIN     6.0f
#define RR_MAX    45.0f
#define BP_SYS_MIN  60
#define BP_SYS_MAX 250
#define BP_DIA_MIN  40
#define BP_DIA_MAX 150

// ======================== FILTERS ====================================
#define EMA_ALPHA_HR    0.15f
#define EMA_ALPHA_SPO2  0.10f
#define EMA_ALPHA_TEMP  0.08f
#define EMA_ALPHA_RR    0.12f
#define HR_OUTLIER_FRAC   0.35f   // reject HR >35% off smoothed
#define SPO2_OUTLIER_ABS  8.0f    // reject SpO2 >8 points off smoothed
#define TEMP_OUTLIER_ABS  1.5f    // reject temp >1.5°C off smoothed
#define RR_OUTLIER_FRAC   0.40f

// ======================== MAX30102 ===================================
#define SPO2_BUFFER_SIZE   100    // 1 s @ 100 Hz — one cardiac cycle
#define SPO2_MIN_SAMPLES    25
#define R_RATIO_HIST_SIZE   10
#define FINGER_IR_THRESHOLD 50000L
#define FINGER_LOST_RESET_MS 3000UL

// ======================== ECG DETECTOR ===============================
#define ECG_BASELINE_ALPHA   0.01f   // DC/baseline-wander tracker (~0.4 Hz HPF)
#define ECG_REFRACTORY_MS    300
#define ECG_MIN_PEAK_AMP     200
#define ECG_ADAPT_ALPHA      0.15f
#define ECG_ADAPT_FRACTION   0.45f
#define ECG_INITIAL_THRESHOLD 300.0f
#define ECG_HR_TIMEOUT_MS    5000UL  // no beats for 5 s → HR unknown
#define HR_MEDIAN_SIZE       5
#define RATE_RING_SIZE       12
// 50 Hz mains notch (Rwanda grid). Set to 60 for 60 Hz regions.
#define ECG_MAINS_HZ         50.0f
// Waveform ring for UI + one exported beat for the backend payload
#define ECG_WAVE_RING        512
#define ECG_EXPORT_SAMPLES   50      // one beat, downsampled, CSV in payload

// ======================== RESPIRATION (EDR) ==========================
#define RR_BUFFER_SIZE   30
#define RR_MIN_SAMPLES   12
#define RESP_CALC_INTERVAL_MS 3000

// ======================== TEMPERATURE (MAX30205) =====================
// MAX30205 is a ±0.1°C CONTACT sensor — unlike the old IR sensor it needs
// no big surface-to-core offset. A small site offset remains tunable
// (axillary placement typically reads ~0.2-0.5°C below core).
#define TEMP_SITE_OFFSET_C  0.2f
#define TEMP_RAW_MIN       25.0f   // below → sensor not on skin
#define TEMP_RAW_MAX       45.0f

// ======================== BLOOD PRESSURE =============================
// SAFETY ENVELOPE — enforced in bp.h on every path, including errors:
#define BP_TARGET_INFLATE_MMHG   180.0f  // stop inflating here
#define BP_HARD_ABORT_MMHG       200.0f  // instant abort + full deflate
#define BP_INFLATE_TIMEOUT_MS  20000UL   // pump can't reach target → abort
#define BP_MEASURE_TIMEOUT_MS  60000UL   // whole measurement bounded
#define BP_DEFLATE_FLOOR_MMHG     35.0f  // measurement ends below this
#define BP_SAMPLE_INTERVAL_MS      20    // 50 Hz cuff-pressure sampling
#define BP_DEFLATE_PWM            60     // starting deflate PWM (tune for ~3 mmHg/s)
#define BP_INFLATE_PWM           255
// Fixed-ratio oscillometric identification (industry-standard ratios)
#define BP_SYS_RATIO 0.55f
#define BP_DIA_RATIO 0.75f
// Pressure sensor transfer: mmHg = (raw/16383)*300*BP_PRES_SCALE + offset.
// Offset is auto-zeroed at boot (cuff open to air). BP_PRES_SCALE must be
// validated against a reference gauge — until then every result carries
// the UNCALIBRATED flag on screen.
#define BP_PRES_SCALE 1.00f
#define BP_HISTORY_SIZE 8

// ======================== TRENDS =====================================
#define TREND_POINTS       120     // 120 points × 5 s = 10 minutes per screen
#define TREND_INTERVAL_MS 5000UL
