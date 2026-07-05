/* ============================================================================
 *  SmartTriage — Patient Registration RFID Reader (desk device)
 * ----------------------------------------------------------------------------
 *  A shared registration-desk device. The registrar taps a patient's card;
 *  the ESP32 reads the card UID and POSTs it to the backend, which resolves
 *  the patient SYSTEM-WIDE (a card first issued at hospital A resolves the
 *  same person at hospital B) and pushes the result to the registrar's
 *  dashboard. This device gives the registrar instant local feedback
 *  (OLED + buzzer) while the dashboard shows the patient and the
 *  "Start visit" action.
 *
 *  TWO USES — the device behaves identically for both; the BACKEND decides:
 *   1. LOOK-UP (returning patient): tap the card → backend finds the patient →
 *      OLED shows the name, positive tone → registrar starts a visit on screen.
 *   2. CAPTURE (new patient): on the registration form the registrar clicks
 *      "Capture card" (arms bind-mode); the next tap of a fresh card is
 *      captured — its UID is pushed straight into the form's RFID field and
 *      the OLED shows "Card captured". No re-typing.
 *   A fresh card tapped WITHOUT bind-mode returns NOT_FOUND and the OLED
 *   shows the UID so the registrar can register with it manually.
 *
 *  The RFID tap is a SPEED LAYER. If WiFi/backend is down the device shows a
 *  clear offline state; the registrar's manual search on the dashboard is
 *  unaffected. Care is never blocked by this device.
 *
 * ----------------------------------------------------------------------------
 *  HARDWARE (ESP32 dev board, 3.3 V logic)
 *   - RC522 RFID reader (SPI)      VSPI
 *       SDA/SS  → GPIO 5     SCK → GPIO 18
 *       MOSI    → GPIO 23    MISO → GPIO 19
 *       RST     → GPIO 4     3.3V + GND   (RC522 is 3.3 V — do NOT use 5 V)
 *   - SSD1306 128x64 OLED (I2C)    SDA → GPIO 21   SCL → GPIO 22
 *   - Passive buzzer               → GPIO 32
 *   - Status LED (WiFi)            → GPIO 2 (onboard)
 *
 *  LIBRARIES (Arduino Library Manager)
 *   - WiFi, HTTPClient        (ESP32 core, built-in)
 *   - MFRC522                 by GithubCommunity / miguelbalboa  (RC522)
 *   - U8g2                    by oliver  (OLED — same as medical_monitor.ino)
 *   - ArduinoJson (v6)        by Benoit Blanchon
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
 *   (IoT Devices -> the reader's card -> Registrar), so that registrar's
 *   Registration Desk highlights it as "their" reader. This is a backend/UI
 *   convenience only — the reader's tap behaviour is identical either way.
 *
 *  SECURITY NOTE (v2): this build talks PLAIN HTTP and the API key is embedded
 *  in the sketch. For production/PHI, move to HTTPS with server-cert validation
 *  (WiFiClientSecure + pinned CA, never setInsecure) and per-device key handling.
 * ==========================================================================*/

#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <SPI.h>
#include <MFRC522.h>
#include <U8g2lib.h>

// ======================== WiFi Configuration ========================
const char* WIFI_SSID     = "SanTech";
const char* WIFI_PASSWORD = "SanTech@IdeasHappen";

// ======================== Server Configuration ======================
// Base host of the SmartTriage backend (no trailing slash).
const char* SERVER_HOST     = "http://192.168.1.85:8080";
// Pre-shared device API key — see PROVISIONING above. Sent as X-Device-API-Key.
const char* DEVICE_API_KEY  = "PASTE_RFID_READER_API_KEY_HERE";
const char* DEVICE_LABEL    = "Registration Desk";

// ======================== Pin Definitions ===========================
#define RC522_SS_PIN   5     // RC522 SDA / SS
#define RC522_RST_PIN  4     // RC522 RST
#define BUZZER_PIN     32    // Passive buzzer
#define LED_WIFI       2     // Onboard LED — WiFi connected
// OLED uses hardware I2C: SDA=21, SCL=22 (ESP32 defaults)

