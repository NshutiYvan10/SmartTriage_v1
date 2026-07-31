/* ============================================================================
 *  SmartTriage — Patient Registration RFID Reader (desk device)
 * ----------------------------------------------------------------------------
 *  A shared registration-desk device. The registrar taps a patient's card;
 *  the ESP32 reads the card UID and POSTs it to the backend, which resolves
 *  the patient SYSTEM-WIDE (a card first issued at hospital A resolves the
 *  same person at hospital B) and pushes the result to the registrar's
 *  dashboard. This device gives the registrar instant LOCAL feedback
 *  (two LEDs + buzzer) while the dashboard shows the patient and the
 *  "Start visit" action.
 *
 *  TWO USES — the device behaves identically for both; the BACKEND decides:
 *   1. LOOK-UP (returning patient): tap the card → backend finds the patient →
 *      GREEN + positive tone → registrar starts a visit on screen.
 *   2. CAPTURE (new patient): on the registration form the registrar clicks
 *      "Capture card" (arms bind-mode); the next tap of a fresh card is
 *      captured — its UID is pushed straight into the form's RFID field
 *      (GREEN + confirm tone). No re-typing.
 *   A fresh card tapped WITHOUT bind-mode returns NOT_FOUND → RED + attention
 *   tone; the registrar registers the patient with that UID (shown on the
 *   dashboard / serial log).
 *
 *  The RFID tap is a SPEED LAYER. If WiFi/backend is down the device signals a
 *  clear offline state (RED + error tone); the registrar's manual search on the
 *  dashboard is unaffected. Care is never blocked by this device.
 *
 * ----------------------------------------------------------------------------
 *  HARDWARE (ESP32 dev board, 3.3 V logic) — pins MATCH THE VERIFIED bring-up
 *  test build; do not change a pin without re-checking the physical solder.
 *   - RC522 RFID reader (SPI)   VSPI
 *       SDA/SS → GPIO 5     SCK → GPIO 18
 *       MOSI   → GPIO 23    MISO → GPIO 19
 *       RST    → GPIO 22    3.3V + GND   (RC522 is 3.3 V — do NOT use 5 V)
 *   - Green LED (success)       → GPIO 2
 *   - Red LED   (denied/error)  → GPIO 4
 *   - Buzzer (passive)          → GPIO 15   (driven with tone()/PWM — a passive buzzer is
 *                                            SILENT on plain digitalWrite, it needs a square
 *                                            wave. GPIO 15 is a strapping pin, so a brief chirp
 *                                            at boot is possible — harmless. VERIFIED on 15.)
 *   - OLED SH1106 128x64 (I2C)  → SDA GPIO 32, SCL GPIO 33   (3.3 V + GND)
 *       Same panel as medical_monitor.ino, but on REMAPPED I2C pins (32/33) — the
 *       monitor's default SCL (22) is taken by the RFID RST on this build. Hardware
 *       I2C is remapped via Wire.begin(32, 33) BEFORE u8g2.begin(), matching the
 *       verified bring-up exactly. (Set HAVE_OLED to 0 to disable for debugging.)
 *
 *   Note: GPIO 2 (green LED) is the onboard LED on many ESP32 boards, so an external
 *   green LED there simply mirrors it — harmless. Every pin above is the ACTUAL
 *   soldered layout, verified on the assembled device.
 *
 *  LIBRARIES (Arduino Library Manager)
 *   - WiFi, HTTPClient        (ESP32 core, built-in)
 *   - MFRC522                 by GithubCommunity / miguelbalboa  (RC522)
 *   - ArduinoJson (v6)        by Benoit Blanchon
 *   - U8g2                    by oliver  (ONLY needed once HAVE_OLED = 1)
 *
 *  PROVISIONING (one-time, before flashing)
 *   A Hospital Admin registers this reader in the app — Admin -> IoT Devices ->
 *   Register Device, with Device Type = RFID_READER. The response shows the
 *   device API key ONCE; paste it into DEVICE_API_KEY below, then flash.
 *     (Equivalent REST call, SUPER_ADMIN / HOSPITAL_ADMIN JWT:
 *        POST /api/v1/iot/devices
 *        { "serialNumber":"RFID-DESK-001", "deviceName":"Registration Desk 1",
 *          "deviceType":"RFID_READER", "hospitalId":"<your-hospital-uuid>" }
 *      -> the response's "apiKey" is what the device sends as X-Device-API-Key.)
 *   OPTIONAL: the admin can then assign this reader to a specific registrar
 *   (IoT Devices -> the reader's card -> Registrar).
 *
 *  SECURITY NOTE (v2): this build talks PLAIN HTTP and the API key is embedded
 *  in the sketch. For production/PHI, move to HTTPS with server-cert validation
 *  (WiFiClientSecure + pinned CA, never setInsecure) and per-device key handling.
 * ==========================================================================*/

