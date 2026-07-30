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
#define UI_GOOD    0x0400   // dark green — in-range values (also ECG trace; LSB=0)
#define UI_WARN    0xCBC0   // dark amber — warning band (readable on white)
#define UI_CRIT    0xF800   // red — critical band / alarms
#define UI_PLETH   0x0333   // deep teal — pleth trace + accent (LSB=1)
#define UI_ACCENT  0x0333
#define UI_VIOLET  0x780F   // trends: RESP
#define UI_BANNER  0xEF5D   // idle banner strip — very light grey
#define UI_CARD    0xF7BE   // very light grey — vitals tile fill

// ---------- scope panes (waveforms only) ----------
// Waveforms render as a bright phosphor trace on a near-black scope —
// the clinical reference look, and the one the SmartTriage Monitoring
// page uses. Only the two wave panes use this; the rest stays light.
#define UI_SCOPE       0x0000   // pane background (black)
#define UI_SCOPE_GRID  0x2124   // faint grid
#define UI_TRACE_ECG   0x07E0   // phosphor green (LSB=0 — trace slot key)
#define UI_TRACE_PLETH 0x07FF   // cyan           (LSB=1 — trace slot key)
// NOTE: the trace colors deliberately differ in bit 0 — drawTrace keys
// its per-trace state (cursor, last-y, gain) on (color & 1).

enum class Page : uint8_t { DASH = 0, WAVE, TREND, BP, DEVICE, COUNT };

class UiController {
public:
  // Cold-boot panel bring-up, VERIFIED instead of assumed.
  //
  // Field evidence: the display renders correctly after an UPLOAD (esptool
  // resets only the MCU — the panel keeps its supply and stays initialized
  // from before) but stays WHITE after an unplug/replug (the panel is power
  // cycled too). So the init sequence is not reaching a panel whose own rail
  // is still settling; the MCU simply boots faster than the display.
  //
  // Therefore: pulse the panel's HARDWARE reset generously (ILI9488 wants
  // >120 ms before it will accept commands), run init, then PROVE it worked
  // by writing a known pixel and reading it back over MISO. Retry, and log
  // every attempt — so the serial record answers the hardware-vs-timing
  // question by itself: a readback that changes means the panel is alive and
  // we merely needed to wait; a readback that never changes across five
  // hardware resets means commands are not reaching it at all.
  bool bringUpDisplay(const char *why) {
    for (int attempt = 1; attempt <= 5; attempt++) {
#if defined(TFT_RST) && (TFT_RST >= 0)
      pinMode(TFT_RST, OUTPUT);
      digitalWrite(TFT_RST, HIGH); delay(10);
      digitalWrite(TFT_RST, LOW);  delay(30);
      digitalWrite(TFT_RST, HIGH); delay(150);
#endif
      tft_.init();
      tft_.setRotation(1);

      // Functional proof: colour a corner, read it back. Compared with a
      // tolerance because ILI9488 stores 18-bit colour, so a 16-bit value
      // does not always round-trip exactly.
      tft_.fillRect(0, 0, 8, 8, TFT_RED);
      uint16_t got = tft_.readPixel(2, 2);
      bool looksRed = (got >> 11) >= 28 && (((got >> 5) & 0x3F) <= 6) && ((got & 0x1F) <= 6);
      Serial.printf("[ui] panel bring-up (%s) attempt %d: readback 0x%04X%s\n",
                    why, attempt, got, looksRed ? "  OK" : "");
      if (looksRed) { panelVerified_ = true; return true; }
      delay(120);
    }
    Serial.println("[ui] panel NOT VERIFIED after 5 hardware resets. If the readback above "
                   "never changed, the panel is not answering: check TFT_RST (GPIO 9), "
                   "TFT_MISO (GPIO 13) and the display's 3V3 rail. (Note: some panels do "
                   "not support pixel read-back at all, in which case the display may still "
                   "be fine — judge by the screen.)");
    return false;
  }
  bool panelVerified_ = false;