// ======================== Behaviour timing ==========================
#define RESULT_HOLD_MS   4500   // How long a tap result stays on screen
#define TAP_COOLDOWN_MS  1500   // Ignore re-reads of the same card within this
#define HTTP_TIMEOUT_MS  6000   // Backend call timeout
#define WIFI_RETRY_MS    5000   // Reconnect backoff

// ======================== Objects ===================================
// SSD1306 128x64 via HW I2C. If your panel is an SH1106 (like the monitor),
// swap to: U8G2_SH1106_128X64_NONAME_F_HW_I2C
U8G2_SSD1306_128X64_NONAME_F_HW_I2C display(U8G2_R0, U8X8_PIN_NONE);
MFRC522 rfid(RC522_SS_PIN, RC522_RST_PIN);

// ======================== State =====================================
String lastCardUid = "";
unsigned long lastTapMs = 0;
unsigned long lastWifiTryMs = 0;
// True once the reader field is clear (no card). A held card must be lifted before the
// SAME UID is accepted again — stops a card left on the reader from firing duplicate taps.
bool fieldClear = true;

// ============================================================================
//  SETUP
// ============================================================================
void setup() {
  Serial.begin(115200);
  delay(200);

  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_WIFI, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  digitalWrite(LED_WIFI, LOW);

  display.begin();
  banner("SmartTriage", "Registration Reader", "Starting...");

  SPI.begin();            // VSPI: SCK=18, MISO=19, MOSI=23
  rfid.PCD_Init();
  Serial.println("RC522 initialised.");

  connectWiFi();
  startupChime();
  showIdle();
}

// ============================================================================
//  MAIN LOOP
// ============================================================================
void loop() {
  // Keep WiFi up; a dropped link shows offline but never blocks the desk.
  if (WiFi.status() != WL_CONNECTED) {
    digitalWrite(LED_WIFI, LOW);
    if (millis() - lastWifiTryMs > WIFI_RETRY_MS) { connectWiFi(); showIdle(); }
  } else {
    digitalWrite(LED_WIFI, HIGH);
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
  banner("Reading card...", uid.c_str(), "");

  if (WiFi.status() != WL_CONNECTED) {
    beepError();
    showResult("OFFLINE", "No connection", "Use manual search", uid);
    holdThenIdle();
    return;
  }

  HTTPClient http;
  String url = String(SERVER_HOST) + "/api/v1/iot/rfid/tap";
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

  // ── Failure paths must be DISTINCT from a genuine "card not registered" ──
  // Otherwise a transient outage or server error reads as "new patient" and the
  // registrar re-registers someone who already exists (duplicate record).
  if (code <= 0) {                       // network / backend unreachable
    beepError();
    showResult("BACKEND DOWN", "Use manual search", "", uid);
    holdThenIdle();
    return;
  }
  if (code == 401) {                     // bad / unknown API key
    beepError();
    showResult("NOT AUTHORISED", "Check device key", "", uid);
    holdThenIdle();
    return;
  }
  if (code < 200 || code >= 300) {       // 4xx/5xx (e.g. 500 / 502 / 504 from app or proxy)
    beepError();
    showResult("BACKEND ERROR", String("HTTP ") + code, "Try again", uid);
    holdThenIdle();
    return;
  }

  StaticJsonDocument<512> res;
  DeserializationError err = deserializeJson(res, payload);
  if (err) {                             // 2xx but unparseable body — NOT a "new patient"
    beepError();
    showResult("BACKEND ERROR", "Bad response", "Try again", uid);
    holdThenIdle();
    return;
  }
  String result = String((const char*) (res["result"] | ""));

  if (result == "FOUND") {
    String name = String((const char*) (res["patientName"] | "Patient"));
    String dob  = String((const char*) (res["dateOfBirth"] | ""));
    String sex  = String((const char*) (res["gender"] | ""));
    String sub  = dob;
    if (sex.length()) sub += (sub.length() ? "  " : "") + sex;
    beepFound();
    showResult("PATIENT FOUND", name, sub.length() ? sub : String("Start visit on screen"), "");
  } else if (result == "CARD_CAPTURED") {
    beepCaptured();
    showResult("CARD CAPTURED", uid, "Filled on the form", "");
  } else {                               // NOT_FOUND (or unparseable)
    beepNotFound();
    showResult("CARD NOT REGISTERED", uid, "Register patient", "");
  }
  holdThenIdle();
}

// ============================================================================
//  WiFi
// ============================================================================
void connectWiFi() {
  lastWifiTryMs = millis();
  Serial.printf("Connecting to WiFi: %s\n", WIFI_SSID);
  banner("SmartTriage", "Connecting WiFi", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 30) {
    delay(400); Serial.print("."); attempts++;
  }
  if (WiFi.status() == WL_CONNECTED) {
    digitalWrite(LED_WIFI, HIGH);
    Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());
    banner("SmartTriage", "WiFi connected", WiFi.localIP().toString().c_str());
    delay(600);
  } else {
    digitalWrite(LED_WIFI, LOW);
    Serial.println("\nWiFi FAILED");
    banner("SmartTriage", "WiFi FAILED", "Manual search only");
    delay(600);
  }
}