#include <WiFi.h>
#include <ESPmDNS.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <SPI.h>
#include <MFRC522.h>

// ======================== WiFi Configuration ========================
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// ======================== Server Configuration ======================
// WHERE TAPS GO — the reader posts DIRECTLY TO THE BACKEND.
//
// Both possible targets expose the SAME endpoint (POST /api/v1/iot/rfid/tap)
// and authenticate with the SAME header (X-Device-API-Key), so switching
// between them is only the three constants below — no code change.
//
//   DIRECT TO BACKEND (this configuration): the desk reader sits on the same
//     LAN as the backend, so a tap takes one hop instead of two. A tap is
//     INTERACTIVE — the registrar is standing there waiting for the patient's
//     chart to open — so removing the proxy removes both latency and a
//     dependency. (Taps were never queued on the Pi anyway: a replayed stale
//     tap would open the wrong patient, so the gateway always forwarded them
//     verbatim and never buffered them. Going direct loses nothing.)
//
//   VIA THE PI GATEWAY (previous configuration): set SERVER_MDNS_HOST to
//     "smart-triage" and the port to 8090. Use that when the Pi is the single
//     point of egress — devices on the Pi's own access point, or a venue where
//     the backend is not directly reachable from the registration desk.
//
// Either way, prefer the NAME over an address: SERVER_MDNS_HOST is resolved by
// mDNS once WiFi is up and re-resolved automatically after 3 consecutive send
// failures, so DHCP reshuffles never strand the reader. Give the hostname
// WITHOUT the ".local" suffix.
const char* SERVER_MDNS_HOST = "Nshutis-MacBook-Pro";  // backend host; "" disables name lookup
const int   SERVER_MDNS_PORT = 8080;                   // backend port (gateway would be 8090)
// Fixed fallback, used only until mDNS resolves — and if it never does. The
// backend's IP moves with the network, which is precisely why the name above
// is the primary mechanism; update this only if you need a hard-coded route.
const char* SERVER_HOST     = "http://172.20.10.4:8080";
// Pre-shared device API key — see PROVISIONING above. Sent as X-Device-API-Key.
const char* DEVICE_API_KEY  = "PASTE_RFID_READER_API_KEY_HERE";
const char* DEVICE_LABEL    = "Registration Desk";

// ======================== Pin Definitions ==========================
// These match the verified bring-up test build exactly.
#define RC522_SS_PIN   5     // RC522 SDA / SS
#define RC522_RST_PIN  22    // RC522 RST  (verified — was on 4 in an earlier draft)
#define SPI_SCK_PIN    18    // VSPI SCK
#define SPI_MISO_PIN   19    // VSPI MISO
#define SPI_MOSI_PIN   23    // VSPI MOSI
#define GREEN_LED      2     // success  (patient found / card captured)
#define RED_LED        4     // denied / not-found / error / offline
#define BUZZER_PIN     15    // passive buzzer — driven via tone()/PWM. VERIFIED on GPIO 15
                             // (a strapping pin; may emit a brief chirp at boot — harmless).

