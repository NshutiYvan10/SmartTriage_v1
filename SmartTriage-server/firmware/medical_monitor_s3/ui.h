/*
 * ui.h — five-page touch UI on TFT_eSPI.
 *
 * v3.1.0 — LIGHT CLINICAL THEME + gesture overhaul + stored calibration.
 *
 *   Theme: white background, near-black ink, dark-green/amber/red value
 *   bands. The 3.0.x black theme washed out on this panel; the light
 *   palette below is picked for RGB565 readability on white (every
 *   colour here was chosen so text passes contrast on TFT_WHITE).
 *
 *   Touch: three fixes that travel together —
 *     1. Calibration is now STORED IN FLASH (NVS) and set from a
 *        ten-second on-device routine ("CALIBRATE TOUCH", Device page).
 *        The compiled-in TOUCH_CAL is only a fallback default; its X
 *        span covers ~20% of the ADC range, which is exactly why only
 *        one strip of the panel responded and buttons missed.
 *     2. Swipes fire MID-DRAG the moment the finger travels SWIPE_MIN_PX,
 *        in either direction, from anywhere on the page — no waiting for
 *        release. While a finger is down the touch is tracked every
 *        frame (idle polling stays at 20 Hz behind the bounded raw-Z
 *        pre-gate that keeps TFT_eSPI's unbounded validTouch loop from
 *        starving the watchdog — proven on this hardware).
 *     3. A quiet click on every page change confirms the gesture.
 *
 * Flicker-free by construction (unchanged from 3.0.x):
 *   - numbers render into a small reused sprite and are pushed only
 *     when their text actually changed;
 *   - waveforms scroll with the classic monitor "erase-ahead cursor";
 *   - static chrome is drawn once per page entry;
 *   - banner/buttons/progress repaint only on a real content change.
 *
 * Pages: 1 Dashboard · 2 Waveforms · 3 Trends · 4 Blood Pressure · 5 Device.
 * Navigation: horizontal swipe anywhere (both directions), or tap the
 * bottom strip (left half = previous, right half = next). The alarm
 * banner overlays every page; tapping it silences the buzzer for 2 min.
 */
#pragma once
#include <TFT_eSPI.h>
#include <Preferences.h>
#include <WiFi.h>
#include <time.h>
#include "esp_task_wdt.h"
#include "config.h"
#include "state.h"
#include "alarms.h"

// ---------- light clinical palette (RGB565, tuned for white bg) ----------
#define UI_BG      TFT_WHITE
#define UI_INK     0x18E3   // near-black slate — primary text
#define UI_MUTED   0x632C   // mid grey — labels, secondary text
#define UI_FAINT   0xC618   // light grey — frames, disabled, inactive dots
#define UI_CARD    0xF7BE   // very light grey — vitals tile fill (premium card)
#define UI_GOOD    0x0400   // dark green — in-range values (LSB=0)
#define UI_WARN    0xCBC0   // dark amber — warning band (readable on white)
#define UI_CRIT    0xF800   // red — critical band / alarms
#define UI_PLETH   0x0333   // deep teal — accent (LSB=1)
#define UI_ACCENT  0x0333
#define UI_VIOLET  0x780F   // trends: RESP
#define UI_BANNER  0xEF5D   // idle banner strip — very light grey

// ---------- scope panes (waveforms render like a real monitor) ----------
// The clinical reference look — and the one the SmartTriage Monitoring
// page uses — is a bright phosphor trace on a near-black scope, not a
// dark line on white. Waveform panes therefore run their own dark
// palette even though the rest of the UI stays light.
#define UI_SCOPE       0x0000   // waveform pane background (black)
#define UI_SCOPE_GRID  0x29A6   // faint scope grid (dark grey-green)
#define UI_TRACE_ECG   0x07E0   // phosphor green  (LSB=0 — trace slot key)
#define UI_TRACE_PLETH 0x07FF   // cyan            (LSB=1 — trace slot key)
// NOTE: the two trace colors deliberately differ in bit 0 — drawTrace
// keys its per-trace state (cursor, last-y, gain) on (color & 1).

enum class Page : uint8_t { DASH = 0, WAVE, TREND, BP, DEVICE, COUNT };

class UiController {
public:
  void begin() {
    tft_.init();
    tft_.setRotation(1);
    // Hand the BP cycle a way to detach/reattach the display SPI driver
    // cleanly around its exclusive-bus measurement (see bp.h / state.h).
    g_tftSpi = &tft_.getSPIinstance();

    // Touch calibration: prefer the values captured on THIS panel by the
    // on-device routine (NVS survives reboots and reflashes); fall back
    // to the compiled-in default only when none are stored yet.
    uint16_t defCal[5] = TOUCH_CAL;
    memcpy(cal_, defCal, sizeof(cal_));
    prefs_.begin("touch", false);
    if (prefs_.getBytesLength("cal") == sizeof(cal_)) {
      uint16_t stored[5];
      prefs_.getBytes("cal", stored, sizeof(stored));
      // Boot-time plausibility gate (v3.6.2) — the field failure this
      // guards against actually happened: a calibration run with bad
      // taps stored a 13-count X span, and because the overshoot
      // rejection discards any touch mapping far off-screen, EVERY
      // genuine press was silently dropped — a fully dead panel from a
      // bad NVS entry that survived reflashes. A working panel spans
      // hundreds-to-thousands of counts per axis; anything narrower is
      // garbage: discard it, fall back to the compiled default, and say
      // so loudly. (v3.6.1 blocks STORING such a cal; this heals
      // devices poisoned before that guard existed.)
      uint16_t sx = stored[0] > stored[1] ? stored[0] - stored[1] : stored[1] - stored[0];
      uint16_t sy = stored[2] > stored[3] ? stored[2] - stored[3] : stored[3] - stored[2];
      if (sx < 500 || sy < 500) {
        prefs_.remove("cal");
        prefs_.remove("trim");
        Serial.printf("[touch] stored calibration { %u, %u, %u, %u, %u } is IMPLAUSIBLE "
                      "(span x=%u y=%u, need >=500) — DISCARDED, using config.h default. "
                      "Re-run CALIBRATE TOUCH (Device page, or serial 'cal touch run').\n",
                      stored[0], stored[1], stored[2], stored[3], stored[4], sx, sy);
      } else {
        memcpy(cal_, stored, sizeof(cal_));
        calFromNvs_ = true;
        Serial.printf("[touch] using stored calibration { %u, %u, %u, %u, %u }\n",
                      cal_[0], cal_[1], cal_[2], cal_[3], cal_[4]);
      }
    } else {
      Serial.println("[touch] no stored calibration — using config.h default. "
                     "Run CALIBRATE TOUCH on the Device page.");
    }
    if (prefs_.getBytesLength("trim") == sizeof(trim_)) {
      prefs_.getBytes("trim", trim_, sizeof(trim_));
      Serial.printf("[touch] using stored trim %+d,%+d\n", trim_[0], trim_[1]);
    }

    W = tft_.width(); H = tft_.height();
    BANNER_H = 26;
    val_.setColorDepth(8);
    tft_.fillScreen(UI_BG);
    drawChrome();
  }

  void frame() {
    // Watchdog heartbeat: a frame ATTEMPT is liveness (during a BP cycle
    // the measurement task owns the bus and every frame legitimately
    // skips at the mutex). A task frozen INSIDE a frame stops feeding
    // and the WDT reboots the monitor — a self-recovering monitor beats
    // a bricked one at a patient's bedside. (Seen frozen twice live.)
    esp_task_wdt_reset();
    frameStarts = frameStarts + 1;
    stage = 1;
    // Own the shared SPI wire for the whole frame (drawing + touch read):
    // GPIO 12 doubles as the cuff-pressure clock, and the BP task borrows
    // it between frames. Skipping a frame under contention is invisible;
    // clocking the sensor mid-draw is not.
    if (xSemaphoreTake(g_spiBusMutex, pdMS_TO_TICKS(100)) != pdTRUE) return;
    stage = 2;
    MonitorState s = snapshotState();
    stage = 3;
    handleTouch(s);

    // On-device touch calibration (requested from the Device page). Runs
    // here, inside the frame's mutex hold: the calibration owns the whole
    // display + touch for as long as the user takes to tap four corners.
    // Safe because it is only reachable while the BP cycle is idle (the
    // idle BP task never touches the shared bus), and no watchdog watches
    // this task.
    if (calRequested_) {
      calRequested_ = false;
      runTouchCalibration();
    }
    stage = 4;

    if (pageDirty_) {
      tft_.fillScreen(UI_BG);
      drawChrome();
      pageDirty_ = false;
      forceRedraw_ = true;
      waveInit_ = false;
    }
    stage = 5;

    drawBanner(s);
    stage = 6;

    switch (page_) {
      case Page::DASH:   drawDashboard(s); break;
      case Page::WAVE:   drawWaveforms(s); break;
      case Page::TREND:  drawTrends(s);    break;
      case Page::BP:     drawBpPage(s);    break;
      case Page::DEVICE: drawDevice(s);    break;
      default: break;
    }
    forceRedraw_ = false;
    xSemaphoreGive(g_spiBusMutex);
    stage = 7;
    frameCount = frameCount + 1;
  }