// ============================================================================
//  OLED helpers (U8g2 full-buffer)
// ============================================================================
void showIdle() {
  display.clearBuffer();
  display.setFont(u8g2_font_7x13B_tr);
  display.drawStr(0, 12, "SmartTriage");
  display.drawHLine(0, 16, 128);
  display.setFont(u8g2_font_6x12_tr);
  display.drawStr(0, 34, DEVICE_LABEL);
  display.setFont(u8g2_font_7x13B_tr);
  display.drawStr(0, 54, "Tap patient card");
  // WiFi dot bottom-right
  display.setFont(u8g2_font_5x7_tr);
  display.drawStr(96, 63, WiFi.status() == WL_CONNECTED ? "online" : "OFFLINE");
  display.sendBuffer();
}

// A titled result: bold title, then up to two detail lines, optional 4th line.
void showResult(const char* title, const String& l1, const String& l2, const String& l3) {
  display.clearBuffer();
  display.setFont(u8g2_font_7x13B_tr);
  display.drawStr(0, 12, title);
  display.drawHLine(0, 16, 128);
  display.setFont(u8g2_font_6x12_tr);
  if (l1.length()) display.drawStr(0, 32, clip(l1, 21).c_str());
  if (l2.length()) display.drawStr(0, 48, clip(l2, 21).c_str());
  if (l3.length()) { display.setFont(u8g2_font_5x7_tr); display.drawStr(0, 62, clip(l3, 25).c_str()); }
  display.sendBuffer();
}

void banner(const char* a, const char* b, const char* c) {
  display.clearBuffer();
  display.setFont(u8g2_font_7x13B_tr);
  display.drawStr(0, 14, a);
  display.setFont(u8g2_font_6x12_tr);
  if (b && b[0]) display.drawStr(0, 36, b);
  if (c && c[0]) display.drawStr(0, 54, c);
  display.sendBuffer();
}

String clip(const String& s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "."; }

void holdThenIdle() {
  unsigned long t = millis();
  // Hold the result, but stay responsive to WiFi LED updates.
  while (millis() - t < RESULT_HOLD_MS) {
    digitalWrite(LED_WIFI, WiFi.status() == WL_CONNECTED ? HIGH : LOW);
    delay(80);
  }
  showIdle();
}

// ============================================================================
//  Buzzer — distinct tones per outcome (passive buzzer via tone()).
// ============================================================================
void beep(int freq, int ms) { tone(BUZZER_PIN, freq, ms); delay(ms + 20); }

void startupChime() { beep(880, 90); beep(1320, 120); }
void beepFound()    { beep(1200, 90); beep(1600, 140); }          // rising, positive
void beepCaptured() { beep(1400, 120); }                          // single confirm
void beepNotFound() { beep(500, 180); beep(400, 220); }           // low, descending
void beepError()    { beep(300, 300); }                           // long low buzz