// ======================== OLED (disabled until pins confirmed) =======
// Flip HAVE_OLED to 1 and set OLED_SDA/OLED_SCL to the ACTUAL soldered GPIOs
// once you've read them off the board. SCL must NOT be 22 (RFID RST uses it).
// The SH1106 driver + software-I2C lets the panel sit on any two free GPIOs.
#define HAVE_OLED  1         // OLED confirmed present + wired (SDA 32 / SCL 33)
#define OLED_SDA   32        // verified soldered SDA
#define OLED_SCL   33        // verified soldered SCL

#if HAVE_OLED
  #include <Wire.h>
  #include <U8g2lib.h>
  // SH1106 128x64 on HARDWARE I2C, remapped to SDA=OLED_SDA / SCL=OLED_SCL via
  // Wire.begin(...) in setup() — matches the verified bring-up test exactly.
  U8G2_SH1106_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
  String clip(const String& s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "."; }
  void oledBanner(const char* a, const char* b, const char* c) {
    display.clearBuffer();
    display.setFont(u8g2_font_7x13B_tr); display.drawStr(0, 14, a);
    display.setFont(u8g2_font_6x12_tr);
    if (b && b[0]) display.drawStr(0, 36, b);
    if (c && c[0]) display.drawStr(0, 54, c);
    display.sendBuffer();
  }
  void oledShow(const char* title, const String& l1, const String& l2) {
    display.clearBuffer();
    display.setFont(u8g2_font_7x13B_tr); display.drawStr(0, 12, title);
    display.drawHLine(0, 16, 128);
    display.setFont(u8g2_font_6x12_tr);
    if (l1.length()) display.drawStr(0, 34, clip(l1, 21).c_str());
    if (l2.length()) display.drawStr(0, 52, clip(l2, 21).c_str());
    display.sendBuffer();
  }
  void oledIdle() {
    display.clearBuffer();
    display.setFont(u8g2_font_7x13B_tr); display.drawStr(0, 12, "SmartTriage");
    display.drawHLine(0, 16, 128);
    display.setFont(u8g2_font_6x12_tr);  display.drawStr(0, 34, DEVICE_LABEL);
    display.setFont(u8g2_font_7x13B_tr); display.drawStr(0, 54, "Tap patient card");
    display.sendBuffer();
  }
#else
  // No-op stubs — the app logic calls these unconditionally, so enabling the
  // OLED later needs no changes anywhere except the HAVE_OLED block above.
  inline void oledBanner(const char*, const char*, const char*) {}
  inline void oledShow(const char*, const String&, const String&) {}
  inline void oledIdle() {}
#endif

// ======================== Behaviour timing ==========================
#define RESULT_HOLD_MS   1500   // How long the result LED stays lit after a tap
#define TAP_COOLDOWN_MS  1500   // Ignore re-reads of the same card within this
#define HTTP_TIMEOUT_MS  6000   // Backend call timeout
#define WIFI_RETRY_MS    5000   // Reconnect backoff

// ======================== Objects ===================================
MFRC522 rfid(RC522_SS_PIN, RC522_RST_PIN);

// ======================== State =====================================
String lastCardUid = "";
unsigned long lastTapMs = 0;
unsigned long lastWifiTryMs = 0;
// Server discovery: current base URL (starts at the fixed fallback) +
// mDNS bookkeeping. See SERVER_MDNS_HOST above.
String serverHost = SERVER_HOST;
bool mdnsUp = false;
bool serverResolved = false;
int tapFailStreak = 0;
unsigned long lastResolveMs = 0;