  // Serial escape hatch (v3.6.2): with a broken/garbage calibration the
  // touchscreen can't navigate to the Device page's CALIBRATE button —
  // the only path to fixing touch required working touch. The cal
  // console can now trigger calibration ('cal touch run') or wipe the
  // stored values back to the compiled default ('cal touch reset').
  void requestTouchCalibration() { calRequested_ = true; }
  void resetTouchCalibration() {
    prefs_.remove("cal");
    prefs_.remove("trim");
    uint16_t defCal[5] = TOUCH_CAL;
    memcpy(cal_, defCal, sizeof(cal_));
    trim_[0] = trim_[1] = 0;
    tft_.setTouch(cal_);
    calFromNvs_ = false;
    Serial.println("[touch] stored calibration cleared — using config.h default");
  }

  // Liveness telemetry for the serial heartbeat:
  //   frameStarts rising, frameCount rising           → healthy
  //   frameStarts rising, frameCount 0                → every frame aborts at the mutex
  //   both frozen, stage names the last step reached  → hard-stuck in that call
  //   (1 mutex · 2 snapshot · 3 touch · 4 chrome · 5 banner · 6 page-draw · 7 done)
  volatile uint32_t frameCount = 0;
  volatile uint32_t frameStarts = 0;
  volatile uint8_t  stage = 0;

private:
  TFT_eSPI tft_;
  TFT_eSprite val_{&tft_};
  Preferences prefs_;
  bool calFromNvs_ = false;
  uint16_t cal_[5] = TOUCH_CAL;   // active calibration (NVS overrides in begin)
  int16_t  trim_[2] = {0, 0};     // centre-tap fine correction (x, y), NVS-stored

  // ---------- raw→screen mapping (v3.1.2 — we own it now) ----------
  // TFT_eSPI's getTouch() REJECTS any touch whose mapped coordinate falls
  // outside the screen (returns "not pressed") instead of clamping. On
  // this panel the resistive overlay maps the bottom edge slightly past
  // the LCD, so the PREV/NEXT bar sat in a DEAD STRIP no calibration
  // could cure ("touching prev/next does nothing" — observed live). We
  // therefore read RAW coordinates and do the library's own linear
  // mapping ourselves (same calData semantics), in int32, with the
  // centre-tap trim applied, CLAMPED to the screen — no dead zones.
  void mapRawToScreen(uint16_t rawX, uint16_t rawY, int32_t &xx, int32_t &yy) {
    bool rotate = cal_[4] & 0x01;
    bool invX   = cal_[4] & 0x02;
    bool invY   = cal_[4] & 0x04;
    int32_t inX = rotate ? rawY : rawX;
    int32_t inY = rotate ? rawX : rawY;
    int32_t spanX = (int32_t)cal_[1] - (int32_t)cal_[0]; if (spanX == 0) spanX = 1;
    int32_t spanY = (int32_t)cal_[3] - (int32_t)cal_[2]; if (spanY == 0) spanY = 1;
    xx = (inX - (int32_t)cal_[0]) * W / spanX;
    yy = (inY - (int32_t)cal_[2]) * H / spanY;
    if (invX) xx = W - xx;
    if (invY) yy = H - yy;
    xx += trim_[0]; yy += trim_[1];
  }

  // Throttled diagnostics: proves (or clears) the electrical-noise theory
  // in a captured serial log without flooding it.
  uint32_t lastPhantomLogMs_ = 0;
  void logPhantom(uint16_t rx, uint16_t ry) {
    uint32_t now = millis();
    if (now - lastPhantomLogMs_ < 15000) return;   // proven filter — keep logs readable
    lastPhantomLogMs_ = now;
    Serial.printf("[touch] phantom rejected (raw %u,%u)\n", rx, ry);
  }

  // Bounded touch read: raw-Z pre-gate, two raw samples that must agree
  // (a fixed-cost stand-in for TFT_eSPI's unbounded validTouch loop,
  // which wedged this panel), then PHANTOM REJECTION (v3.1.4):
  //
  // With the cuff module attached, its ADC shares the display/touch
  // clock wire (GPIO 12) and the bus picks up bursts of electrical
  // noise. Those bursts read as rail-ish raw values which map far
  // off-screen — and v3.1.2's unconditional clamp parked them ON A
  // CORNER, where the bottom-left one is the PREV zone: the monitor
  // "walked backwards on its own" (observed live). Real fingers
  // slightly past the panel edge overshoot by a few pixels; garbage
  // overshoots by hundreds. So: clamp small overshoot (keeps the
  // bottom bar alive), reject big overshoot and rail raw values, and
  // require pressure to still be present after the coordinate reads.
  bool readTouch(uint16_t zGate, uint16_t &sx, uint16_t &sy) {
    if (tft_.getTouchRawZ() <= zGate) return false;
    uint16_t rx1, ry1, rx2, ry2;
    tft_.getTouchRaw(&rx1, &ry1);
    tft_.getTouchRaw(&rx2, &ry2);
    if (abs((int)rx1 - (int)rx2) > 40 || abs((int)ry1 - (int)ry2) > 40) return false;
    uint16_t rx = (uint16_t)((rx1 + rx2) / 2), ry = (uint16_t)((ry1 + ry2) / 2);
    if (rx < 60 || rx > 4030 || ry < 60 || ry > 4030) { logPhantom(rx, ry); return false; }
    if (tft_.getTouchRawZ() <= zGate) return false;   // pressure gone = burst, not finger
    int32_t xx, yy;
    mapRawToScreen(rx, ry, xx, yy);
    if (xx < -30 || xx > W + 30 || yy < -30 || yy > H + 30) { logPhantom(rx, ry); return false; }
    sx = (uint16_t)constrain(xx, (int32_t)0, (int32_t)(W - 1));
    sy = (uint16_t)constrain(yy, (int32_t)0, (int32_t)(H - 1));
    return true;
  }
  int W = 480, H = 320, BANNER_H = 26;
  Page page_ = Page::DASH;
  bool pageDirty_ = true, forceRedraw_ = true, waveInit_ = false;

  // ---------- shared chrome: bottom navigation bar ----------
  // v3.1.1 — real touch targets. The v3.1.0 "< prev / next >" labels sat
  // at the extreme bottom edge while the tap zone floated above them, so
  // users had to press "a bit on top of" the text (and resistive panels
  // are least accurate at the very edge). Now: a 26 px bar with PREV /
  // NEXT chips whose HIT zones are the full left/right thirds of the
  // bottom 34 px — much bigger than the visuals, per touch-target
  // practice. The current page's name lives in the top banner.
  void drawChrome() {
    int barY = H - 26;
    tft_.fillRect(0, barY, W, 26, UI_BANNER);
    tft_.drawFastHLine(0, barY, W, UI_FAINT);
    tft_.setTextColor(UI_ACCENT, UI_BANNER);
    tft_.setTextDatum(ML_DATUM);
    tft_.drawString("<  PREV", 10, barY + 13, 2);
    tft_.setTextDatum(MR_DATUM);
    tft_.drawString("NEXT  >", W - 10, barY + 13, 2);
    tft_.setTextDatum(TL_DATUM);
    // page dots, centred in the bar
    int dots = (int)Page::COUNT;
    int cx = W / 2 - dots * 12 / 2 + 6;
    for (int i = 0; i < dots; i++) {
      tft_.fillCircle(cx + i * 12, barY + 13, 3, i == (int)page_ ? UI_ACCENT : UI_FAINT);
    }
  }

  // ---------- alarm / sim banner (top strip, every page) ----------
  // Redrawn ONLY when its rendered content actually changes (signature
  // compare) — an every-frame repaint both flickered visibly and burned
  // ~25 ms of SPI per frame.
  char bannerSig_[120] = "";