  void begin() {
    bringUpDisplay("boot");
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

    // ---- cold-boot display re-init (v3.9.1) ----
    // Field: the same build renders fine after an upload (soft reset — the
    // panel keeps power and stays initialized) but comes up WHITE after a
    // cold power-on, with frames/touch/sensors all healthy. White with a
    // live backlight = controller never accepted its init sequence: at a
    // cold start the panel/rail (this box also wakes a pump driver at
    // power-on) is not ready when tft.init() runs ~1.5 s in. Re-send the
    // full init once at ~4 s, when the rails are long stable, and repaint.
    // On a boot where the first init already took, this costs one brief
    // flicker; on a cold boot it brings the panel up. Runs under the SPI
    // mutex like every other display access.
    if (!bootReinit_ && millis() > 4000) {
      bootReinit_ = true;
      // Skip the retry storm if the panel already proved itself at boot —
      // re-initialising a working display costs a visible flicker.
      if (!panelVerified_) {
        bringUpDisplay("4s cold-boot guard");
        tft_.setTouch(cal_);
        tft_.fillScreen(UI_BG);
        pageDirty_ = true;
      }
    }

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
      uint32_t t0 = millis();
      tft_.fillScreen(UI_BG);
      drawChrome();
      // The full-screen clear wipes PIXELS; these wipe the CACHES that decide
      // whether a pixel gets redrawn. forceRedraw_ covers one frame, but the
      // per-surface latches and the text cache outlive it, and any slot whose
      // text happens to match its previous value on a different page would be
      // silently skipped — leaving a hole in the freshly-cleared screen.
      for (int i = 0; i < (int)(sizeof(cells_) / sizeof(cells_[0])); i++) cells_[i].last[0] = '\0';
      bannerSig_[0] = '\0';
      lastTrendSig_ = 0xFFFFFFFF;
      lastBpBtn_ = -1; lastBpUiState_ = -1; lastSimBtn_ = -1;
      lastPlethState_ = -1;
      tileBand_[0] = tileBand_[1] = tileBand_[2] = tileBand_[3] = 1;
      pageFlipMs_ = t0;
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
    if (pageFlipMs_) {
      uint32_t took = millis() - pageFlipMs_;
      pageFlipMs_ = 0;
      Serial.printf("[ui] page -> %d repainted in %lu ms\n", (int)page_, (unsigned long)took);
    }
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
  bool bootReinit_ = false;   // one-time cold-boot re-init latch
  uint32_t pageFlipMs_ = 0;   // set on a page repaint, timed out at frame end

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
  // Cell-cache slots are GLOBAL, so two pages must never share an index:
  // the cache compares TEXT only, so a shared slot means page B can suppress
  // a redraw that page A needs (and vice versa) for any frame where
  // forceRedraw_ is false. The Device page previously reused 0-9 — the same
  // slots as the dashboard tiles/BP strip and the waveform numerics.
  //   0-13  dashboard / waveform / BP-page values
  //   14-21 BP-history rows (trends)
  //   22-24 status tags (temp, pleth message, resp)
  //   25-34 Device-page rows
  Cell cells_[36];

  // Only fonts 2 and 4 are used for data (the fonts the previously working
  // build proved are enabled in this project's User_Setup.h); `size` scales
  // font 4 up for the big numerics. Fonts 6/7 are avoided deliberately —
  // they lack the '/' glyph a BP reading needs, and may not be loaded.
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
  uint16_t tileBand_[4] = {1, 1, 1, 1};   // accent-bar repaint cache
  void drawDashboard(const MonitorState &s) {
    int top = BANNER_H + 4;
    int tileW = (W - 18) / 2, tileH = (H - top - 104) / 2;
    struct Tile { const char *label; const char *unit; };

    if (forceRedraw_) {
      static const Tile tiles[4] = {{"HR", "bpm"}, {"SpO2", "%"}, {"TEMP", "C"}, {"RESP", "/min"}};
      for (int i = 0; i < 4; i++) {
        int x = 6 + (i % 2) * (tileW + 6), y = top + (i / 2) * (tileH + 6);
        // Card fill + hairline frame (visual only — same rect as before).
        tft_.fillRoundRect(x, y, tileW, tileH, 8, UI_CARD);
        tft_.drawRoundRect(x, y, tileW, tileH, 8, UI_FAINT);
        tft_.setTextColor(UI_MUTED, UI_CARD);
        tft_.drawString(tiles[i].label, x + 10, y + 6, 2);
        tft_.setTextDatum(TR_DATUM);
        tft_.drawString(tiles[i].unit, x + tileW - 10, y + 6, 2);
        tft_.setTextDatum(TL_DATUM);
        tileBand_[i] = 1;   // force the accent bar to repaint
      }
      int by = top + 2 * (tileH + 6);
      tft_.fillRoundRect(6, by, W - 12, H - by - 42, 8, UI_CARD);
      tft_.drawRoundRect(6, by, W - 12, H - by - 42, 8, UI_FAINT);
      tft_.setTextColor(UI_MUTED, UI_CARD);
      tft_.drawString("BP (last reading)", 16, by + 6, 2);
    }

    // Colored left accent bar per tile: the at-a-glance band indicator
    // (green in-range / amber warning / red critical). Purely additive —
    // 4 px inside the existing tile, repainted only on band change.
    auto accent = [&](int i, uint16_t c) {
      if (tileBand_[i] == c) return;
      tileBand_[i] = c;
      int x = 6 + (i % 2) * (tileW + 6), y = top + (i / 2) * (tileH + 6);
      tft_.fillRect(x + 3, y + 8, 4, tileH - 16, c);
    };

    char t[20];
    // HR (+ source/quality tag). A weak or stale ECG signal DIMS the
    // number and says so — a value flickering between "96" and "--" at
    // a bedside helps nobody, and a confidently-wrong number is worse.
    bool hrWeak = s.hrFromEcg && s.ecgQuality <= 1;
    uint16_t hrC = hrWeak ? UI_MUTED
                          : bandColor(s.hr, ALM_HR_WARN_LOW, ALM_HR_WARN_HIGH, ALM_HR_CRIT_LOW, ALM_HR_CRIT_HIGH);
    snprintf(t, sizeof(t), s.hr > 0 ? "%.0f" : "--", s.hr);
    cell(0, 6 + 8, top + 22, tileW - 16, tileH - 42, t, hrC, 4, 2, UI_CARD);
    accent(0, hrC);
    // A bare "--" with no reason is a support call. When HR is blanked,
    // SAY WHY in the tag line (this branch previously rendered "" for
    // exactly the case that most needs an explanation).
    const char *hrTag = s.hr > 0 ? (!s.hrFromEcg ? "PULSE-OX"
                                   : hrWeak ? "ECG - WEAK SIGNAL, CHECK ELECTRODES" : "ECG")
                      : s.chEcg == Chan::NO_CONTACT ? "LEADS OFF"
                      : "SIGNAL POOR - CHECK ELECTRODES";
    cell(10, 6 + 8, top + tileH - 20, tileW - 16, 16, hrTag,
         (s.hr <= 0 || hrWeak) ? UI_WARN : UI_MUTED, 1, 1, UI_CARD);

    uint16_t spC = bandColor(s.spo2, ALM_SPO2_WARN, 101, ALM_SPO2_CRIT, 101);
    snprintf(t, sizeof(t), s.spo2 > 0 ? "%.0f" : "--", s.spo2);
    cell(1, 12 + tileW + 8, top + 22, tileW - 16, tileH - 42, t, spC, 4, 2, UI_CARD);
    accent(1, spC);

    // Temperature: an UNCALIBRATED contact sensor reads SKIN temp, degrees
    // below core — show it dimmed with an explicit tag rather than as a
    // confident clinical value (net.h also excludes it from transmission).
    bool tempCal = g_cal.tempCalibrated();
    uint16_t tpC = !tempCal ? UI_MUTED
                            : bandColor(s.temp, ALM_TEMP_WARN_LOW, ALM_TEMP_WARN_HIGH, ALM_TEMP_CRIT_LOW, ALM_TEMP_CRIT_HIGH);
    snprintf(t, sizeof(t), s.temp > 0 ? "%.1f" : "--.-", s.temp);
    cell(2, 6 + 8, top + tileH + 6 + 22, tileW - 16, tileH - 42, t, tpC, 4, 2, UI_CARD);
    accent(2, tempCal ? tpC : UI_WARN);
    cell(22, 6 + 8, top + 2 * tileH + 6 - 20, tileW - 16, 16,
         s.temp <= 0 ? ""
         : !tempCal ? "SKIN TEMP - RUN cal temp"
         : !s.tempSettled ? "STABILISING - HOLD IN PLACE"
         : "",
         UI_WARN, 1, 1, UI_CARD);

    // RESP: not reported by this hardware (see sensors.h publish()). The
    // tile stays so the layout is unchanged, and says so plainly rather
    // than showing a number nobody should act on.
    cell(3, 12 + tileW + 8, top + tileH + 6 + 22, tileW - 16, tileH - 42, "--", UI_MUTED, 4, 2, UI_CARD);
    accent(3, UI_FAINT);
    cell(24, 12 + tileW + 8, top + 2 * tileH + 6 - 20, tileW - 16, 16,
         "not measured on this device", UI_MUTED, 1, 1, UI_CARD);

    // BP strip
    int by = top + 2 * (tileH + 6);
    if (s.bpLast.valid && s.bpCalibrated) {
      char when[28] = "time unsynced";
      if (s.bpLast.at > 0) {
        struct tm tmv; localtime_r(&s.bpLast.at, &tmv);
        snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min);
      }
      snprintf(t, sizeof(t), "%d/%d", s.bpLast.sys, s.bpLast.dia);
      uint16_t c = (s.bpLast.sys > ALM_SYS_CRIT_HIGH || s.bpLast.sys < ALM_SYS_CRIT_LOW) ? UI_CRIT : UI_GOOD;
      cell(4, 16, by + 20, 150, 28, t, c, 4, 1, UI_CARD);
      char meta[56];
      snprintf(meta, sizeof(meta), "MAP %d   at %s", s.bpLast.map, when);
      cell(5, 176, by + 24, W - 196, 18, meta, UI_MUTED, 2, 1, UI_CARD);
    } else if (s.bpLast.valid) {
      // A reading EXISTS but the pressure front-end is not validated. The
      // dashboard is the at-a-glance clinical view — the number a nurse
      // transcribes — so it shows nothing here. The BP page still displays
      // the raw result for engineering/validation work, clearly marked.
      cell(4, 16, by + 20, 150, 28, "--/--", UI_MUTED, 4, 1, UI_CARD);
      cell(5, 176, by + 24, W - 196, 18, "uncalibrated - not clinical, see BP page", UI_WARN, 2, 1, UI_CARD);
    } else {
      cell(4, 16, by + 20, 150, 28, "--/--", UI_MUTED, 4, 1, UI_CARD);
      cell(5, 176, by + 24, W - 196, 18, "no reading - use BP page", UI_MUTED, 2, 1, UI_CARD);
    }

    // per-channel status chips
    char chips[64];
    snprintf(chips, sizeof(chips), "SPO2:%s  TEMP:%s  ECG:%s  CUFF:%s",
             chanTxt(s.chSpo2), chanTxt(s.chTemp), chanTxt(s.chEcg), chanTxt(s.chBp));
    cell(6, 6, H - 40, W - 12, 12, chips, UI_MUTED, 1);
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
      tft_.fillRect(0, top, plotW, H - top - 28, UI_BG);
      paintScope(ecgY, ecgH, plotW, "ECG  (Lead II)", UI_TRACE_ECG);
      paintScope(plethY, plethH, plotW, "PLETH (SpO2)", UI_TRACE_PLETH);
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
      drawTrace(g_ecgWave, g_ecgWaveHead, ecgReadHead_, ecgY + 2, ecgH - 4, plotW, UI_TRACE_ECG);
    }