void resolveServer() {
  if (SERVER_MDNS_HOST[0] == '\0' || WiFi.status() != WL_CONNECTED) return;
  if (!mdnsUp) {
    mdnsUp = MDNS.begin("st-rfid-desk");
    if (!mdnsUp) return;                       // retry on a later call
  }
  lastResolveMs = millis();
  IPAddress ip = MDNS.queryHost(SERVER_MDNS_HOST, 2500);
  if (ip != IPAddress()) {
    serverHost = "http://" + ip.toString() + ":" + String(SERVER_MDNS_PORT);
    serverResolved = true;
    tapFailStreak = 0;
    Serial.printf("[net] %s.local -> %s\n", SERVER_MDNS_HOST, serverHost.c_str());
  }
}
// True once the reader field is clear (no card). A held card must be lifted before the
// SAME UID is accepted again — stops a card left on the reader from firing duplicate taps.
bool fieldClear = true;

// ============================================================================
//  Buzzer — driven with tone() (a PWM square wave). A PASSIVE buzzer makes NO
//  sound on plain digitalWrite(HIGH) (that's DC); it needs an oscillating signal,
//  i.e. tone(). tone() also drives an ACTIVE buzzer fine, so this is correct
//  regardless of buzzer type. Outcomes are distinguished by pitch + pattern.
// ============================================================================
void beep(int freq, int ms) { tone(BUZZER_PIN, freq, ms); delay(ms + 30); noTone(BUZZER_PIN); }

// Warble = rapidly alternate two tones (siren). Same 3.3 V peak as a steady tone,
// but the sweeping pitch — landing in the ear's most-sensitive ~3–4 kHz band — is
// PERCEIVED as louder and cuts through ambient noise far better. With a fixed
// single pin at 3.3 V, this (plus sitting exactly on the resonant peak) is the most
// a passive buzzer can do without changing the voltage/wiring.
void warble(int fLo, int fHi, int totalMs, int stepMs) {
  unsigned long start = millis();
  bool hi = false;
  while (millis() - start < (unsigned long) totalMs) {
    tone(BUZZER_PIN, hi ? fHi : fLo);
    delay(stepMs);
    hi = !hi;
  }
  noTone(BUZZER_PIN);
}

// ONE knob: set BUZZER_RESONANT to your buzzer's LOUDEST frequency (run the sweep).
// Single-tone cues sit right on it (max real volume); warble cues sweep around it
// (max perceived loudness) — so tuning this one number makes everything louder.
static const int BUZZER_RESONANT = 2700;
void beepFound()    { beep(BUZZER_RESONANT - 200, 90); beep(BUZZER_RESONANT + 400, 180); }   // quick rising — positive
void beepCaptured() { beep(BUZZER_RESONANT, 200); }                                          // single strong tone — confirm
void beepNotFound() { warble(BUZZER_RESONANT - 200, BUZZER_RESONANT + 500, 800, 70); }       // bright siren — attention
void beepError()    { warble(BUZZER_RESONANT - 700, BUZZER_RESONANT + 100, 1000, 100); }     // slower low siren — error

// ============================================================================
//  LEDs
// ============================================================================
void ledsOff() { digitalWrite(GREEN_LED, LOW); digitalWrite(RED_LED, LOW); }

// Startup self-test — mirrors the verified bring-up test: blink both LEDs + buzzer twice.
void startupSelfTest() {
  for (int i = 0; i < 2; i++) {
    digitalWrite(GREEN_LED, HIGH); digitalWrite(RED_LED, HIGH);
    beep(BUZZER_RESONANT, 180);                        // loud startup beep at the resonant peak
    digitalWrite(GREEN_LED, LOW);  digitalWrite(RED_LED, LOW);
    delay(150);
  }
}

// Boot connectivity cue on the LEDs (no dedicated WiFi LED on this build).
void wifiCue(bool connected) {
  for (int i = 0; i < 2; i++) {
    digitalWrite(connected ? GREEN_LED : RED_LED, HIGH); delay(120);
    digitalWrite(connected ? GREEN_LED : RED_LED, LOW);  delay(120);
  }
  if (connected) beepCaptured(); else beepError();
}