  void drawBanner(const MonitorState &s) {
    uint32_t now = millis();
    bool critical = s.alarms.any();
    bool flash = (now / 400) % 2 == 0;
    bool silenced = s.alarmSilencedUntil != 0 && now < s.alarmSilencedUntil;

    char text[96] = "";
    const char *link = s.backendUp ? "LINK OK"
                     : s.wifiUp ? "NO SERVER"
                     : s.provisioned ? "NO WIFI" : "NOT PROVISIONED";
    int minute = -1;
    if (s.clockSynced) {
      time_t t = time(nullptr); struct tm tmv; localtime_r(&t, &tmv);
      minute = tmv.tm_hour * 100 + tmv.tm_min;
    }
    int kind = critical ? 2 : s.simulation ? 1 : 0;
    if (critical) AlarmManager::describe(s.alarms, text, sizeof(text));

    char sig[120];
    snprintf(sig, sizeof(sig), "%d|%d|%d|%d|%d|%s|%s",
             kind, (critical && flash) ? 1 : 0, silenced ? 1 : 0, minute, (int)page_, link, text);
    if (!forceRedraw_ && strcmp(sig, bannerSig_) == 0) return;
    strlcpy(bannerSig_, sig, sizeof(bannerSig_));

    if (critical) {
      uint16_t bg = flash ? UI_CRIT : 0x8000;
      tft_.fillRect(0, 0, W, BANNER_H, bg);
      tft_.setTextColor(TFT_WHITE, bg);
      tft_.setTextDatum(ML_DATUM);
      tft_.drawString(text, 6, BANNER_H / 2, 2);
      tft_.setTextDatum(MR_DATUM);
      tft_.drawString(silenced ? "SILENCED" : "TAP TO SILENCE", W - 6, BANNER_H / 2, 1);
      tft_.setTextDatum(TL_DATUM);
    } else if (s.simulation) {
      uint16_t bg = TFT_ORANGE;
      tft_.fillRect(0, 0, W, BANNER_H, bg);
      tft_.setTextColor(TFT_BLACK, bg);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString("SIMULATION MODE — DEMO DATA, NOT TRANSMITTED", W / 2, BANNER_H / 2, 2);
      tft_.setTextDatum(TL_DATUM);
    } else {
      tft_.fillRect(0, 0, W, BANNER_H, UI_BANNER);
      tft_.setTextColor(UI_ACCENT, UI_BANNER);
      tft_.setTextDatum(ML_DATUM);
      tft_.drawString("SMARTTRIAGE MONITOR", 6, BANNER_H / 2, 2);
      // current page name, centred (moved here from the bottom strip)
      static const char *names[] = {"VITALS", "WAVES", "TRENDS", "BLOOD PRESSURE", "DEVICE"};
      tft_.setTextColor(UI_INK, UI_BANNER);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString(names[(int)page_], W / 2, BANNER_H / 2, 2);
      char right[44];
      if (minute >= 0) snprintf(right, sizeof(right), "%02d:%02d  %s", minute / 100, minute % 100, link);
      else             snprintf(right, sizeof(right), "--:--  %s", link);
      tft_.setTextColor(s.backendUp ? UI_GOOD : UI_WARN, UI_BANNER);
      tft_.setTextDatum(MR_DATUM);
      tft_.drawString(right, W - 6, BANNER_H / 2, 2);
      tft_.setTextDatum(TL_DATUM);
    }
  }

  // ---------- value cell: sprite-rendered, redrawn only on change ----------
  struct Cell { char last[28] = ""; };
  Cell cells_[24];   // 0-13 pages · 14-21 BP-history rows

  // Fonts: 2/4 carry text (full glyph set); 6 carries the premium large
  // numerics (48 px digits + '.' ':' '-' — User_Setup loads it). Font 6
  // has NO '/' or '%', so BP strings stay on font 4 scaled, and units
  // live in the tile header instead of beside the number. `size` scales
  // any font up. `bg` lets a cell live on a card or scope pane.
  void cell(int id, int x, int y, int w, int h, const char *txt,
            uint16_t color, uint8_t font, uint8_t size = 1, uint16_t bg = UI_BG) {
    if (!forceRedraw_ && strcmp(cells_[id].last, txt) == 0) return;
    strlcpy(cells_[id].last, txt, sizeof(cells_[id].last));
    val_.createSprite(w, h);
    val_.fillSprite(bg);
    val_.setTextColor(color, bg);
    val_.setTextDatum(MC_DATUM);
    val_.setTextSize(size);
    val_.drawString(txt, w / 2, h / 2, font);
    val_.setTextSize(1);
    val_.pushSprite(x, y);
    val_.deleteSprite();
  }

  static uint16_t bandColor(float v, float warnLo, float warnHi, float critLo, float critHi) {
    if (v <= 0) return UI_MUTED;
    if (v < critLo || v > critHi) return UI_CRIT;
    if (v < warnLo || v > warnHi) return UI_WARN;
    return UI_GOOD;
  }

  // =====================================================================
  //  PAGE 1 — dashboard
  // =====================================================================
  // Per-tile band-color accent bar cache (repaint only on band change).
  uint16_t tileBand_[4] = {1, 1, 1, 1};

  void drawDashboard(const MonitorState &s) {
    int top = BANNER_H + 4;
    int tileW = (W - 18) / 2, tileH = (H - top - 108) / 2;
    struct Tile { const char *label; const char *unit; };
    static const Tile tiles[4] = {{"HR", "bpm"}, {"SpO2", "%"}, {"TEMP", "C"}, {"RESP", "/min"}};

    if (forceRedraw_) {
      for (int i = 0; i < 4; i++) {
        int x = 6 + (i % 2) * (tileW + 6), y = top + (i / 2) * (tileH + 6);
        tft_.fillRoundRect(x, y, tileW, tileH, 10, UI_CARD);
        tft_.setTextColor(UI_MUTED, UI_CARD);
        tft_.drawString(tiles[i].label, x + 16, y + 8, 4);
        tft_.setTextDatum(TR_DATUM);
        tft_.drawString(tiles[i].unit, x + tileW - 12, y + 12, 2);
        tft_.setTextDatum(TL_DATUM);
        tileBand_[i] = 1;   // force accent repaint
      }
      int by = top + 2 * (tileH + 6);
      tft_.fillRoundRect(6, by, W - 12, H - by - 46, 10, UI_CARD);
      tft_.setTextColor(UI_MUTED, UI_CARD);
      tft_.drawString("BP", 16, by + 8, 4);
      tft_.setTextColor(UI_FAINT, UI_CARD);
      tft_.drawString("last reading", 62, by + 14, 2);
    }

    // Colored left accent bar per tile — the at-a-glance band indicator
    // (green in-range / amber warning / red critical), same trick the
    // SmartTriage vitals tiles use.
    auto accent = [&](int i, uint16_t c) {
      if (tileBand_[i] == c) return;
      tileBand_[i] = c;
      int x = 6 + (i % 2) * (tileW + 6), y = top + (i / 2) * (tileH + 6);
      tft_.fillRoundRect(x, y + 6, 5, tileH - 12, 2, c);
    };

    char t[20];
    // HR (+ source/quality tag). A weak or stale ECG signal DIMS the
    // number and says so — a value flickering between "96" and "--" at
    // a bedside helps nobody, and a confidently-wrong number is worse.
    bool hrWeak = s.hrFromEcg && s.ecgQuality <= 1;
    uint16_t hrC = hrWeak ? UI_MUTED
                          : bandColor(s.hr, ALM_HR_WARN_LOW, ALM_HR_WARN_HIGH, ALM_HR_CRIT_LOW, ALM_HR_CRIT_HIGH);
    snprintf(t, sizeof(t), s.hr > 0 ? "%.0f" : "--", s.hr);
    cell(0, 6 + 14, top + 26, tileW - 28, tileH - 48, t, hrC, 6, 1, UI_CARD);
    accent(0, hrC);
    cell(10, 6 + 14, top + tileH - 22, tileW - 28, 18,
         s.hr <= 0 ? "" : !s.hrFromEcg ? "PULSE-OX"
                        : hrWeak ? "ECG - WEAK SIGNAL" : "ECG",
         hrWeak ? UI_WARN : UI_FAINT, 2, 1, UI_CARD);

    uint16_t spC = bandColor(s.spo2, ALM_SPO2_WARN, 101, ALM_SPO2_CRIT, 101);
    snprintf(t, sizeof(t), s.spo2 > 0 ? "%.0f" : "--", s.spo2);
    cell(1, 12 + tileW + 14, top + 26, tileW - 28, tileH - 48, t, spC, 6, 1, UI_CARD);
    accent(1, spC);

    // Temperature: an UNCALIBRATED contact sensor reads SKIN temp, degrees
    // below core — display it dimmed with an explicit tag, never as a
    // confident clinical value (and net.h excludes it from transmission).
    bool tempCal = g_cal.tempCalibrated();
    uint16_t tpC = !tempCal ? UI_MUTED
                            : bandColor(s.temp, ALM_TEMP_WARN_LOW, ALM_TEMP_WARN_HIGH, ALM_TEMP_CRIT_LOW, ALM_TEMP_CRIT_HIGH);
    snprintf(t, sizeof(t), s.temp > 0 ? "%.1f" : "--.-", s.temp);
    cell(2, 6 + 14, top + tileH + 6 + 26, tileW - 28, tileH - 48, t, tpC, 6, 1, UI_CARD);
    accent(2, tempCal ? tpC : UI_WARN);
    cell(22, 6 + 14, top + 2 * tileH + 6 - 22, tileW - 28, 18,
         (s.temp > 0 && !tempCal) ? "UNCAL - not transmitted" : "",
         UI_WARN, 2, 1, UI_CARD);

    uint16_t rrC = bandColor(s.rr, ALM_RR_WARN_LOW, ALM_RR_WARN_HIGH, 1, 99);
    snprintf(t, sizeof(t), s.rr > 0 ? "%.0f" : "--", s.rr);
    cell(3, 12 + tileW + 14, top + tileH + 6 + 26, tileW - 28, tileH - 48, t, rrC, 6, 1, UI_CARD);
    accent(3, rrC);

    // BP strip
    int by = top + 2 * (tileH + 6);
    if (s.bpLast.valid) {
      char when[28] = "time unsynced";
      if (s.bpLast.at > 0) {
        struct tm tmv; localtime_r(&s.bpLast.at, &tmv);
        snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min);
      }
      snprintf(t, sizeof(t), "%d/%d", s.bpLast.sys, s.bpLast.dia);
      uint16_t c = (s.bpLast.sys > ALM_SYS_CRIT_HIGH || s.bpLast.sys < ALM_SYS_CRIT_LOW) ? UI_CRIT : UI_GOOD;
      cell(4, 70, by + 24, 170, 40, t, c, 4, 2, UI_CARD);
      char meta[56];
      snprintf(meta, sizeof(meta), "MAP %d   at %s%s", s.bpLast.map, when, s.bpCalibrated ? "" : "  UNCAL");
      cell(5, 250, by + 34, W - 266, 20, meta, UI_MUTED, 2, 1, UI_CARD);
    } else {
      cell(4, 70, by + 24, 170, 40, "--/--", UI_MUTED, 4, 2, UI_CARD);
      cell(5, 250, by + 34, W - 266, 20, "no reading - use BP page", UI_MUTED, 2, 1, UI_CARD);
    }