    // Pleth pane: NEVER show a stale/frozen curve for a sensor that is
    // not delivering — freeze-frames read as live data to a clinician.
    // State the truth in the pane and clear it once on transition.
    int plethState = s.simulation ? 0
                   : s.chSpo2 == Chan::OK ? 0
                   : s.chSpo2 == Chan::NO_CONTACT ? 1 : 2;
    if (plethState != lastPlethState_) {
      lastPlethState_ = plethState;
      paintScope(plethY, plethH, plotW, "PLETH (SpO2)", UI_TRACE_PLETH);
      plethReadHead_ = g_plethWaveHead;         // don't replay stale ring content
      waveX_[UI_TRACE_PLETH & 1] = 1;           // restart its sweep on the cleared pane
      lastPy_[UI_TRACE_PLETH & 1] = -1;
    }
    if (plethState == 0) {
      drawTrace(g_plethWave, g_plethWaveHead, plethReadHead_, plethY + 2, plethH - 4, plotW, UI_TRACE_PLETH);
    } else {
      cell(23, plotW / 2 - 110, plethY + plethH / 2 - 9, 220, 18,
           plethState == 1 ? "PLACE FINGER ON SENSOR" : "SPO2 SENSOR NOT DETECTED",
           plethState == 1 ? UI_WARN : UI_MUTED, 2, 1, UI_SCOPE);
    }