// Show a tap outcome: light the LED, drive the buzzer, mirror to the OLED (if
// enabled) and the serial log, hold briefly, then return to idle.
void result(bool ok, const char* title, const String& l1, const String& l2, void (*toneFn)()) {
  digitalWrite(ok ? GREEN_LED : RED_LED, HIGH);
  oledShow(title, l1, l2);
  Serial.printf("[result] %s | %s | %s\n", title, l1.c_str(), l2.c_str());
  toneFn();
  delay(RESULT_HOLD_MS);
  ledsOff();
  oledIdle();
}

// ============================================================================
//  SETUP
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(200);

  pinMode(GREEN_LED, OUTPUT);
  pinMode(RED_LED, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);
  ledsOff();
  digitalWrite(BUZZER_PIN, LOW);

  startupSelfTest();

  Serial.println();
  Serial.println("================================");
  Serial.println(" SmartTriage Registration Reader");
  Serial.println("================================");

#if HAVE_OLED
  Wire.begin(OLED_SDA, OLED_SCL);   // remap HW I2C to the soldered pins BEFORE begin()
  delay(200);
  display.begin();
  oledBanner("SmartTriage", "Registration Reader", "Starting...");
#endif

  // VSPI: explicit pins to match the verified wiring.
  SPI.begin(SPI_SCK_PIN, SPI_MISO_PIN, SPI_MOSI_PIN, RC522_SS_PIN);
  rfid.PCD_Init();
  Serial.println("RC522 initialised.");

  connectWiFi();
  oledIdle();
  Serial.println("Ready. Tap a patient card...");
}

// ============================================================================
//  MAIN LOOP
// ============================================================================
void loop() {
  // Keep WiFi up; a dropped link signals offline on tap but never blocks the desk.
  if (WiFi.status() != WL_CONNECTED) {
    if (millis() - lastWifiTryMs > WIFI_RETRY_MS) { connectWiFi(); oledIdle(); }
  }

  String uid = readCardUid();
  if (uid.length() == 0) { fieldClear = true; delay(60); return; }  // no card → field is clear

  // Debounce: accept the SAME card again only after it has been lifted (fieldClear), and
  // guard genuine rapid re-reads with a short cooldown. A different card taps through at once.
  unsigned long now = millis();
  if (uid == lastCardUid && (!fieldClear || (now - lastTapMs) < TAP_COOLDOWN_MS)) return;
  fieldClear = false;
  lastCardUid = uid;
  lastTapMs = now;

  Serial.printf("Card tapped: %s\n", uid.c_str());
  handleTap(uid);
}

// ============================================================================
//  RFID — read the UID of a presented card as canonical uppercase hex
//  (no separators). Read-only cards: we only read the UID; no auth needed.
// ============================================================================
String readCardUid() {
  if (!rfid.PICC_IsNewCardPresent()) return "";
  if (!rfid.PICC_ReadCardSerial())   return "";

  String uid = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (rfid.uid.uidByte[i] < 0x10) uid += "0";
    uid += String(rfid.uid.uidByte[i], HEX);
  }
  uid.toUpperCase();

  rfid.PICC_HaltA();          // stop reading this card
  rfid.PCD_StopCrypto1();
  return uid;
}