    // per-channel status chips
    char chips[72];
    snprintf(chips, sizeof(chips), "SPO2 %s   TEMP %s   ECG %s   CUFF %s",
             chanTxt(s.chSpo2), chanTxt(s.chTemp), chanTxt(s.chEcg), chanTxt(s.chBp));
    cell(6, 6, H - 44, W - 12, 16, chips, UI_MUTED, 2);
  }

  static const char *chanTxt(Chan c) {
    switch (c) {
      case Chan::OK:         return "OK";
      case Chan::NO_CONTACT: return "OFF-PT";
      case Chan::FAULT:      return "FAULT";
      default:               return "N/A";
    }
  }

  // =====================================================================
  //  PAGE 2 — waveforms (scrolling erase-ahead traces)
  // =====================================================================
  uint16_t ecgReadHead_ = 0, plethReadHead_ = 0;
  // ONE SWEEP CURSOR PER TRACE. A single shared cursor was the cause of the
  // "ECG goes unstable the moment a finger is on the pulse-ox" field report:
  // the pleth trace only draws while a finger is detected, and it was
  // advancing the SAME cursor as the ECG. Every pleth sample therefore stole
  // a column from the ECG's sweep — the ECG's erase-ahead skipped those
  // columns (leaving pixels from the previous sweep under the live trace)
  // and its time base jumped from 4.0 to ~2.9 ms/pixel. Indexed like
  // lastPy_/dispPeak_, by colour bit 0.
  int waveX_[2] = {0, 0};

  void drawWaveforms(const MonitorState &s) {
    int top = BANNER_H + 2;
    int numW = 96;                        // numeric column on the right
    int plotW = W - numW - 4;
    int ecgH = (H - top - 32) * 3 / 5;
    int plethH = (H - top - 32) - ecgH - 4;
    int ecgY = top, plethY = top + ecgH + 4;

    if (!waveInit_) {
      // Scope panes: black, rounded, with a faint center reference line —
      // the phosphor-on-black look of a real monitor (and the SmartTriage
      // Monitoring page this display should visually match).
      tft_.fillRect(0, top, plotW, H - top - 28, UI_BG);
      tft_.fillRoundRect(0, ecgY, plotW, ecgH, 6, UI_SCOPE);
      tft_.fillRoundRect(0, plethY, plotW, plethH, 6, UI_SCOPE);
      drawScopeGrid(ecgY, ecgH, plotW);
      drawScopeGrid(plethY, plethH, plotW);
      tft_.setTextColor(UI_TRACE_ECG, UI_SCOPE);   tft_.drawString("ECG - LEAD II", 8, ecgY + 4, 2);
      tft_.setTextColor(UI_TRACE_PLETH, UI_SCOPE); tft_.drawString("PLETH - SpO2", 8, plethY + 4, 2);
      waveX_[0] = waveX_[1] = 1;
      ecgReadHead_ = g_ecgWaveHead;
      plethReadHead_ = g_plethWaveHead;
      gainReset(0); gainReset(1);   // fresh display gain per page entry
      lastPlethState_ = -1;
      waveInit_ = true;
    }

    if (!s.simulation && s.chEcg == Chan::NO_CONTACT) {
      cell(7, plotW / 2 - 90, ecgY + ecgH / 2 - 10, 180, 20, "ECG LEADS OFF", UI_CRIT, 2, 1, UI_SCOPE);
    } else {
      cell(7, plotW / 2 - 90, ecgY + ecgH / 2 - 10, 1, 1, "", UI_SCOPE, 1, 1, UI_SCOPE); // clear marker
      drawTrace(g_ecgWave, g_ecgWaveHead, ecgReadHead_, ecgY + 4, ecgH - 8, plotW, UI_TRACE_ECG);
    }

    // Pleth pane: NEVER show a stale/frozen curve for a sensor that is
    // not delivering — freeze-frames read as live data to a clinician.
    // State the truth in the pane and clear it once on transition.
    int plethState = s.simulation ? 0
                   : s.chSpo2 == Chan::OK ? 0
                   : s.chSpo2 == Chan::NO_CONTACT ? 1 : 2;
    if (plethState != lastPlethState_) {
      lastPlethState_ = plethState;
      tft_.fillRoundRect(0, plethY, plotW, plethH, 6, UI_SCOPE);
      drawScopeGrid(plethY, plethH, plotW);
      tft_.setTextColor(UI_TRACE_PLETH, UI_SCOPE);
      tft_.drawString("PLETH - SpO2", 8, plethY + 4, 2);
      plethReadHead_ = g_plethWaveHead;         // don't replay stale ring content
      waveX_[UI_TRACE_PLETH & 1] = 1;           // restart its sweep on the cleared pane
      lastPy_[UI_TRACE_PLETH & 1] = -1;
    }
    if (plethState == 0) {
      drawTrace(g_plethWave, g_plethWaveHead, plethReadHead_, plethY + 4, plethH - 8, plotW, UI_TRACE_PLETH);
    } else {
      cell(23, plotW / 2 - 110, plethY + plethH / 2 - 9, 220, 18,
           plethState == 1 ? "PLACE FINGER ON SENSOR" : "SPO2 SENSOR NOT DETECTED",
           plethState == 1 ? UI_WARN : UI_MUTED, 2, 1, UI_SCOPE);
    }

    // numerics column — large font-6 digits, aligned with each pane
    char t[16];
    bool hrWeak = s.hrFromEcg && s.ecgQuality <= 1;
    snprintf(t, sizeof(t), s.hr > 0 ? "%.0f" : "--", s.hr);
    cell(8, W - numW, ecgY + 20, numW - 4, 52, t,
         hrWeak ? UI_MUTED
                : bandColor(s.hr, ALM_HR_WARN_LOW, ALM_HR_WARN_HIGH, ALM_HR_CRIT_LOW, ALM_HR_CRIT_HIGH), 6);
    if (forceRedraw_) { tft_.setTextColor(UI_MUTED, UI_BG); tft_.drawString("HR bpm", W - numW + 8, ecgY + 2, 2); }

    snprintf(t, sizeof(t), s.spo2 > 0 ? "%.0f" : "--", s.spo2);
    cell(9, W - numW, plethY + 20, numW - 4, 52, t,
         bandColor(s.spo2, ALM_SPO2_WARN, 101, ALM_SPO2_CRIT, 101), 6);
    if (forceRedraw_) { tft_.setTextColor(UI_MUTED, UI_BG); tft_.drawString("SpO2 %", W - numW + 8, plethY + 2, 2); }
  }

  // Faint scope grid: dotted horizontal lines at 1/4, 1/2, 3/4 height.
  // Kept dotted + sparse so the erase-ahead cursor can cheaply restore
  // the column it just wiped (see drawTrace).
  void drawScopeGrid(int y, int h, int plotW) {
    for (int q = 1; q <= 3; q++) {
      int gy = y + h * q / 4;
      for (int gx = 2; gx < plotW - 2; gx += 4) tft_.drawPixel(gx, gy, UI_SCOPE_GRID);
    }
  }

  // consume new ring samples; draw one column per sample with an erase-ahead cursor.
  //
  // DISPLAY GAIN (v3.7.0) — median of recent window maxima. The previous
  // attack/decay auto-gain had a RATCHET: attack (0.5/sample) was ~1000x
  // faster than decay (0.9995/sample), so the gain tracked the noise
  // PEAK HISTORY. With old electrodes popping every few seconds, every
  // real beat rendered 1.7-2.2x too small between pops — exactly the
  // "doesn't look like a real ECG" complaint. Now: each trace collects
  // the max |sample| over a 512-sample window (~2 s @250 Hz — guaranteed
  // to contain a QRS) and the gain is the MEDIAN of the last 5 window
  // maxima. One artifact ruins one window; the median ignores it. Beat
  // height stays rock-steady, adapting to a genuine amplitude change in
  // ~4-6 s like a real monitor's auto-scale.
  struct TraceGain {
    float winMax = 0; int winN = 0;
    float hist[5] = {400, 400, 400, 400, 400}; int histN = 0;
    float gain = 400;
    void push(float a) {
      if (a > winMax) winMax = a;
      if (++winN < 512) return;
      hist[histN % 5] = max(winMax, 250.0f);           // noise floor
      histN++; winMax = 0; winN = 0;
      float s[5]; memcpy(s, hist, sizeof(s));
      for (int i = 1; i < 5; i++) { float k = s[i]; int j = i - 1;
        while (j >= 0 && s[j] > k) { s[j + 1] = s[j]; j--; } s[j + 1] = k; }
      gain = s[2];
    }
  };
  TraceGain traceGain_[2];
  void gainReset(int slot) { traceGain_[slot] = TraceGain(); }

  void drawTrace(volatile int16_t *ring, volatile uint16_t &headRef,
                 uint16_t &readHead, int y, int h, int plotW, uint16_t color) {
    uint16_t head = headRef;
    TraceGain &tg = traceGain_[color & 1];
    int budget = 40;                                   // samples per frame cap
    while (readHead != head && budget-- > 0) {
      readHead = (uint16_t)((readHead + 1) % ECG_WAVE_RING);
      int16_t v = ring[readHead];
      tg.push(fabsf((float)v));
      // Artifacts taller than the gain CLIP at the pane edge (constrain
      // below) — exactly what a real monitor does — instead of shrinking
      // every beat to make room for them.
      int py = y + h / 2 - (int)((float)v / (tg.gain * 1.25f) * (h / 2 - 2));
      py = constrain(py, y, y + h - 1);

      // erase-ahead cursor (classic monitor sweep), private to this trace
      int &x = waveX_[color & 1];
      int eraseX = (x + 6) % plotW;
      if (eraseX > 1 && eraseX < plotW - 1) {
        tft_.drawFastVLine(eraseX, y, h, UI_SCOPE);
        // restore the dotted grid pixels this column carried
        if ((eraseX & 3) == 2) {
          for (int q = 1; q <= 3; q++) tft_.drawPixel(eraseX, y - 4 + (h + 8) * q / 4, UI_SCOPE_GRID);
        }
      }

      if (x > 1 && lastPy_[color & 1] >= 0) {
        // 2-px stroke: the phosphor-bright trace reads as a solid
        // waveform instead of a hairline (drawn as two offset lines,
        // cheap enough at 40 samples/frame).
        tft_.drawLine(x - 1, lastPy_[color & 1], x, py, color);
        tft_.drawLine(x - 1, lastPy_[color & 1] + 1, x, py + 1, color);
      } else {
        tft_.drawPixel(x, py, color);
      }
      lastPy_[color & 1] = py;
      if (++x >= plotW - 1) { x = 1; lastPy_[color & 1] = -1; }
    }
  }
  int lastPy_[2] = {-1, -1};
  int lastPlethState_ = -1;

  // =====================================================================
  //  PAGE 3 — trends
  // =====================================================================
  void drawTrends(const MonitorState &s) {
    int top = BANNER_H + 4;
    int chartW = W * 3 / 5 - 10;
    int chartH = (H - top - 34) / 4 - 4;

    struct Row { const char *label; const TrendRing *r; uint16_t color; };
    Row rows[4] = {
      {"HR",   &g_trendHr,   UI_GOOD},
      {"SpO2", &g_trendSpo2, UI_PLETH},
      {"TEMP", &g_trendTemp, UI_WARN},
      {"RESP", &g_trendRr,   UI_VIOLET},
    };

    // Charts repaint only when the underlying rings actually gained a
    // point (every TREND_INTERVAL_MS) — repainting on a fixed 2 s timer
    // made all four charts blink for no data change.
    uint32_t ringSig = (uint32_t)g_trendHr.count * 131 + g_trendHr.idx
                     + (uint32_t)g_trendSpo2.idx * 7 + (uint32_t)g_trendTemp.idx * 13
                     + (uint32_t)g_trendRr.idx * 29;
    if (!forceRedraw_ && ringSig == lastTrendSig_) { drawBpHistory(s, chartW); return; }
    lastTrendSig_ = ringSig;

    for (int i = 0; i < 4; i++) {
      int y = top + i * (chartH + 4);
      tft_.fillRect(0, y, chartW + 8, chartH, UI_BG);
      tft_.drawRoundRect(4, y, chartW, chartH, 4, UI_FAINT);
      tft_.setTextColor(rows[i].color, UI_BG);
      tft_.drawString(rows[i].label, 10, y + 3, 2);

      const TrendRing *r = rows[i].r;
      if (r->count < 2) continue;
      // auto-scale to the data
      float lo = 1e9, hi = -1e9;
      for (int k = 0; k < r->count; k++) {
        float v = r->v[k];
        if (v <= 0) continue;
        lo = min(lo, v); hi = max(hi, v);
      }
      if (hi <= lo) { hi = lo + 1; }
      float pad = (hi - lo) * 0.15f + 0.1f;
      lo -= pad; hi += pad;

      int lastX = -1, lastY = -1;
      for (int k = 0; k < r->count; k++) {
        int ringIdx = (r->idx - r->count + k + TREND_POINTS) % TREND_POINTS;
        float v = r->v[ringIdx];
        if (v <= 0) { lastX = -1; continue; }
        int px = 5 + (int)((float)k / (TREND_POINTS - 1) * (chartW - 3));
        int py = y + chartH - 3 - (int)((v - lo) / (hi - lo) * (chartH - 6));
        if (lastX >= 0) tft_.drawLine(lastX, lastY, px, py, rows[i].color);
        lastX = px; lastY = py;
      }
      char lbl[16];
      snprintf(lbl, sizeof(lbl), "%.0f-%.0f", lo + pad, hi - pad);
      tft_.setTextColor(UI_MUTED, UI_BG);
      tft_.setTextDatum(TR_DATUM);
      tft_.drawString(lbl, chartW - 2, y + 3, 2);
      tft_.setTextDatum(TL_DATUM);
    }
    drawBpHistory(s, chartW);
  }

  void drawBpHistory(const MonitorState &s, int chartW) {
    int x = chartW + 14, top = BANNER_H + 4;
    if (forceRedraw_) {
      tft_.setTextColor(UI_MUTED, UI_BG);
      tft_.drawString("BP HISTORY", x, top, 2);
    }
    char line[28];
    for (int i = 0; i < BP_HISTORY_SIZE; i++) {
      if (i < s.bpHistoryCount && s.bpHistory[i].valid) {
        char when[10] = "--:--";
        if (s.bpHistory[i].at > 0) {
          struct tm tmv; localtime_r(&s.bpHistory[i].at, &tmv);
          snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min);
        }
        snprintf(line, sizeof(line), "%s  %d/%d", when, s.bpHistory[i].sys, s.bpHistory[i].dia);
      } else {
        line[0] = '\0';
      }
      // Dedicated cache slot per row — the first build rotated 3 slots
      // across 8 rows, which repainted ~5 rows EVERY frame (visible
      // churn + constant SPI load on the trends page).
      cell(14 + i, x, top + 22 + i * 20, W - x - 6, 18, line, UI_INK, 2);
    }
  }
  uint32_t lastTrendSig_ = 0xFFFFFFFF;

  // =====================================================================
  //  PAGE 4 — blood pressure
  // =====================================================================
  int btnX_, btnY_, btnW_, btnH_;

  // The content area is a STATE MACHINE with a hard clear on every state
  // change. The previous version drew its result/status cells at
  // DIFFERENT positions per state (measuring vs FAILED vs result) and
  // the cell cache only compares text — so switching states left the
  // old state's pixels underneath ("FAILED" overlapping stale text,
  // observed in the field). One fillRect per transition ends the class.
  int lastBpUiState_ = -1;

  void drawBpPage(const MonitorState &s) {
    int top = BANNER_H + 6;
    btnW_ = 210; btnH_ = 58;
    btnX_ = W / 2 - btnW_ / 2 + 30; btnY_ = H - btnH_ - 36;

    if (forceRedraw_) {
      tft_.setTextColor(UI_MUTED, UI_BG);
      tft_.drawString("BLOOD PRESSURE", 10, top, 4);
      tft_.setTextColor(UI_FAINT, UI_BG);
      tft_.drawString("oscillometric", 10, top + 28, 2);
      if (!s.bpCalibrated) {
        tft_.setTextColor(UI_WARN, UI_BG);
        tft_.setTextDatum(TR_DATUM);
        tft_.drawString("UNCALIBRATED", W - 8, top, 2);
        tft_.setTextDatum(TL_DATUM);
      }
    }

    bool busy = s.bpPhase == BpPhase::INFLATING || s.bpPhase == BpPhase::MEASURING
             || s.bpPhase == BpPhase::COMPUTING || s.bpPhase == BpPhase::ZEROING;

    int uiState = busy ? 1 : s.bpPhase == BpPhase::ERROR ? 2 : s.bpLast.valid ? 3 : 0;
    int areaY = top + 34, areaH = btnY_ - areaY - 8;
    if (forceRedraw_ || uiState != lastBpUiState_) {
      lastBpUiState_ = uiState;
      tft_.fillRect(0, areaY, W, areaH, UI_BG);
      cells_[11].last[0] = cells_[12].last[0] = cells_[13].last[0] = '\0';
    }

    // result / live area — every state draws into the SAME cleared frame
    char t[32];
    if (busy) {
      // During a REAL measurement the BP task owns the whole shared bus
      // (the pressure ADC shares the display clock and has no CS — see
      // bp.h), so this screen is drawn ONCE and then freezes until the
      // cuff releases. Draw honest static guidance, not live numbers
      // that would sit frozen mid-value. (The sim cycle does update the
      // number — it never touches the bus.)
      snprintf(t, sizeof(t), s.simulation ? "%.0f" : "MEASURING", s.cuffPressure);
      cell(11, W / 2 - 140, areaY + 8, 280, 56, t, UI_WARN, s.simulation ? 6 : 4, 1);
      cell(12, W / 2 - 150, areaY + 70, 300, 20,
           s.simulation ? "cuff mmHg (simulated)" : "display pauses while measuring",
           UI_MUTED, 2);
      cell(13, W / 2 - 160, areaY + 94, 320, 20,
           s.simulation ? "Measuring - hold still" : "hold still - press & HOLD screen to stop",
           UI_WARN, 2);
    } else if (s.bpPhase == BpPhase::ERROR) {
      cell(11, W / 2 - 150, areaY + 8, 300, 44, "FAILED", UI_CRIT, 4, 1);
      cell(12, W / 2 - 200, areaY + 60, 400, 20, s.bpError, UI_CRIT, 2);
      cell(13, W / 2 - 160, areaY + 86, 320, 20, "check cuff, then press START BP to retry", UI_MUTED, 2);
    } else if (s.bpLast.valid) {
      snprintf(t, sizeof(t), "%d/%d", s.bpLast.sys, s.bpLast.dia);
      cell(11, W / 2 - 140, areaY + 8, 280, 56, t, UI_GOOD, 4, 2);
      char meta[48];
      char when[10] = "--:--";
      if (s.bpLast.at > 0) { struct tm tmv; localtime_r(&s.bpLast.at, &tmv); snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min); }
      snprintf(meta, sizeof(meta), "MAP %d mmHg   measured %s", s.bpLast.map, when);
      cell(12, W / 2 - 150, areaY + 72, 300, 20, meta, UI_MUTED, 2);
    } else {
      cell(11, W / 2 - 150, areaY + 16, 300, 44, "--/--", UI_MUTED, 4, 2);
      cell(12, W / 2 - 170, areaY + 72, 340, 20, "wrap cuff snugly, then press START BP", UI_MUTED, 2);
    }

    // start/cancel button — repainted only when its state changes (an
    // every-frame repaint flickers and wastes SPI time). While a cycle
    // runs the button becomes CANCEL: a cuff squeezing a patient's arm
    // must always be stoppable from the screen.
    int btnState = busy ? 1 : 0;
    if (forceRedraw_ || btnState != lastBpBtn_) {
      lastBpBtn_ = btnState;
      uint16_t bc = busy ? UI_CRIT : UI_GOOD;
      tft_.fillRoundRect(btnX_, btnY_, btnW_, btnH_, 12, bc);
      tft_.setTextColor(TFT_WHITE, bc);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString(busy ? "CANCEL" : "START BP", btnX_ + btnW_ / 2, btnY_ + btnH_ / 2, 4);
      tft_.setTextDatum(TL_DATUM);
      // Pump-scale calibration (see bp.h) — maintenance, deliberately
      // DEMOTED to a small outline chip: needed once per pump module,
      // not part of the clinical flow. Hidden while a cycle runs.
      if (!busy) {
        tft_.fillRoundRect(8, btnY_ + 10, 100, btnH_ - 20, 8, UI_BG);
        tft_.drawRoundRect(8, btnY_ + 10, 100, btnH_ - 20, 8, UI_FAINT);
        tft_.setTextColor(UI_MUTED, UI_BG);
        tft_.setTextDatum(MC_DATUM);
        tft_.drawString("CAL PUMP", 58, btnY_ + btnH_ / 2, 2);
        tft_.setTextDatum(TL_DATUM);
      } else {
        tft_.fillRect(8, btnY_, 116, btnH_, UI_BG);
      }
    }
  }
  int lastBpBtn_ = -1;

  // =====================================================================
  //  PAGE 5 — device status
  // =====================================================================
  int simBtnY_ = 0;

  // Rebuilt (v3.7.0): three titled sections — CONNECTION / DEVICE /
  // CALIBRATION — with merged, readable rows instead of ten cramped
  // label:value lines, and the maintenance actions demoted to a compact
  // button row at the bottom. The calibration section makes the three
  // stored calibrations (touch / BP scale / temperature) first-class
  // status the user can SEE, instead of hidden NVS state.
  void drawDevice(const MonitorState &s) {
    int top = BANNER_H + 6, lh = 22;
    char line[72];

    auto section = [&](const char *title, int y) {
      tft_.setTextColor(UI_ACCENT, UI_BG);
      tft_.drawString(title, 10, y, 2);
      int tw = tft_.textWidth(title, 2);
      tft_.drawFastHLine(18 + tw, y + 8, W - 30 - tw, UI_FAINT);
    };
    auto row = [&](int id, const char *label, const char *v, uint16_t c, int y) {
      if (forceRedraw_) { tft_.setTextColor(UI_MUTED, UI_BG); tft_.drawString(label, 14, y, 2); }
      cell(id, 118, y - 2, W - 126, lh - 2, v, c, 2);
    };

    int yConn = top, yDev = top + 18 + 3 * lh + 6, yCal = yDev + 18 + 3 * lh + 6;
    if (forceRedraw_) {
      section("CONNECTION", yConn);
      section("DEVICE", yDev);
      section("CALIBRATION", yCal);
    }

    // ── CONNECTION ──
    int y = yConn + 18;
    snprintf(line, sizeof(line), s.wifiUp ? "%s   %d dBm" : "DISCONNECTED",
             s.wifiUp ? WiFi.SSID().c_str() : "", s.wifiRssi);
    row(0, "WiFi", line, s.wifiUp ? (s.wifiRssi > -70 ? UI_GOOD : UI_WARN) : UI_CRIT, y); y += lh;
    if (s.backendUp && s.lastAckAt > 0) {
      struct tm tmv; localtime_r(&s.lastAckAt, &tmv);
      snprintf(line, sizeof(line), "receiving   last ack %02d:%02d:%02d", tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
    } else if (s.backendUp) strlcpy(line, "receiving", sizeof(line));
    else strlcpy(line, "NOT REACHABLE", sizeof(line));
    row(1, "Server", line, s.backendUp ? UI_GOOD : UI_CRIT, y); y += lh;
    row(2, "Clock", s.clockSynced ? "NTP synced (UTC)" : "NOT SYNCED", s.clockSynced ? UI_GOOD : UI_WARN, y);

    // ── DEVICE ──
    y = yDev + 18;
    row(3, "Identity", DEVICE_SERIAL "   " FIRMWARE_VERSION, UI_INK, y); y += lh;
    snprintf(line, sizeof(line), "%lu ok / %lu failed   %u buffered",
             (unsigned long)s.txOk, (unsigned long)s.txFail, s.offlineBuffered);
    row(4, "Transmit", line, s.offlineBuffered ? UI_WARN : UI_INK, y); y += lh;
    sensorRowY_ = y;   // this row is tappable — cycles the pulse-ox LED drive
    snprintf(line, sizeof(line), "SPO2 %s  TEMP %s  ECG %s  CUFF %s%s",
             chanTxt(s.chSpo2), chanTxt(s.chTemp), chanTxt(s.chEcg), chanTxt(s.chBp),
             s.spo2LedLevel == 2 ? "" : s.spo2LedLevel == 1 ? "  [LED HALF]" : "  [LED OFF]");
    row(5, "Sensors", line, s.spo2LedLevel == 2 ? UI_INK : UI_WARN, y);

    // ── CALIBRATION ── (stored, survives reflash; serial console: 'cal show')
    y = yCal + 18;
    bool tempCal = g_cal.tempCalibrated();
    snprintf(line, sizeof(line), "TOUCH %s   BP %s   TEMP %s",
             calFromNvs_ ? "ok" : "default",
             s.bpCalibrated ? "ok" : "uncal",
             tempCal ? "ok" : "UNCAL - not transmitted");
    row(6, "Stored", line, tempCal ? UI_INK : UI_WARN, y);

    // ── maintenance buttons — repainted only when state changes ──
    simBtnY_ = H - 26 - 40;
    int simState = s.simulation ? 1 : 0;
    if (forceRedraw_ || simState != lastSimBtn_) {
      lastSimBtn_ = simState;
      if (s.simulation) {
        tft_.fillRoundRect(10, simBtnY_, 220, 32, 8, TFT_ORANGE);
        tft_.setTextColor(TFT_BLACK, TFT_ORANGE);
      } else {
        tft_.fillRoundRect(10, simBtnY_, 220, 32, 8, UI_BG);
        tft_.drawRoundRect(10, simBtnY_, 220, 32, 8, UI_FAINT);
        tft_.setTextColor(UI_MUTED, UI_BG);
      }
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString(s.simulation ? "SIMULATION: ON" : "SIMULATION: OFF", 120, simBtnY_ + 16, 2);
      tft_.setTextDatum(TL_DATUM);
    }
    if (forceRedraw_) {
      tft_.fillRoundRect(240, simBtnY_, 220, 32, 8, UI_BG);
      tft_.drawRoundRect(240, simBtnY_, 220, 32, 8, calFromNvs_ ? UI_FAINT : UI_WARN);
      tft_.setTextColor(calFromNvs_ ? UI_MUTED : UI_WARN, UI_BG);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString(calFromNvs_ ? "CALIBRATE TOUCH" : "CALIBRATE TOUCH !", 350, simBtnY_ + 16, 2);
      tft_.setTextDatum(TL_DATUM);
    }
  }
  int lastSimBtn_ = -1;
  int sensorRowY_ = 0;

  // =====================================================================
  //  On-device touch calibration (Device page → CALIBRATE TOUCH)
  //
  //  TFT_eSPI draws an arrow in each corner; the user taps them in turn.
  //  The resulting 5 values are applied immediately, persisted to NVS
  //  (they survive reboots AND reflashes), and printed to serial as a
  //  ready-to-paste TOUCH_CAL line. This is the durable fix for the
  //  "touch only responds in one spot" symptom: swipe zones, buttons and
  //  taps all depend on this mapping being true for THIS panel.
  // =====================================================================
  bool calRequested_ = false;

  void runTouchCalibration() {
    // The calibration legitimately blocks for as long as the user takes
    // to tap the corners — unsubscribe from the watchdog for its duration.
    esp_task_wdt_delete(NULL);
    tft_.fillScreen(UI_BG);
    tft_.setTextColor(UI_INK, UI_BG);
    tft_.setTextDatum(MC_DATUM);
    tft_.drawString("TOUCH CALIBRATION", W / 2, H / 2 - 30, 4);
    tft_.setTextColor(UI_MUTED, UI_BG);
    tft_.drawString("Tap the corner arrows as they appear", W / 2, H / 2 + 6, 2);
    tft_.drawString("(use a fingernail or stylus for precision)", W / 2, H / 2 + 26, 2);
    tft_.setTextDatum(TL_DATUM);

    uint16_t prevCal[5];
    memcpy(prevCal, cal_, sizeof(cal_));
    tft_.calibrateTouch(cal_, UI_ACCENT, UI_BG, 18);

    // ---- plausibility gate (v3.6.x) ----
    // A working XPT2046 spans hundreds-to-thousands of ADC counts corner
    // to corner. When the touch data line is broken the controller
    // answers ~0 for every tap, calibrateTouch happily "succeeds", and a
    // garbage cal (observed live: X span of 13 counts) gets persisted —
    // masking a WIRING fault as a calibration fault forever after.
    // Refuse to store a span under 500 counts on either axis and say
    // what it really means.
    uint16_t spanX = cal_[0] > cal_[1] ? cal_[0] - cal_[1] : cal_[1] - cal_[0];
    uint16_t spanY = cal_[2] > cal_[3] ? cal_[2] - cal_[3] : cal_[3] - cal_[2];
    if (spanX < 500 || spanY < 500) {
      memcpy(cal_, prevCal, sizeof(cal_));   // keep whatever we had
      tft_.setTouch(cal_);
      Serial.printf("[touch] CALIBRATION REJECTED: raw span x=%u y=%u (need >=500) - "
                    "touch controller returning implausible values; check touch wiring "
                    "(T_DO/GPIO13, T_CS/GPIO5), not calibration\n", spanX, spanY);
      tft_.fillScreen(UI_BG);
      tft_.setTextColor(UI_CRIT, UI_BG);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString("CALIBRATION REJECTED", W / 2, H / 2 - 20, 4);
      tft_.setTextColor(UI_MUTED, UI_BG);
      tft_.drawString("Touch readings implausible - check", W / 2, H / 2 + 10, 2);
      tft_.drawString("touch wiring (T_DO / T_CS), then retry", W / 2, H / 2 + 30, 2);
      tft_.setTextDatum(TL_DATUM);
      tone(PIN_BUZZER, 400, 200);
      delay(2500);
      pageDirty_ = true;
      esp_task_wdt_add(NULL);
      return;
    }

    tft_.setTouch(cal_);
    prefs_.putBytes("cal", cal_, sizeof(cal_));
    calFromNvs_ = true;
    Serial.printf("[touch] calibrated + stored: TOUCH_CAL { %u, %u, %u, %u, %u }\n",
                  cal_[0], cal_[1], cal_[2], cal_[3], cal_[4]);

    // ---- centre fine-trim (v3.1.2) ----
    // The four corner arrows nail the scale, but a constant finger-pad /
    // parallax bias survives them (observed live: presses registered
    // ~35 px below the finger, so the SIMULATION button fired PREV).
    // One tap on a centre target measures that residual offset and
    // stores it as a correction applied to every future touch.
    trim_[0] = trim_[1] = 0;
    // wait for the last calibration touch to clear
    uint32_t t0 = millis();
    while (tft_.getTouchRawZ() > 100 && millis() - t0 < 3000) delay(20);
    delay(300);

    tft_.fillScreen(UI_BG);
    tft_.setTextColor(UI_INK, UI_BG);
    tft_.setTextDatum(MC_DATUM);
    tft_.drawString("One more: tap the centre of the ring", W / 2, H / 2 - 60, 2);
    tft_.setTextDatum(TL_DATUM);
    tft_.drawCircle(W / 2, H / 2, 14, UI_ACCENT);
    tft_.drawCircle(W / 2, H / 2, 13, UI_ACCENT);
    tft_.drawFastHLine(W / 2 - 20, H / 2, 40, UI_ACCENT);
    tft_.drawFastVLine(W / 2, H / 2 - 20, 40, UI_ACCENT);

    t0 = millis();
    bool got = false; uint16_t tx = 0, ty = 0;
    while (millis() - t0 < 15000) {
      if (readTouch(TOUCH_Z_PRESS, tx, ty)) { got = true; break; }
      delay(20);
    }
    if (got) {
      int dx = W / 2 - (int)tx, dy = H / 2 - (int)ty;
      if (abs(dx) <= 60 && abs(dy) <= 60) { trim_[0] = (int16_t)dx; trim_[1] = (int16_t)dy; }
      // a wild tap (>60 px off) is treated as a miss — no trim
    }
    prefs_.putBytes("trim", trim_, sizeof(trim_));
    Serial.printf("[touch] centre check %s: trim %+d,%+d stored\n",
                  got ? "done" : "timed out", trim_[0], trim_[1]);

    tft_.fillScreen(UI_BG);
    tft_.setTextColor(UI_GOOD, UI_BG);
    tft_.setTextDatum(MC_DATUM);
    tft_.drawString("CALIBRATED", W / 2, H / 2 - 8, 4);
    tft_.setTextColor(UI_MUTED, UI_BG);
    tft_.drawString("saved to device memory", W / 2, H / 2 + 22, 2);
    tft_.setTextDatum(TL_DATUM);
    tone(PIN_BUZZER, 1400, 80);
    delay(900);
    pageDirty_ = true;      // repaint the Device page fresh
    esp_task_wdt_add(NULL); // resume watchdog cover
  }

  // =====================================================================
  //  Touch: swipe navigation + page-local buttons + alarm silence
  // =====================================================================
  bool touching_ = false;
  uint16_t downX_ = 0, downY_ = 0, lastX_ = 0, lastY_ = 0;
  uint32_t downMs_ = 0, lastTouchPollMs_ = 0;

  void handleTouch(const MonitorState &s) {
    // Idle: poll at 20 Hz behind the bounded raw-Z pre-gate (TFT_eSPI's
    // getTouch runs an UNBOUNDED pressure-debounce loop inside; hammering
    // it with no finger present starved IDLE0 into a watchdog reboot loop
    // — diagnosed live on this hardware).
    //
    // While a finger IS down: track EVERY frame (~30 Hz) with a lighter
    // pressure gate. The 3.0.x builds kept the 20 Hz cadence during the
    // gesture too, so a fast swipe landed only one or two samples — the
    // measured travel was ~0 px and the page never changed. That is the
    // "sliding only works in one spot / never backwards" complaint: the
    // only reliable navigation left was the bottom-right tap zone.
    uint32_t now = millis();
    if (!touching_ && now - lastTouchPollMs_ < 50) return;
    lastTouchPollMs_ = now;

    uint16_t x = 0, y = 0;
    bool pressed = readTouch(touching_ ? TOUCH_Z_TRACK : TOUCH_Z_PRESS, x, y);

    if (pressed && !touching_) {                 // touch start
      touching_ = true; downX_ = x; downY_ = y; lastX_ = x; lastY_ = y; downMs_ = now;
      // One line per press — lets a captured serial log show exactly
      // where the firmware thinks fingers are landing.
      Serial.printf("[touch] press x=%u y=%u\n", x, y);
      // Buttons fire ON PRESS — instant feedback, and a quick tap can
      // never fall between two polls.
      pressConsumed_ = onPress(x, y, s);
    } else if (pressed) {                        // drag
      lastX_ = x; lastY_ = y;
      // Swipe fires MID-DRAG, the moment the finger has travelled far
      // enough — no waiting for release, either direction, from anywhere.
      int dx = (int)x - (int)downX_;
      if (!pressConsumed_ && abs(dx) >= SWIPE_MIN_PX) {
        flipPage(dx < 0 ? +1 : -1);
        pressConsumed_ = true;                   // one flip per gesture
      }
    } else if (!pressed && touching_) {          // touch release
      touching_ = false;
      // Fallback for a swipe so fast it completed between two polls.
      int dx = (int)lastX_ - (int)downX_;
      if (!pressConsumed_ && abs(dx) >= SWIPE_MIN_PX) {
        flipPage(dx < 0 ? +1 : -1);
      } else if (!pressConsumed_) {
        onTap(downX_, downY_, s);
      }
      pressConsumed_ = false;
    }
  }
  bool pressConsumed_ = false;

  uint32_t lastFlipMs_ = 0;
  void flipPage(int step) {
    // One page per gesture-beat: even if a phantom press slips through
    // every filter, it can nudge one page at most — never machine-gun
    // the user from page 4 back to page 1.
    uint32_t now = millis();
    if (now - lastFlipMs_ < 400) return;
    lastFlipMs_ = now;
    int p = ((int)page_ + step + (int)Page::COUNT) % (int)Page::COUNT;
    page_ = (Page)p;
    pageDirty_ = true;
    tone(PIN_BUZZER, 1400, 25);    // quiet click: gesture registered
  }

  // Press-fired controls (buttons). Returns true when the press hit one,
  // so the drag/release passes don't double-handle it as a swipe/tap.
  bool onPress(uint16_t x, uint16_t y, const MonitorState &s) {
    bool bpBusy = s.bpPhase == BpPhase::INFLATING || s.bpPhase == BpPhase::MEASURING
               || s.bpPhase == BpPhase::COMPUTING || s.bpPhase == BpPhase::ZEROING;
    if (page_ == Page::BP) {
      if (x >= btnX_ && x <= btnX_ + btnW_ && y >= btnY_ && y <= btnY_ + btnH_) {
        if (stateLock()) {
          if (bpBusy) g_state.bpCancelRequested = true;   // button reads CANCEL
          else        g_state.bpRequested = true;         // button reads START BP
          stateUnlock();
        }
        tone(PIN_BUZZER, bpBusy ? 600 : 900, 60);
        return true;
      }
      // CAL PUMP — guided pump-scale calibration (idle only)
      if (!bpBusy && x >= 8 && x <= 124 && y >= btnY_ && y <= btnY_ + btnH_) {
        if (stateLock()) { g_state.bpCalRequested = true; stateUnlock(); }
        tone(PIN_BUZZER, 1000, 60);
        return true;
      }
    }
    // Sensors row → cycle the pulse-ox LED drive (full → half → off → full).
    // The ECG-interference test: watch the ECG trace and the [ecg] serial
    // line while stepping the LED current down with a finger still on the
    // sensor. Nothing here changes the clinical signal path.
    if (page_ == Page::DEVICE && sensorRowY_ > 0 && y >= sensorRowY_ - 2 && y <= sensorRowY_ + 20) {
      if (stateLock()) {
        g_state.spo2LedLevel = g_state.spo2LedLevel == 2 ? 1 : g_state.spo2LedLevel == 1 ? 0 : 2;
        stateUnlock();
      }
      tone(PIN_BUZZER, 1400, 60);
      return true;
    }
    if (page_ == Page::DEVICE && simBtnY_ > 0 && y >= simBtnY_ && y <= simBtnY_ + 32) {
      if (x >= 10 && x <= 230) {                 // simulation toggle
        if (stateLock()) {
          g_state.simulation = !g_state.simulation;
          if (!g_state.simulation) {
            g_state.hr = g_state.spo2 = g_state.temp = g_state.rr = 0;
            g_state.chSpo2 = g_state.chTemp = g_state.chEcg = Chan::ABSENT;
          }
          stateUnlock();
        }
        pageDirty_ = true;
        tone(PIN_BUZZER, 1200, 80);
        return true;
      }
      if (x >= 240 && x <= 460 && !bpBusy) {     // touch calibration
        calRequested_ = true;                    // runs after this touch pass
        tone(PIN_BUZZER, 1000, 60);
        return true;
      }
    }
    return false;
  }

  // Release-fired targets (the ones where accidental brushes must not
  // trigger): alarm silence + bottom-strip page navigation. Buttons live
  // in onPress() for instant response.
  void onTap(uint16_t x, uint16_t y, const MonitorState &s) {
    // banner tap → silence alarms
    if (y < BANNER_H + 6 && s.alarms.any()) {
      if (stateLock()) { g_state.alarmSilencedUntil = millis() + ALARM_SILENCE_MS; stateUnlock(); }
      bannerSig_[0] = '\0';   // force banner repaint with SILENCED label
      return;
    }
    // bottom navigation bar → generous hit zones: the full left/right
    // thirds of the bottom 34 px (visuals are 26 px; the extra headroom
    // absorbs the "I pressed slightly above the button" reality of
    // resistive edges). Middle third (the dots) is deliberately inert.
    if (y >= (uint16_t)(H - 34)) {
      if (x < (uint16_t)(W / 3))     { flipPage(-1); return; }
      if (x > (uint16_t)(2 * W / 3)) { flipPage(+1); return; }
      return;
    }
  }
};
