# SmartTriage — External Monitor Simulator

A standalone Python script that simulates ESP32 bedside monitors posting vitals
to the SmartTriage backend over HTTP. Run it on **any computer on your local
network** to simulate multiple monitors sending vitals for different patients.

---

## Quick Start

### 1. Install Python dependency

```bash
pip install requests
```

### 2. Register a device on the SmartTriage frontend

1. Log in as **Admin** → go to **IoT Devices**
2. Click **Register Device** — enter a serial number (e.g. `MON-BED-01`)
3. Copy the **serial number** and **API key** shown after registration
4. **Power On** the device (admin action)
5. As a **Nurse**, **Assign** the device to a patient

### 3. Run the simulator

```bash
# From another computer on the same network:
python monitor_simulator.py \
    --server 192.168.1.50:8080 \
    --serial MON-BED-01 \
    --api-key st_dev_abc123...

# Or on the same computer (use localhost):
python monitor_simulator.py \
    --server localhost:8080 \
    --serial MON-BED-01 \
    --api-key st_dev_abc123...
```

Vitals will start flowing every 5 seconds → visible on the **Constant Monitoring** page.

---

## Scenarios

| Scenario        | Description                                          |
| --------------- | ---------------------------------------------------- |
| `normal`        | Stable adult — all vitals in safe range              |
| `tachycardic`   | Elevated HR (100–130 bpm), otherwise normal          |
| `hypotensive`   | Low BP (SBP 80–95), compensatory tachycardia         |
| `hypoxic`       | Dropping SpO2 (88–93%), tachypnoea                   |
| `febrile`       | Fever (38.5–39.5°C), compensatory tachycardia        |
| `deteriorating` | Starts normal, gradually worsens over 5 minutes      |
| `critical`      | Multiple vitals in danger zone — triggers RED alerts |

```bash
# Example — deteriorating patient:
python monitor_simulator.py --server localhost:8080 \
    --serial MON-BED-02 --api-key st_dev_... --scenario deteriorating
```

---

## Multi-Device Simulation

Create a `devices.json` file (see `devices.example.json`):

```json
[
  { "serial": "MON-BED-01", "apiKey": "st_dev_...", "scenario": "normal" },
  {
    "serial": "MON-BED-02",
    "apiKey": "st_dev_...",
    "scenario": "deteriorating"
  },
  { "serial": "MON-BED-03", "apiKey": "st_dev_...", "scenario": "critical" }
]
```

```bash
python monitor_simulator.py --server 192.168.1.50:8080 --config devices.json
```

Each device runs on its own thread, posting independently.

---

## Distributed Simulation (Multiple Computers)

1. Find the server computer's IP: `ipconfig` (Windows) or `ifconfig` (Mac/Linux)
2. On each other computer, install Python + requests
3. Copy `monitor_simulator.py` to each computer
4. Register a separate device per computer on the SmartTriage frontend
5. Run the script with `--server <SERVER_IP>:8080 --serial <DEVICE_SERIAL> --api-key <KEY>`

Each computer acts as its own bedside monitor. All vitals flow into the same
backend and show up on the Constant Monitoring dashboard in real-time.

---

## How It Works

```
 Computer A                    Computer B (server)              Browser
┌──────────────┐             ┌─────────────────────┐        ┌──────────────┐
│ Python Script │──HTTP POST─→│ IoTStreamController │        │ConstantMon.  │
│ (MON-BED-01) │             │ /api/v1/iot/stream  │        │  VitalMon.   │
│              │             │   /ingest            │        │              │
│ Generates    │             │ ┌─ authenticate      │        │ WebSocket    │
│ vitals every │             │ ├─ validate          │        │ subscription │
│ 5 seconds    │             │ ├─ persist to DB     │──WS──→│ /topic/vitals│
│              │             │ ├─ WebSocket push    │        │ /{visitId}   │
│              │             │ └─ AI engine check   │        │              │
└──────────────┘             └─────────────────────┘        └──────────────┘
```

The Python script posts to the **exact same endpoint** that real ESP32 hardware
uses (`POST /api/v1/iot/stream/ingest` with `X-Device-API-Key` header). The
backend processes it identically — validates, persists, pushes via WebSocket,
and runs the AI monitoring engine.

---

## Options

| Flag         | Description                           | Default  |
| ------------ | ------------------------------------- | -------- |
| `--server`   | Server address (required)             | —        |
| `--serial`   | Device serial number                  | —        |
| `--api-key`  | Device API key                        | —        |
| `--scenario` | Vital scenario                        | `normal` |
| `--interval` | Seconds between readings              | `5.0`    |
| `--config`   | Path to multi-device JSON config file | —        |

If no `--serial` / `--api-key` / `--config` is given, the script enters
interactive mode and prompts you.

---

## RFID Reader Simulator (`rfid_simulator.py`, V95)

Simulates the registration-desk ESP32 + RFID reader without hardware. It makes the exact call the
firmware makes — `POST /api/v1/iot/rfid/tap` with the device's `X-Device-API-Key` — and prints what
the reader's screen + buzzer would show for each result.

First register an `RFID_READER` device (IoT Devices page, or `POST /api/v1/iot/devices` with
`deviceType=RFID_READER`) to get its API key.

```bash
# Tap a card — identifies the patient system-wide and surfaces them on the registrar dashboard
python rfid_simulator.py --server localhost:8080 --api-key <KEY> --card 04A2B9C1

# Demonstrate the offline state (unreachable server)
python rfid_simulator.py --server localhost:9999 --api-key <KEY> --card 04A2B9C1
```

| Result | Screen / buzzer |
|---|---|
| `FOUND` | patient name shown, positive tone — registrar confirms & opens a visit |
| `NOT_FOUND` | "register manually", distinct tone |
| `CARD_CAPTURED` | reader was armed in registration bind mode; UID captured into the form |
| offline | device shows "No connection" + error tone (server unreachable) |

| Flag | Meaning | Default |
|------|---------|---------|
| `--server`   | SmartTriage server address          | required |
| `--api-key`  | RFID reader device API key          | required |
| `--card`     | Card UID to tap                     | required |
| `--repeat`   | Number of taps to send              | `1`      |
| `--interval` | Seconds between taps (if `--repeat`) | `3.0`   |