// ============================================================================
//  Tap → backend → feedback. The backend decides look-up vs bind-capture.
// ============================================================================
void handleTap(const String& uid) {
  oledBanner("Reading card...", uid.c_str(), "");

  if (WiFi.status() != WL_CONNECTED) {
    result(false, "OFFLINE", "No connection", "Use manual search", beepError);
    return;
  }

  // Re-resolve the gateway name if taps keep failing — the address may
  // simply have moved (DHCP reshuffle on a shared/hotspot network).
  if (!serverResolved || (tapFailStreak >= 3 && millis() - lastResolveMs > 30000)) {
    resolveServer();
  }

  HTTPClient http;
  String url = serverHost + "/api/v1/iot/rfid/tap";
  http.begin(url);
  http.setTimeout(HTTP_TIMEOUT_MS);
  http.setConnectTimeout(HTTP_TIMEOUT_MS);   // bound the TCP connect too — a stuck/unroutable
                                             // backend must not block the desk loop indefinitely
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-API-Key", DEVICE_API_KEY);

  StaticJsonDocument<128> req;
  req["cardId"]   = uid;
  req["tappedAt"] = String(millis());   // device uptime ms (informational)
  String body;
  serializeJson(req, body);

  int code = http.POST(body);
  String payload = (code > 0) ? http.getString() : "";
  http.end();

  // DEBUG — shows the ACTUAL URL hit + the raw HTTPClient code, so a "BACKEND
  // DOWN" is unambiguous: a negative code is a transport error (-1 refused,
  // -11 read timeout, ...); a positive code is a real HTTP status. Confirms the
  // URL is the right IP (not a stale one). Safe to remove once it's working.
  Serial.printf("[tap] POST %s  ->  code=%d\n", url.c_str(), code);
  if (code > 0) Serial.printf("[tap] response: %s\n", payload.c_str());

  // ── Failure paths must be DISTINCT from a genuine "card not registered" ──
  // Otherwise a transient outage or server error reads as "new patient" and the
  // registrar re-registers someone who already exists (duplicate record).
  if (code <= 0) {                       // network / backend unreachable
    tapFailStreak++;                     // 3 in a row → re-resolve the gateway name
    result(false, "BACKEND DOWN", "Use manual search", "", beepError);
    return;
  }
  tapFailStreak = 0;
  if (code == 401) {                     // bad / unknown API key
    result(false, "NOT AUTHORISED", "Check device key", "", beepError);
    return;
  }
  if (code < 200 || code >= 300) {       // 4xx/5xx (e.g. 500 / 502 / 504 from app or proxy)
    result(false, "BACKEND ERROR", String("HTTP ") + code, "Try again", beepError);
    return;
  }

  StaticJsonDocument<512> res;
  DeserializationError err = deserializeJson(res, payload);
  if (err) {                             // 2xx but unparseable body — NOT a "new patient"
    result(false, "BACKEND ERROR", "Bad response", "Try again", beepError);
    return;
  }
  String outcome = String((const char*) (res["result"] | ""));

  if (outcome == "FOUND") {
    String name = String((const char*) (res["patientName"] | "Patient"));
    String dob  = String((const char*) (res["dateOfBirth"] | ""));
    String sex  = String((const char*) (res["gender"] | ""));
    String sub  = dob;
    if (sex.length()) sub += (sub.length() ? "  " : "") + sex;
    result(true, "PATIENT FOUND", name, sub.length() ? sub : String("Start visit on screen"), beepFound);
  } else if (outcome == "CARD_CAPTURED") {
    result(true, "CARD CAPTURED", uid, "Filled on the form", beepCaptured);
  } else if (outcome == "CARD_IN_USE") {
    // Tap-to-capture refused: this card already belongs to another patient. Without this
    // branch it fell through to "CARD NOT REGISTERED", which says the opposite of the truth.
    result(false, "CARD ALREADY IN USE", uid, "Use a different card", beepError);
  } else {                               // NOT_FOUND
    result(false, "CARD NOT REGISTERED", uid, "Register patient", beepNotFound);
  }
}

// ============================================================================
//  WiFi
// ============================================================================
void connectWiFi() {
  lastWifiTryMs = millis();
  Serial.printf("Connecting to WiFi: %s\n", WIFI_SSID);
  oledBanner("SmartTriage", "Connecting WiFi", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(400); Serial.print("."); attempts++;
  }
  bool ok = (WiFi.status() == WL_CONNECTED);
  Serial.println();
  if (ok) {
    Serial.printf("WiFi connected: %s\n", WiFi.localIP().toString().c_str());
    oledBanner("SmartTriage", "WiFi connected", WiFi.localIP().toString().c_str());
    resolveServer();                     // find the gateway by name right away
  } else {
    Serial.println("WiFi FAILED — manual search only");
    oledBanner("SmartTriage", "WiFi FAILED", "Manual search only");
  }
  wifiCue(ok);
}
