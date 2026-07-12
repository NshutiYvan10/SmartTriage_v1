# SmartTriage Medical Monitor — ESP32-S3 firmware v3.0

Production-grade rewrite of the placeholder monitor. Calibrated, filtered,
five-page touch UI, on-device alarms, and real transmission into SmartTriage.

## Hardware

| Part | Bus / pins |
|---|---|
| ESP32-S3 | Arduino-ESP32 core **3.x** |
| SPI TFT + touch (TFT_eSPI) | pins live in TFT_eSPI `User_Setup.h` — the sketch adapts to any resolution |
| MAX30102 pulse-ox | I²C — SDA **6**, SCL **7** (100 kHz) |
| MAX30205 contact temp | same I²C bus, addr **0x48** |
| AD8232 ECG | OUT → **1** (ADC), LO+ → **14**, LO− → **15** |
| Cuff pressure ADC | CS **2**, MISO **4**, SCK **12** (bit-banged 16-bit) |
| Pump H-bridge | IN1 **16**, IN2 **17**, ENA **18** (PWM) |
| LEDs | normal **21**, warning **47**, critical **45**, BP **35**, heartbeat **37** |
| Buzzer | **19** |

## Libraries (Arduino Library Manager)

- `TFT_eSPI` — **keep your existing `User_Setup.h` unchanged**: the display is
  driven exactly like the previously working build (same `init()`, rotation 1,
  same touch calibration). All value text uses fonts 2 and 4 (the fonts that
  build already proved); tiny labels use the GLCD font — keep `LOAD_GLCD`,
  `LOAD_FONT2`, `LOAD_FONT4` enabled (they are by default).
- `SparkFun MAX3010x Pulse and Proximity Sensor Library` — **not** the MAX30100 library; that chip is register-incompatible and was why HR/SpO2 never worked
- `ArduinoJson` (v7)

## Build

Verified compiling on `esp32:esp32:esp32s3` (Arduino-ESP32 core 3.3.10) with
zero warnings — 1.16 MB program (88% of the default 1.3 MB app partition).
On an 8/16 MB S3 board pick a larger partition scheme in the IDE
(Tools → Partition Scheme) for comfortable headroom.

## Provisioning (once per device)

1. Register the device in SmartTriage (admin → IoT devices). Copy the serial
   number and API key into `config.h` (`DEVICE_SERIAL`, `DEVICE_API_KEY`).
2. Set `WIFI_SSID` / `WIFI_PASSWORD` / `SERVER_BASE` in `config.h`.
3. **Never commit real credentials** — the repo carries placeholders only.
4. Flash. On the Device page verify: WiFi connected → "SmartTriage receiving"
   → Last sync updating every 5 s. The ED binds the device to a patient
   visit server-side (device sessions); the monitor needs no patient input.

## Calibration

- **Temperature**: MAX30205 is a ±0.1 °C contact sensor; `TEMP_SITE_OFFSET_C`
  (default +0.2 °C) compensates skin-site vs core. Validate against a
  reference thermometer and adjust.
- **Touch**: `TOUCH_CAL` in `config.h` carries the previous build's values.
  If taps land off-target run TFT_eSPI's `Touch_calibrate` example and paste
  the five numbers.
- **Blood pressure**: zero-point auto-calibrates at every boot (cuff must be
  open to air). The **scale** (`BP_PRES_SCALE`) must be validated once against
  a reference gauge: tee the cuff line into a manual sphygmomanometer, inflate
  to a known pressure, and set `BP_PRES_SCALE = reference / displayed`. Until
  then every result shows **UNCALIBRATED** on the BP page — deliberately.
- **Mains notch**: `ECG_MAINS_HZ` is 50 (Rwanda). Set 60 for 60 Hz grids.

## Safety notes (BP)

Inflation is pressure-feedback controlled (target 180 mmHg), with a hard
abort >200 mmHg, inflation stall/timeout detection, a bounded total cycle,
and **every** error path ends in motor-stop + full deflation. Do not raise
`BP_HARD_ABORT_MMHG`.

## Simulation mode

Device page → SIMULATION toggle. Demo vitals + synthetic waveforms for
presentations. While active: an amber banner is shown on every page and
**nothing is transmitted to SmartTriage** — fake vitals must never enter a
clinical record. The backend sees heartbeats only (device online, no data).

## Architecture

```
core 1: sensorTask (250 Hz ECG, MAX30102 FIFO, temp, alarms)
        bpTask     (closed-loop oscillometric measurement)
core 0: netTask    (WiFi state machine, NTP, ingest/heartbeat, offline ring)
        uiTask     (5 pages, swipe nav, flicker-free partial redraws)
```

Files: `config.h` (all tunables) · `filters.h` (EMA/median/notch/DC) ·
`sensors.h` (SpO2/temp/ECG pipelines + sim source) · `bp.h` (oscillometric
state machine) · `alarms.h` · `net.h` · `ui.h` · `state.h` (shared model).