    // numerics column
    char t[16];
    bool hrWeak = s.hrFromEcg && s.ecgQuality <= 1;
    snprintf(t, sizeof(t), s.hr > 0 ? "%.0f" : "--", s.hr);
    cell(8, W - numW, ecgY + 14, numW - 4, 40, t,
         hrWeak ? UI_MUTED
                : bandColor(s.hr, ALM_HR_WARN_LOW, ALM_HR_WARN_HIGH, ALM_HR_CRIT_LOW, ALM_HR_CRIT_HIGH), 4);
    if (forceRedraw_) { tft_.setTextColor(UI_MUTED, UI_BG); tft_.drawString("HR bpm", W - numW + 8, ecgY + 2, 1); }

    snprintf(t, sizeof(t), s.spo2 > 0 ? "%.0f%%" : "--", s.spo2);
    cell(9, W - numW, plethY + 14, numW - 4, 34, t,
         bandColor(s.spo2, ALM_SPO2_WARN, 101, ALM_SPO2_CRIT, 101), 4);
    if (forceRedraw_) { tft_.setTextColor(UI_MUTED, UI_BG); tft_.drawString("SpO2", W - numW + 8, plethY + 2, 1); }
  }

  // consume new ring samples; draw one column per sample with an erase-ahead cursor.
  //
  // AUTO-GAIN (v3.4.1): the pane is normalised to the signal's own recent
  // peak amplitude — professional-monitor behaviour. The previous fixed
  // ±2047 mapping made a normal QRS a small squiggle and every motion
  // artifact a full-height spike ("line spikes very high, then shrinks
  // down small" — field report). Gain rises instantly to contain a
  // bigger signal and relaxes slowly (~12%/s), so beat height stays
  // steady and an artifact compresses the trace only briefly.
  // Scope pane: black fill, dotted grid (4 vertical divisions + 3
  // horizontal), label. Same rect as the old framed pane.
  void paintScope(int y, int h, int plotW, const char *label, uint16_t labelColor) {
    tft_.fillRect(0, y, plotW, h, UI_SCOPE);
    for (int q = 1; q <= 3; q++) {
      int gy = y + h * q / 4;
      for (int gx = 2; gx < plotW - 2; gx += 12) tft_.drawPixel(gx, gy, UI_SCOPE_GRID);
    }
    for (int gx = SCOPE_VDIV; gx < plotW - 2; gx += SCOPE_VDIV) {
      for (int gy = y + 2; gy < y + h - 2; gy += 8) tft_.drawPixel(gx, gy, UI_SCOPE_GRID);
    }
    tft_.setTextColor(labelColor, UI_SCOPE);
    tft_.drawString(label, 6, y + 3, 1);
  }
  static const int SCOPE_VDIV = 48;   // vertical grid spacing (px)

  // DISPLAY GAIN — median of recent window maxima. The previous
  // attack/decay pair had a RATCHET: attack (0.5/sample) was ~1000x
  // faster than decay (0.9995/sample), so the gain tracked the noise
  // PEAK HISTORY, not the signal. With electrode pops every few seconds
  // every real beat rendered 1.7-2.2x too small — the "doesn't look like
  // an ECG" complaint. Now each trace takes the max |sample| over a
  // 512-sample window (~2 s @250 Hz, guaranteed to contain a QRS) and
  // the gain is the MEDIAN of the last 5 window maxima: one artifact
  // spoils one window, the median ignores it. Artifacts CLIP at the pane
  // edge (as on a real monitor) instead of shrinking every beat.
  struct TraceGain {
    float winMax = 0; int winN = 0;
    float hist[5] = {400, 400, 400, 400, 400}; int histN = 0;
    float gain = 400;
    void push(float a) {
      if (a > winMax) winMax = a;
      if (++winN < 512) return;
      hist[histN % 5] = max(winMax, 250.0f);           // noise floor
      histN++; winMax = 0; winN = 0;
      float t[5]; memcpy(t, hist, sizeof(t));
      for (int i = 1; i < 5; i++) { float k = t[i]; int j = i - 1;
        while (j >= 0 && t[j] > k) { t[j + 1] = t[j]; j--; } t[j + 1] = k; }
      gain = t[2];
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
      int py = y + h / 2 - (int)((float)v / (tg.gain * 1.25f) * (h / 2 - 2));
      py = constrain(py, y, y + h - 1);

      // erase-ahead cursor (classic monitor sweep), private to this trace
      int &x = waveX_[color & 1];
      int eraseX = (x + 6) % plotW;
      if (eraseX > 1 && eraseX < plotW - 1) {
        tft_.drawFastVLine(eraseX, y, h, UI_SCOPE);
        // restore the grid pixels this column carried
        if (eraseX % SCOPE_VDIV == 0) {
          for (int gy = y + 2; gy < y + h - 2; gy += 8) tft_.drawPixel(eraseX, gy, UI_SCOPE_GRID);
        } else if (eraseX % 12 == 2) {
          for (int q = 1; q <= 3; q++) tft_.drawPixel(eraseX, y + h * q / 4, UI_SCOPE_GRID);
        }
      }

      if (x > 1 && lastPy_[color & 1] >= 0) {
        // 2 px stroke — reads as a solid phosphor trace, not a hairline
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
      tft_.drawRect(4, y, chartW, chartH, UI_FAINT);
      tft_.setTextColor(rows[i].color, UI_BG);
      tft_.drawString(rows[i].label, 8, y + 2, 1);

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
      tft_.drawString(lbl, chartW, y + 2, 1);
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

  void drawBpPage(const MonitorState &s) {
    int top = BANNER_H + 6;
    btnW_ = 200; btnH_ = 54;
    btnX_ = W / 2 - btnW_ / 2; btnY_ = H - btnH_ - 36;

    if (forceRedraw_) {
      tft_.setTextColor(UI_MUTED, UI_BG);
      tft_.drawString("OSCILLOMETRIC BLOOD PRESSURE", 10, top, 2);
      if (!s.bpCalibrated) {
        tft_.setTextColor(UI_WARN, UI_BG);
        tft_.setTextDatum(TR_DATUM);
        tft_.drawString("UNCALIBRATED", W - 8, top, 2);
        tft_.setTextDatum(TL_DATUM);
      }
    }

    bool busy = s.bpPhase == BpPhase::INFLATING || s.bpPhase == BpPhase::MEASURING
             || s.bpPhase == BpPhase::COMPUTING || s.bpPhase == BpPhase::ZEROING;

    // One hard clear per state change. Every state below writes the same
    // three line slots at the same y, so nothing can survive underneath.
    int uiState = busy ? 1 : s.bpPhase == BpPhase::ERROR ? 2 : s.bpLast.valid ? 3 : 0;
    if (forceRedraw_ || uiState != lastBpUiState_) {
      lastBpUiState_ = uiState;
      tft_.fillRect(0, top + 24, W, btnY_ - top - 32, UI_BG);
      cells_[11].last[0] = cells_[12].last[0] = cells_[13].last[0] = '\0';
    }

    // result / live area
    char t[32];
    if (busy) {
      // During a REAL measurement the BP task owns the whole shared bus
      // (the pressure ADC shares the display clock and has no CS — see
      // bp.h), so this screen is drawn ONCE and then freezes until the
      // cuff releases. Draw honest static guidance, not live numbers
      // that would sit frozen mid-value. (The sim cycle does update the
      // number — it never touches the bus.)
      snprintf(t, sizeof(t), s.simulation ? "%.0f" : "MEASURING", s.cuffPressure);
      cell(11, W / 2 - 150, top + 30, 300, 52, t, UI_WARN, 4, s.simulation ? 2 : 1);
      cell(12, W / 2 - 200, top + 86, 400, 18,
           s.simulation ? "cuff mmHg (simulated)" : "display pauses while measuring",
           UI_MUTED, 2);
      cell(13, W / 2 - 200, top + 108, 400, 20,
           s.simulation ? "Measuring - hold still" : "hold still - press & HOLD screen to stop",
           UI_WARN, 2);
    } else if (s.bpPhase == BpPhase::ERROR) {
      cell(11, W / 2 - 150, top + 30, 300, 52, "FAILED", UI_CRIT, 4, 1);
      cell(12, W / 2 - 200, top + 86, 400, 18, s.bpError, UI_CRIT, 2);
      cell(13, W / 2 - 200, top + 108, 400, 20, "check the cuff, then press START BP to retry", UI_MUTED, 2);
    } else if (s.bpLast.valid) {
      snprintf(t, sizeof(t), "%d/%d", s.bpLast.sys, s.bpLast.dia);
      cell(11, W / 2 - 150, top + 30, 300, 52, t, UI_GOOD, 4, 2);
      char meta[48];
      char when[10] = "--:--";
      if (s.bpLast.at > 0) { struct tm tmv; localtime_r(&s.bpLast.at, &tmv); snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min); }
      snprintf(meta, sizeof(meta), "MAP %d mmHg   measured %s", s.bpLast.map, when);
      cell(12, W / 2 - 200, top + 86, 400, 18, meta, UI_MUTED, 2);
      cell(13, W / 2 - 200, top + 108, 400, 20,
           s.bpCalibrated ? "" : "UNCALIBRATED - engineering value, NOT for clinical use",
           UI_CRIT, 2);
    } else {
      cell(11, W / 2 - 150, top + 30, 300, 52, "no reading yet", UI_MUTED, 4, 1);
      cell(12, W / 2 - 200, top + 86, 400, 18, "wrap cuff snugly, then press START BP", UI_MUTED, 2);
      cell(13, W / 2 - 200, top + 108, 400, 20, "", UI_MUTED, 2);
    }

    // start/cancel button — repainted only when its state changes (an
    // every-frame repaint flickers and wastes SPI time). While a cycle
    // runs the button becomes CANCEL: a cuff squeezing a patient's arm
    // must always be stoppable from the screen.
    int btnState = busy ? 1 : 0;
    if (forceRedraw_ || btnState != lastBpBtn_) {
      lastBpBtn_ = btnState;
      uint16_t bc = busy ? UI_CRIT : UI_GOOD;
      tft_.fillRoundRect(btnX_, btnY_, btnW_, btnH_, 10, bc);
      tft_.setTextColor(TFT_WHITE, bc);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString(busy ? "CANCEL" : "START BP", btnX_ + btnW_ / 2, btnY_ + btnH_ / 2, 4);
      tft_.setTextDatum(TL_DATUM);
      // guided pump-scale calibration (see bp.h) — hidden while busy
      if (!busy) {
        tft_.fillRoundRect(8, btnY_, 116, btnH_, 10, UI_ACCENT);
        tft_.setTextColor(TFT_WHITE, UI_ACCENT);
        tft_.setTextDatum(MC_DATUM);
        tft_.drawString("CAL", 66, btnY_ + btnH_ / 2 - 10, 4);
        tft_.drawString("PUMP", 66, btnY_ + btnH_ / 2 + 14, 2);
        tft_.setTextDatum(TL_DATUM);
      } else {
        tft_.fillRect(8, btnY_, 116, btnH_, UI_BG);
      }
    }
  }
  int lastBpBtn_ = -1;
  int lastBpUiState_ = -1;

  // =====================================================================
  //  PAGE 5 — device status
  // =====================================================================
  int simBtnY_ = 0;
  void drawDevice(const MonitorState &s) {
    int top = BANNER_H + 6, lh = 22, y = top;
    char line[64];

    auto row = [&](int id, const char *v, uint16_t c) {
      cell(25 + id, 150, y, W - 156, lh - 4, v, c, 2);   // 25.. = Device slots
      y += lh;
    };
    if (forceRedraw_) {
      const char *labels[] = {"WiFi", "Signal", "Server", "Last sync", "Device", "Calibration",
                              "Transmit", "Buffered", "Sensors", "Clock"};
      int ly = top;
      for (int i = 0; i < 10; i++) { tft_.setTextColor(UI_MUTED, UI_BG); tft_.drawString(labels[i], 10, ly, 2); ly += lh; }
    }

    snprintf(line, sizeof(line), "%s%s", s.wifiUp ? "connected  " : "DISCONNECTED", s.wifiUp ? WiFi.SSID().c_str() : "");
    row(0, line, s.wifiUp ? UI_GOOD : UI_CRIT);
    snprintf(line, sizeof(line), s.wifiUp ? "%d dBm" : "-", s.wifiRssi);
    row(1, line, s.wifiRssi > -70 ? UI_GOOD : UI_WARN);
    row(2, s.backendUp ? "SmartTriage receiving" : "NOT REACHABLE", s.backendUp ? UI_GOOD : UI_CRIT);
    if (s.lastAckAt > 0) {
      struct tm tmv; localtime_r(&s.lastAckAt, &tmv);
      snprintf(line, sizeof(line), "%02d:%02d:%02d", tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
    } else strlcpy(line, "never", sizeof(line));
    row(3, line, s.lastAckAt > 0 ? UI_INK : UI_WARN);
    row(4, DEVICE_SERIAL "   " FIRMWARE_VERSION, UI_INK);
    // Stored calibrations (NVS, survive reflash) as visible status rather
    // than hidden state. Temp UNCAL is the one that blocks transmission.
    bool tempCalOk = g_cal.tempCalibrated();
    snprintf(line, sizeof(line), "touch %s  BP %s  temp %s",
             calFromNvs_ ? "ok" : "default", s.bpCalibrated ? "ok" : "uncal",
             tempCalOk ? "ok" : "UNCAL");
    row(5, line, tempCalOk ? UI_INK : UI_WARN);
    snprintf(line, sizeof(line), "%lu ok / %lu failed", (unsigned long)s.txOk, (unsigned long)s.txFail);
    row(6, line, UI_INK);
    snprintf(line, sizeof(line), "%u offline readings", s.offlineBuffered);
    row(7, line, s.offlineBuffered ? UI_WARN : UI_INK);
    sensorRowY_ = y;   // this row is tappable — cycles the pulse-ox LED drive
    snprintf(line, sizeof(line), "SPO2:%s TEMP:%s ECG:%s CUFF:%s%s",
             chanTxt(s.chSpo2), chanTxt(s.chTemp), chanTxt(s.chEcg), chanTxt(s.chBp),
             s.spo2LedLevel == 2 ? "" : s.spo2LedLevel == 1 ? "  [LED HALF]" : "  [LED OFF]");
    row(8, line, s.spo2LedLevel == 2 ? UI_INK : UI_WARN);
    row(9, s.clockSynced ? "NTP synced (UTC)" : "NOT SYNCED", s.clockSynced ? UI_GOOD : UI_WARN);

    // simulation toggle — repainted only when its state changes
    simBtnY_ = y + 4;
    int simState = s.simulation ? 1 : 0;
    if (forceRedraw_ || simState != lastSimBtn_) {
      lastSimBtn_ = simState;
      uint16_t sc = s.simulation ? TFT_ORANGE : UI_FAINT;
      tft_.fillRoundRect(10, simBtnY_, 220, 32, 8, sc);
      tft_.setTextColor(s.simulation ? TFT_BLACK : UI_INK, sc);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString(s.simulation ? "SIMULATION: ON" : "SIMULATION: OFF", 120, simBtnY_ + 16, 2);
      tft_.setTextDatum(TL_DATUM);
    }

    // touch-calibration button — static, drawn on page entry
    if (forceRedraw_) {
      tft_.fillRoundRect(240, simBtnY_, 220, 32, 8, UI_ACCENT);
      tft_.setTextColor(TFT_WHITE, UI_ACCENT);
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
