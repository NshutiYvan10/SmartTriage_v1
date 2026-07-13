/*
 * net.h — WiFi + SmartTriage transmission.
 *
 * Runs entirely on core 0 so a blocking HTTP round-trip can never starve
 * the 250 Hz samplers (the previous build interleaved HTTP with sampling).
 *
 * Contract (matches the backend exactly):
 *   POST {SERVER_BASE}/api/v1/iot/stream/ingest
 *     header X-Device-API-Key, body DeviceVitalPayload JSON
 *   POST {SERVER_BASE}/api/v1/iot/stream/heartbeat   (idle keepalive)
 *
 * Cadence: TX_INTERVAL_MS = 5 s — the backend's default device data
 * interval; fast enough for the bedside pipeline, cheap enough for WiFi.
 *
 * ECG: we transmit the DERIVED values (HR/RR) plus ONE representative
 * beat (~50 samples CSV) in `ecgWaveform`, not the raw 250 Hz stream —
 * the backend stores a String snapshot per reading; streaming raw would
 * be 3000 samples per 5 s window into a field designed for one cycle.
 *
 * Resilience: the monitor NEVER depends on the network — send failures
 * go to an offline ring (5 min) flushed oldest-first on reconnect, and
 * the UI keeps rendering regardless.
 *
 * SIMULATION: no vitals are EVER posted while simulating — fake numbers
 * must never enter a clinical record. We send heartbeats only.
 */
#pragma once
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <time.h>
#include "config.h"
#include "state.h"
#include "sensors.h"

struct OfflineReading {
  time_t  at;
  int16_t hr, spo2, rr, sys, dia;
  float   temp;
};

class NetLink {
public:
  void attachEcg(EcgPipeline *ecg) { ecg_ = ecg; }

  static bool provisioned() {
    return strcmp(WIFI_SSID, "YOUR_WIFI_SSID") != 0;
  }

  void taskLoop() {
    if (!provisioned()) {
      // Bench state: no WiFi attempts, no alarms — just report the fact.
      for (;;) { publishNetState(); vTaskDelay(pdMS_TO_TICKS(1000)); }
    }
    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    for (;;) {
      manageWifi();
      manageClock();

      uint32_t now = millis();
      MonitorState s = snapshotState();

      if (s.wifiUp && now - lastTxMs_ >= TX_INTERVAL_MS) {
        lastTxMs_ = now;
        if (s.simulation) {
          if (now - lastHeartbeatMs_ >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatMs_ = now;
            sendHeartbeat();
          }
        } else if (hasAnyVital(s)) {
          bool ok = sendVitals(s);
          if (ok) flushOfflineBuffer();
          else    bufferOffline(s);
        } else if (now - lastHeartbeatMs_ >= HEARTBEAT_INTERVAL_MS) {
          lastHeartbeatMs_ = now;
          sendHeartbeat();
        }
      }

      publishNetState();
      vTaskDelay(pdMS_TO_TICKS(200));
    }
  }

private:
  static bool hasAnyVital(const MonitorState &s) {
    return s.hr > 0 || s.spo2 > 0 || s.temp > 0 || s.rr > 0 || s.bpLast.valid;
  }

  // ---------------- WiFi state machine (non-blocking) ----------------
  void manageWifi() {
    bool up = WiFi.status() == WL_CONNECTED;
    if (up) { reconnectBackoffMs_ = 2000; lastReconnectMs_ = millis(); return; }
    uint32_t now = millis();
    if (now - lastReconnectMs_ >= reconnectBackoffMs_) {
      lastReconnectMs_ = now;
      reconnectBackoffMs_ = min(reconnectBackoffMs_ * 2, (uint32_t)60000);
      WiFi.disconnect();
      WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    }
  }

  void manageClock() {
    if (clockRequested_ || WiFi.status() != WL_CONNECTED) return;
    configTime(0, 0, NTP_SERVER_1, NTP_SERVER_2);   // UTC — clinical record time
    clockRequested_ = true;
  }
  static bool clockSynced() { return time(nullptr) > 1700000000; }   // sanity: after 2023

  static void isoUtc(time_t t, char *out, size_t cap) {
    struct tm tmv;
    gmtime_r(&t, &tmv);
    strftime(out, cap, "%Y-%m-%dT%H:%M:%SZ", &tmv);
  }

  // ---------------- transmission ----------------
  bool sendVitals(const MonitorState &s, const OfflineReading *buffered = nullptr) {
    JsonDocument doc;
    doc["serialNumber"]   = DEVICE_SERIAL;
    doc["sequenceNumber"] = (long)(++seq_);

    time_t at = buffered ? buffered->at : (clockSynced() ? time(nullptr) : 0);
    if (at > 0) {
      char iso[24];
      isoUtc(at, iso, sizeof(iso));
      doc["capturedAt"] = iso;
    }

    if (buffered) {
      if (buffered->hr > 0)   doc["heartRate"]       = buffered->hr;
      if (buffered->spo2 > 0) doc["spo2"]            = buffered->spo2;
      if (buffered->rr > 0)   doc["respiratoryRate"] = buffered->rr;
      if (buffered->temp > 0) doc["temperature"]     = ((int)(buffered->temp * 10)) / 10.0;
      if (buffered->sys > 0)  { doc["systolicBp"] = buffered->sys; doc["diastolicBp"] = buffered->dia; }
    } else {
      if (s.hr > 0)   doc["heartRate"]       = (int)roundf(s.hr);
      if (s.spo2 > 0) doc["spo2"]            = (int)roundf(s.spo2);
      if (s.rr > 0)   doc["respiratoryRate"] = (int)roundf(s.rr);
      if (s.temp > 0) doc["temperature"]     = ((int)(s.temp * 10)) / 10.0;
      if (s.bpLast.valid) {
        // BP persists in every payload until a new manual reading replaces it.
        doc["systolicBp"]  = s.bpLast.sys;
        doc["diastolicBp"] = s.bpLast.dia;
      }
      if (s.perfusionIndex > 0) doc["spo2PerfusionIndex"] = s.perfusionIndex;
      if (s.chEcg == Chan::NO_CONTACT) doc["ecgRhythm"] = "LEADS_OFF";
      else if (ecg_ != nullptr && s.hrFromEcg) {
        char csv[ECG_EXPORT_SAMPLES * 7];
        if (ecg_->exportBeatCsv(csv, sizeof(csv)) > 0) doc["ecgWaveform"] = csv;
      }
      doc["wifiRssi"] = WiFi.RSSI();
      if (seq_ % 12 == 1) doc["firmwareVersion"] = FIRMWARE_VERSION;
    }

    String body;
    serializeJson(doc, body);

    HTTPClient http;
    http.setTimeout(HTTP_TIMEOUT_MS);
    http.begin(String(SERVER_BASE) + INGEST_PATH);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-API-Key", DEVICE_API_KEY);
    int code = http.POST(body);
    bool accepted = false;
    if (code == 200) {
      // ack body: {"accepted":true,...}
      accepted = http.getString().indexOf("\"accepted\":true") >= 0;
    }
    http.end();

    if (accepted) {
      lastAckMillis_ = millis();
      lastAckEpoch_ = clockSynced() ? time(nullptr) : 0;
      txOk_++;
    } else {
      txFail_++;
    }
    return accepted;
  }

  void sendHeartbeat() {
    HTTPClient http;
    http.setTimeout(HTTP_TIMEOUT_MS);
    http.begin(String(SERVER_BASE) + HEARTBEAT_PATH);
    http.addHeader("Content-Type", "application/json");
    http.addHeader("X-Device-API-Key", DEVICE_API_KEY);
    String body = String("{\"serialNumber\":\"") + DEVICE_SERIAL + "\"}";
    int code = http.POST(body);
    if (code == 200) { lastAckMillis_ = millis(); lastAckEpoch_ = clockSynced() ? time(nullptr) : 0; }
    http.end();
  }

  // ---------------- offline resilience ----------------
  void bufferOffline(const MonitorState &s) {
    if (!clockSynced()) return;    // without a timestamp a delayed reading is clinically meaningless
    OfflineReading r;
    r.at = time(nullptr);
    r.hr = (int16_t)roundf(s.hr); r.spo2 = (int16_t)roundf(s.spo2);
    r.rr = (int16_t)roundf(s.rr); r.temp = s.temp;
    r.sys = s.bpLast.valid ? (int16_t)s.bpLast.sys : 0;
    r.dia = s.bpLast.valid ? (int16_t)s.bpLast.dia : 0;
    ring_[ringHead_] = r;
    ringHead_ = (ringHead_ + 1) % OFFLINE_BUFFER_SIZE;
    if (ringCount_ < OFFLINE_BUFFER_SIZE) ringCount_++;
  }

  void flushOfflineBuffer() {
    // Oldest-first, max 3 per cycle so a long outage drains gently.
    int flushed = 0;
    MonitorState dummy;
    while (ringCount_ > 0 && flushed < 3) {
      int oldest = (ringHead_ - ringCount_ + OFFLINE_BUFFER_SIZE) % OFFLINE_BUFFER_SIZE;
      if (!sendVitals(dummy, &ring_[oldest])) break;
      ringCount_--;
      flushed++;
    }
  }

  void publishNetState() {
    if (!stateLock()) return;
    g_state.provisioned = provisioned();
    g_state.wifiUp = WiFi.status() == WL_CONNECTED;
    g_state.wifiRssi = g_state.wifiUp ? WiFi.RSSI() : 0;
    g_state.lastAckMillis = lastAckMillis_;
    g_state.lastAckAt = lastAckEpoch_;
    g_state.backendUp = lastAckMillis_ > 0 && millis() - lastAckMillis_ < BACKEND_LOST_ALARM_MS;
    g_state.txOk = txOk_; g_state.txFail = txFail_;
    g_state.offlineBuffered = ringCount_;
    g_state.clockSynced = clockSynced();
    stateUnlock();
  }

  EcgPipeline *ecg_ = nullptr;
  uint32_t seq_ = 0, txOk_ = 0, txFail_ = 0;
  uint32_t lastTxMs_ = 0, lastHeartbeatMs_ = 0;
  uint32_t lastAckMillis_ = 0;
  time_t   lastAckEpoch_ = 0;
  uint32_t lastReconnectMs_ = 0, reconnectBackoffMs_ = 2000;
  bool clockRequested_ = false;
  OfflineReading ring_[OFFLINE_BUFFER_SIZE];
  int ringHead_ = 0, ringCount_ = 0;
};
