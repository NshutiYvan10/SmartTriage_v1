/*
 * ui.h — five-page touch UI on TFT_eSPI.
 *
 * Flicker-free by construction (the previous build did a full
 * fillScreen on nearly every frame):
 *   - numbers render into a small reused sprite and are pushed only
 *     when their text actually changed;
 *   - waveforms scroll with the classic monitor "erase-ahead cursor" —
 *     one column cleared and redrawn per new sample, no full-frame work;
 *   - static chrome (labels, frames) is drawn once per page entry.
 *
 * Layout adapts to tft.width()/height() at runtime, so whatever panel
 * your working User_Setup.h defines (480×320 assumed) renders correctly.
 *
 * Pages: 1 Dashboard · 2 Waveforms · 3 Trends · 4 Blood Pressure · 5 Device.
 * Navigation: horizontal swipe anywhere, or tap the page dots. The alarm
 * banner overlays every page; tapping it silences the buzzer for 2 min.
 */
#pragma once
#include <TFT_eSPI.h>
#include <WiFi.h>
#include <time.h>
#include "config.h"
#include "state.h"
#include "alarms.h"

enum class Page : uint8_t { DASH = 0, WAVE, TREND, BP, DEVICE, COUNT };

class UiController {
public:
  void begin() {
    tft_.init();
    tft_.setRotation(1);
    uint16_t cal[5] = TOUCH_CAL;
    tft_.setTouch(cal);
    W = tft_.width(); H = tft_.height();
    BANNER_H = 26;
    val_.setColorDepth(8);
    tft_.fillScreen(TFT_BLACK);
    drawChrome();
  }

  void frame() {
    MonitorState s = snapshotState();
    handleTouch(s);

    if (pageDirty_) {
      tft_.fillScreen(TFT_BLACK);
      drawChrome();
      pageDirty_ = false;
      forceRedraw_ = true;
      waveInit_ = false;
    }

    drawBanner(s);

    switch (page_) {
      case Page::DASH:   drawDashboard(s); break;
      case Page::WAVE:   drawWaveforms(s); break;
      case Page::TREND:  drawTrends(s);    break;
      case Page::BP:     drawBpPage(s);    break;
      case Page::DEVICE: drawDevice(s);    break;
      default: break;
    }
    forceRedraw_ = false;
  }

private:
  TFT_eSPI tft_;
  TFT_eSprite val_{&tft_};
  int W = 480, H = 320, BANNER_H = 26;
  Page page_ = Page::DASH;
  bool pageDirty_ = true, forceRedraw_ = true, waveInit_ = false;

  // ---------- shared chrome ----------
  void drawChrome() {
    // page dots, bottom-centre
    int dots = (int)Page::COUNT;
    int cx = W / 2 - dots * 10 / 2 + 2;
    for (int i = 0; i < dots; i++) {
      tft_.fillCircle(cx + i * 12, H - 8, 3, i == (int)page_ ? TFT_CYAN : TFT_DARKGREY);
    }
    static const char *names[] = {"VITALS", "WAVES", "TRENDS", "BLOOD PRESSURE", "DEVICE"};
    tft_.setTextColor(TFT_DARKGREY, TFT_BLACK);
    tft_.setTextDatum(BC_DATUM);
    tft_.drawString(names[(int)page_], W / 2, H - 14, 1);
    tft_.setTextDatum(TL_DATUM);
  }

  // ---------- alarm / sim banner (top strip, every page) ----------
  void drawBanner(const MonitorState &s) {
    uint32_t now = millis();
    bool critical = s.alarms.any();
    char text[96];

    if (critical) {
      AlarmManager::describe(s.alarms, text, sizeof(text));
      bool flash = (now / 400) % 2 == 0;             // impossible to miss
      uint16_t bg = flash ? TFT_RED : 0x8000;
      tft_.fillRect(0, 0, W, BANNER_H, bg);
      tft_.setTextColor(TFT_WHITE, bg);
      tft_.setTextDatum(ML_DATUM);
      tft_.drawString(text, 6, BANNER_H / 2, 2);
      bool silenced = s.alarmSilencedUntil != 0 && now < s.alarmSilencedUntil;
      tft_.setTextDatum(MR_DATUM);
      tft_.drawString(silenced ? "SILENCED" : "TAP TO SILENCE", W - 6, BANNER_H / 2, 1);
      tft_.setTextDatum(TL_DATUM);
      bannerWas_ = 2;
    } else if (s.simulation) {
      uint16_t bg = TFT_ORANGE;
      tft_.fillRect(0, 0, W, BANNER_H, bg);
      tft_.setTextColor(TFT_BLACK, bg);
      tft_.setTextDatum(MC_DATUM);
      tft_.drawString("SIMULATION MODE — DEMO DATA, NOT TRANSMITTED", W / 2, BANNER_H / 2, 2);
      tft_.setTextDatum(TL_DATUM);
      bannerWas_ = 1;
    } else {
      if (bannerWas_ != 0 || forceRedraw_) tft_.fillRect(0, 0, W, BANNER_H, TFT_BLACK);
      bannerWas_ = 0;
      // idle header: title left, clock + link state right
      tft_.setTextColor(TFT_CYAN, TFT_BLACK);
      tft_.setTextDatum(ML_DATUM);
      tft_.drawString("SMARTTRIAGE MONITOR", 6, BANNER_H / 2, 2);
      char right[40];
      if (s.clockSynced) {
        time_t t = time(nullptr); struct tm tmv; localtime_r(&t, &tmv);
        snprintf(right, sizeof(right), "%02d:%02d  %s", tmv.tm_hour, tmv.tm_min,
                 s.backendUp ? "LINK OK" : (s.wifiUp ? "NO SERVER" : "NO WIFI"));
      } else {
        snprintf(right, sizeof(right), "--:--  %s",
                 s.backendUp ? "LINK OK" : (s.wifiUp ? "NO SERVER" : "NO WIFI"));
      }
      tft_.setTextColor(s.backendUp ? TFT_GREEN : TFT_ORANGE, TFT_BLACK);
      tft_.setTextDatum(MR_DATUM);
      tft_.drawString(right, W - 6, BANNER_H / 2, 2);
      tft_.setTextDatum(TL_DATUM);
    }
  }

  // ---------- value cell: sprite-rendered, redrawn only on change ----------
  struct Cell { char last[20] = ""; };
  Cell cells_[14];

  // Only fonts 2 and 4 are used for data (the fonts the previously working
  // build proved are enabled in this project's User_Setup.h); `size` scales
  // font 4 up for the big numerics. Fonts 6/7 are avoided deliberately —
  // they lack the '/' glyph a BP reading needs, and may not be loaded.
  void cell(int id, int x, int y, int w, int h, const char *txt,
            uint16_t color, uint8_t font, uint8_t size = 1) {
    if (!forceRedraw_ && strcmp(cells_[id].last, txt) == 0) return;
    strlcpy(cells_[id].last, txt, sizeof(cells_[id].last));
    val_.createSprite(w, h);
    val_.fillSprite(TFT_BLACK);
    val_.setTextColor(color, TFT_BLACK);
    val_.setTextDatum(MC_DATUM);
    val_.setTextSize(size);
    val_.drawString(txt, w / 2, h / 2, font);
    val_.setTextSize(1);
    val_.pushSprite(x, y);
    val_.deleteSprite();
  }

  static uint16_t bandColor(float v, float warnLo, float warnHi, float critLo, float critHi) {
    if (v <= 0) return TFT_DARKGREY;
    if (v < critLo || v > critHi) return TFT_RED;
    if (v < warnLo || v > warnHi) return TFT_YELLOW;
    return TFT_GREEN;
  }

  // =====================================================================
  //  PAGE 1 — dashboard
  // =====================================================================
  void drawDashboard(const MonitorState &s) {
    int top = BANNER_H + 4;
    int tileW = (W - 18) / 2, tileH = (H - top - 96) / 2;
    struct Tile { const char *label; const char *unit; };

    if (forceRedraw_) {
      static const Tile tiles[4] = {{"HR", "bpm"}, {"SpO2", "%"}, {"TEMP", "C"}, {"RESP", "/min"}};
      for (int i = 0; i < 4; i++) {
        int x = 6 + (i % 2) * (tileW + 6), y = top + (i / 2) * (tileH + 6);
        tft_.drawRoundRect(x, y, tileW, tileH, 8, TFT_DARKGREY);
        tft_.setTextColor(TFT_SILVER, TFT_BLACK);
        tft_.drawString(tiles[i].label, x + 10, y + 6, 2);
        tft_.setTextDatum(TR_DATUM);
        tft_.drawString(tiles[i].unit, x + tileW - 10, y + 6, 2);
        tft_.setTextDatum(TL_DATUM);
      }
      int by = top + 2 * (tileH + 6);
      tft_.drawRoundRect(6, by, W - 12, H - by - 26, 8, TFT_DARKGREY);
      tft_.setTextColor(TFT_SILVER, TFT_BLACK);
      tft_.drawString("BP (last reading)", 16, by + 6, 2);
    }

    char t[20];
    // HR (+ source tag)
    snprintf(t, sizeof(t), s.hr > 0 ? "%.0f" : "--", s.hr);
    cell(0, 6 + 8, top + 22, tileW - 16, tileH - 42,
         t, bandColor(s.hr, ALM_HR_WARN_LOW, ALM_HR_WARN_HIGH, ALM_HR_CRIT_LOW, ALM_HR_CRIT_HIGH), 4, 2);
    cell(10, 6 + 8, top + tileH - 20, tileW - 16, 16,
         s.hr <= 0 ? "" : (s.hrFromEcg ? "ECG" : "PULSE-OX"), TFT_DARKGREY, 1);

    snprintf(t, sizeof(t), s.spo2 > 0 ? "%.0f" : "--", s.spo2);
    cell(1, 12 + tileW + 8, top + 22, tileW - 16, tileH - 42,
         t, bandColor(s.spo2, ALM_SPO2_WARN, 101, ALM_SPO2_CRIT, 101), 4, 2);

    snprintf(t, sizeof(t), s.temp > 0 ? "%.1f" : "--.-", s.temp);
    cell(2, 6 + 8, top + tileH + 6 + 22, tileW - 16, tileH - 42,
         t, bandColor(s.temp, ALM_TEMP_WARN_LOW, ALM_TEMP_WARN_HIGH, ALM_TEMP_CRIT_LOW, ALM_TEMP_CRIT_HIGH), 4, 2);

    snprintf(t, sizeof(t), s.rr > 0 ? "%.0f" : "--", s.rr);
    cell(3, 12 + tileW + 8, top + tileH + 6 + 22, tileW - 16, tileH - 42,
         t, bandColor(s.rr, ALM_RR_WARN_LOW, ALM_RR_WARN_HIGH, 1, 99), 4, 2);

    // BP strip
    int by = top + 2 * (tileH + 6);
    if (s.bpLast.valid) {
      char when[28] = "time unsynced";
      if (s.bpLast.at > 0) {
        struct tm tmv; localtime_r(&s.bpLast.at, &tmv);
        snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min);
      }
      snprintf(t, sizeof(t), "%d/%d", s.bpLast.sys, s.bpLast.dia);
      uint16_t c = (s.bpLast.sys > ALM_SYS_CRIT_HIGH || s.bpLast.sys < ALM_SYS_CRIT_LOW) ? TFT_RED : TFT_GREEN;
      cell(4, 16, by + 22, 150, 34, t, c, 4);
      char meta[56];
      snprintf(meta, sizeof(meta), "MAP %d   at %s%s", s.bpLast.map, when, s.bpCalibrated ? "" : "  UNCAL");
      cell(5, 176, by + 30, W - 196, 18, meta, TFT_SILVER, 2);
    } else {
      cell(4, 16, by + 22, 150, 34, "--/--", TFT_DARKGREY, 4);
      cell(5, 176, by + 30, W - 196, 18, "no reading - use BP page", TFT_DARKGREY, 2);
    }

    // per-channel status chips
    char chips[64];
    snprintf(chips, sizeof(chips), "SPO2:%s  TEMP:%s  ECG:%s  CUFF:%s",
             chanTxt(s.chSpo2), chanTxt(s.chTemp), chanTxt(s.chEcg), chanTxt(s.chBp));
    cell(6, 6, H - 24, W - 12, 12, chips, TFT_DARKGREY, 1);
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
  int waveX_ = 0;

  void drawWaveforms(const MonitorState &s) {
    int top = BANNER_H + 2;
    int numW = 96;                        // numeric column on the right
    int plotW = W - numW - 4;
    int ecgH = (H - top - 20) * 3 / 5;
    int plethH = (H - top - 20) - ecgH - 4;
    int ecgY = top, plethY = top + ecgH + 4;

    if (!waveInit_) {
      tft_.fillRect(0, top, plotW, H - top - 16, TFT_BLACK);
      tft_.drawRect(0, ecgY, plotW, ecgH, 0x0200);        // faint green frame
      tft_.drawRect(0, plethY, plotW, plethH, 0x0010);    // faint blue frame
      tft_.setTextColor(TFT_GREEN, TFT_BLACK);  tft_.drawString("ECG  (Lead II)", 6, ecgY + 3, 1);
      tft_.setTextColor(TFT_CYAN, TFT_BLACK);   tft_.drawString("PLETH (SpO2)", 6, plethY + 3, 1);
      waveX_ = 1;
      ecgReadHead_ = g_ecgWaveHead;
      plethReadHead_ = g_plethWaveHead;
      waveInit_ = true;
    }

    if (!s.simulation && s.chEcg == Chan::NO_CONTACT) {
      cell(7, plotW / 2 - 90, ecgY + ecgH / 2 - 10, 180, 20, "ECG LEADS OFF", TFT_RED, 2);
    } else {
      cell(7, plotW / 2 - 90, ecgY + ecgH / 2 - 10, 1, 1, "", TFT_BLACK, 1); // clear marker
      drawTrace(g_ecgWave, g_ecgWaveHead, ecgReadHead_, ecgY + 2, ecgH - 4, plotW, TFT_GREEN);
    }
    drawTrace(g_plethWave, g_plethWaveHead, plethReadHead_, plethY + 2, plethH - 4, plotW, TFT_CYAN);

    // numerics column
    char t[16];
    snprintf(t, sizeof(t), s.hr > 0 ? "%.0f" : "--", s.hr);
    cell(8, W - numW, ecgY + 14, numW - 4, 40, t,
         bandColor(s.hr, ALM_HR_WARN_LOW, ALM_HR_WARN_HIGH, ALM_HR_CRIT_LOW, ALM_HR_CRIT_HIGH), 4);
    if (forceRedraw_) { tft_.setTextColor(TFT_SILVER, TFT_BLACK); tft_.drawString("HR bpm", W - numW + 8, ecgY + 2, 1); }

    snprintf(t, sizeof(t), s.spo2 > 0 ? "%.0f%%" : "--", s.spo2);
    cell(9, W - numW, plethY + 14, numW - 4, 34, t,
         bandColor(s.spo2, ALM_SPO2_WARN, 101, ALM_SPO2_CRIT, 101), 4);
    if (forceRedraw_) { tft_.setTextColor(TFT_SILVER, TFT_BLACK); tft_.drawString("SpO2", W - numW + 8, plethY + 2, 1); }
  }

  // consume new ring samples; draw one column per sample with an erase-ahead cursor
  void drawTrace(volatile int16_t *ring, volatile uint16_t &headRef,
                 uint16_t &readHead, int y, int h, int plotW, uint16_t color) {
    uint16_t head = headRef;
    int budget = 40;                                   // samples per frame cap
    while (readHead != head && budget-- > 0) {
      readHead = (uint16_t)((readHead + 1) % ECG_WAVE_RING);
      int16_t v = ring[readHead];
      int py = y + h / 2 - (int)((float)v / 2047.0f * (h / 2 - 2));
      py = constrain(py, y, y + h - 1);

      // erase-ahead cursor (classic monitor sweep)
      int eraseX = (waveX_ + 6) % plotW;
      if (eraseX > 1) tft_.drawFastVLine(eraseX, y, h, TFT_BLACK);

      if (waveX_ > 1 && lastPy_[color & 1] >= 0 && waveX_ - 1 != 0) {
        tft_.drawLine(waveX_ - 1, lastPy_[color & 1], waveX_, py, color);
      } else {
        tft_.drawPixel(waveX_, py, color);
      }
      lastPy_[color & 1] = py;
      if (++waveX_ >= plotW - 1) { waveX_ = 1; lastPy_[0] = lastPy_[1] = -1; }
    }
  }
  int lastPy_[2] = {-1, -1};

  // =====================================================================
  //  PAGE 3 — trends
  // =====================================================================
  void drawTrends(const MonitorState &s) {
    int top = BANNER_H + 4;
    int chartW = W * 3 / 5 - 10;
    int chartH = (H - top - 24) / 4 - 4;

    struct Row { const char *label; const TrendRing *r; uint16_t color; };
    Row rows[4] = {
      {"HR",   &g_trendHr,   TFT_GREEN},
      {"SpO2", &g_trendSpo2, TFT_CYAN},
      {"TEMP", &g_trendTemp, TFT_ORANGE},
      {"RESP", &g_trendRr,   TFT_VIOLET},
    };

    uint32_t now = millis();
    if (!forceRedraw_ && now - lastTrendDrawMs_ < 2000) { drawBpHistory(s, chartW); return; }
    lastTrendDrawMs_ = now;

    for (int i = 0; i < 4; i++) {
      int y = top + i * (chartH + 4);
      tft_.fillRect(0, y, chartW + 8, chartH, TFT_BLACK);
      tft_.drawRect(4, y, chartW, chartH, TFT_DARKGREY);
      tft_.setTextColor(rows[i].color, TFT_BLACK);
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
      tft_.setTextColor(TFT_DARKGREY, TFT_BLACK);
      tft_.setTextDatum(TR_DATUM);
      tft_.drawString(lbl, chartW, y + 2, 1);
      tft_.setTextDatum(TL_DATUM);
    }
    drawBpHistory(s, chartW);
  }

  void drawBpHistory(const MonitorState &s, int chartW) {
    int x = chartW + 14, top = BANNER_H + 4;
    if (forceRedraw_) {
      tft_.setTextColor(TFT_SILVER, TFT_BLACK);
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
      cell(10 + (i % 3), x, top + 22 + i * 20, W - x - 6, 18, line, TFT_WHITE, 2);
      // NOTE: cells 10-12 rotate — acceptable coarse dedup for a short list
    }
  }
  uint32_t lastTrendDrawMs_ = 0;

  // =====================================================================
  //  PAGE 4 — blood pressure
  // =====================================================================
  int btnX_, btnY_, btnW_, btnH_;

  void drawBpPage(const MonitorState &s) {
    int top = BANNER_H + 6;
    btnW_ = 200; btnH_ = 54;
    btnX_ = W / 2 - btnW_ / 2; btnY_ = H - btnH_ - 30;

    if (forceRedraw_) {
      tft_.setTextColor(TFT_SILVER, TFT_BLACK);
      tft_.drawString("OSCILLOMETRIC BLOOD PRESSURE", 10, top, 2);
      if (!s.bpCalibrated) {
        tft_.setTextColor(TFT_ORANGE, TFT_BLACK);
        tft_.setTextDatum(TR_DATUM);
        tft_.drawString("UNCALIBRATED", W - 8, top, 2);
        tft_.setTextDatum(TL_DATUM);
      }
    }

    bool busy = s.bpPhase == BpPhase::INFLATING || s.bpPhase == BpPhase::MEASURING
             || s.bpPhase == BpPhase::COMPUTING || s.bpPhase == BpPhase::ZEROING;

    // result / live area
    char t[32];
    if (busy) {
      snprintf(t, sizeof(t), "%.0f", s.cuffPressure);
      cell(11, W / 2 - 110, top + 30, 220, 56, t, TFT_ORANGE, 4, 2);
      cell(12, W / 2 - 110, top + 92, 220, 18, "cuff mmHg", TFT_SILVER, 2);
      const char *phase = s.bpPhase == BpPhase::INFLATING ? "Inflating..."
                        : s.bpPhase == BpPhase::MEASURING ? "Measuring - hold still"
                        : "Computing...";
      cell(13, W / 2 - 130, top + 116, 260, 20, phase, TFT_ORANGE, 2);
      // progress bar
      int bw = W - 80;
      tft_.drawRect(40, top + 146, bw, 14, TFT_DARKGREY);
      tft_.fillRect(41, top + 147, (bw - 2) * s.bpProgress / 100, 12, TFT_ORANGE);
    } else if (s.bpPhase == BpPhase::ERROR) {
      cell(11, W / 2 - 150, top + 30, 300, 40, "FAILED", TFT_RED, 4);
      cell(12, W / 2 - 170, top + 84, 340, 18, s.bpError, TFT_RED, 2);
      cell(13, 40, top + 146, W - 80, 20, "", TFT_BLACK, 1);
      tft_.fillRect(40, top + 146, W - 80, 16, TFT_BLACK);
    } else if (s.bpLast.valid) {
      snprintf(t, sizeof(t), "%d/%d", s.bpLast.sys, s.bpLast.dia);
      cell(11, W / 2 - 140, top + 30, 280, 56, t, TFT_GREEN, 4, 2);
      char meta[48];
      char when[10] = "--:--";
      if (s.bpLast.at > 0) { struct tm tmv; localtime_r(&s.bpLast.at, &tmv); snprintf(when, sizeof(when), "%02d:%02d", tmv.tm_hour, tmv.tm_min); }
      snprintf(meta, sizeof(meta), "MAP %d mmHg   measured %s", s.bpLast.map, when);
      cell(12, W / 2 - 150, top + 96, 300, 18, meta, TFT_SILVER, 2);
      cell(13, 40, top + 146, W - 80, 20, "", TFT_BLACK, 1);
      tft_.fillRect(40, top + 146, W - 80, 16, TFT_BLACK);
    } else {
      cell(11, W / 2 - 150, top + 40, 300, 40, "no reading yet", TFT_DARKGREY, 4);
      cell(12, W / 2 - 170, top + 92, 340, 18, "wrap cuff snugly, then press start", TFT_SILVER, 2);
    }

    // start button
    uint16_t bc = busy ? TFT_DARKGREY : TFT_GREEN;
    tft_.fillRoundRect(btnX_, btnY_, btnW_, btnH_, 10, bc);
    tft_.setTextColor(TFT_BLACK, bc);
    tft_.setTextDatum(MC_DATUM);
    tft_.drawString(busy ? "MEASURING..." : "START BP", btnX_ + btnW_ / 2, btnY_ + btnH_ / 2, 4);
    tft_.setTextDatum(TL_DATUM);
  }

  // =====================================================================
  //  PAGE 5 — device status
  // =====================================================================
  int simBtnY_ = 0;
  void drawDevice(const MonitorState &s) {
    int top = BANNER_H + 6, lh = 22, y = top;
    char line[64];

    auto row = [&](int id, const char *v, uint16_t c) {
      cell(id, 150, y, W - 156, lh - 4, v, c, 2);
      y += lh;
    };
    if (forceRedraw_) {
      const char *labels[] = {"WiFi", "Signal", "Server", "Last sync", "Device", "Firmware",
                              "Transmit", "Buffered", "Sensors", "Clock"};
      int ly = top;
      for (int i = 0; i < 10; i++) { tft_.setTextColor(TFT_SILVER, TFT_BLACK); tft_.drawString(labels[i], 10, ly, 2); ly += lh; }
    }

    snprintf(line, sizeof(line), "%s%s", s.wifiUp ? "connected  " : "DISCONNECTED", s.wifiUp ? WiFi.SSID().c_str() : "");
    row(0, line, s.wifiUp ? TFT_GREEN : TFT_RED);
    snprintf(line, sizeof(line), s.wifiUp ? "%d dBm" : "-", s.wifiRssi);
    row(1, line, s.wifiRssi > -70 ? TFT_GREEN : TFT_YELLOW);
    row(2, s.backendUp ? "SmartTriage receiving" : "NOT REACHABLE", s.backendUp ? TFT_GREEN : TFT_RED);
    if (s.lastAckAt > 0) {
      struct tm tmv; localtime_r(&s.lastAckAt, &tmv);
      snprintf(line, sizeof(line), "%02d:%02d:%02d", tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
    } else strlcpy(line, "never", sizeof(line));
    row(3, line, s.lastAckAt > 0 ? TFT_WHITE : TFT_ORANGE);
    row(4, DEVICE_SERIAL " (session bound at ED)", TFT_WHITE);
    row(5, FIRMWARE_VERSION, TFT_WHITE);
    snprintf(line, sizeof(line), "%lu ok / %lu failed", (unsigned long)s.txOk, (unsigned long)s.txFail);
    row(6, line, TFT_WHITE);
    snprintf(line, sizeof(line), "%u offline readings", s.offlineBuffered);
    row(7, line, s.offlineBuffered ? TFT_ORANGE : TFT_WHITE);
    snprintf(line, sizeof(line), "SPO2:%s TEMP:%s ECG:%s CUFF:%s",
             chanTxt(s.chSpo2), chanTxt(s.chTemp), chanTxt(s.chEcg), chanTxt(s.chBp));
    row(8, line, TFT_WHITE);
    row(9, s.clockSynced ? "NTP synced (UTC)" : "NOT SYNCED", s.clockSynced ? TFT_GREEN : TFT_ORANGE);

    // simulation toggle
    simBtnY_ = y + 8;
    uint16_t sc = s.simulation ? TFT_ORANGE : 0x39E7;
    tft_.fillRoundRect(10, simBtnY_, 220, 40, 8, sc);
    tft_.setTextColor(s.simulation ? TFT_BLACK : TFT_WHITE, sc);
    tft_.setTextDatum(MC_DATUM);
    tft_.drawString(s.simulation ? "SIMULATION: ON" : "SIMULATION: OFF", 120, simBtnY_ + 20, 2);
    tft_.setTextDatum(TL_DATUM);
  }

  // =====================================================================
  //  Touch: swipe navigation + page-local buttons + alarm silence
  // =====================================================================
  bool touching_ = false;
  uint16_t downX_ = 0, downY_ = 0, lastX_ = 0, lastY_ = 0;
  uint32_t downMs_ = 0;

  void handleTouch(const MonitorState &s) {
    uint16_t x, y;
    bool pressed = tft_.getTouch(&x, &y);

    if (pressed && !touching_) {                 // touch start
      touching_ = true; downX_ = x; downY_ = y; lastX_ = x; lastY_ = y; downMs_ = millis();
    } else if (pressed) {                        // drag — remember position
      lastX_ = x; lastY_ = y;
    } else if (!pressed && touching_) {          // touch release
      touching_ = false;
      // getTouch reports nothing on release — use the last pressed coords.
      int dx = (int)lastX_ - (int)downX_;
      if (abs(dx) > 70) {
        int p = (int)page_ + (dx < 0 ? 1 : -1);
        p = (p + (int)Page::COUNT) % (int)Page::COUNT;
        page_ = (Page)p;
        pageDirty_ = true;
        return;
      }
      onTap(downX_, downY_, s);
    }
  }

  void onTap(uint16_t x, uint16_t y, const MonitorState &s) {
    // banner tap → silence alarms
    if (y < BANNER_H + 6 && s.alarms.any()) {
      if (stateLock()) { g_state.alarmSilencedUntil = millis() + ALARM_SILENCE_MS; stateUnlock(); }
      return;
    }
    // page dots strip → tap left/right half jumps a page
    if (y > H - 22) {
      int p = ((int)page_ + (x > W / 2 ? 1 : -1) + (int)Page::COUNT) % (int)Page::COUNT;
      page_ = (Page)p; pageDirty_ = true;
      return;
    }
    if (page_ == Page::BP) {
      bool busy = s.bpPhase == BpPhase::INFLATING || s.bpPhase == BpPhase::MEASURING
               || s.bpPhase == BpPhase::COMPUTING || s.bpPhase == BpPhase::ZEROING;
      if (!busy && x >= btnX_ && x <= btnX_ + btnW_ && y >= btnY_ && y <= btnY_ + btnH_) {
        if (stateLock()) { g_state.bpRequested = true; stateUnlock(); }
        tone(PIN_BUZZER, 900, 60);
      }
    }
    if (page_ == Page::DEVICE && simBtnY_ > 0
        && x >= 10 && x <= 230 && y >= simBtnY_ && y <= simBtnY_ + 40) {
      if (stateLock()) {
        g_state.simulation = !g_state.simulation;
        if (!g_state.simulation) {
          // leaving sim: wipe demo values so real pipelines repopulate
          g_state.hr = g_state.spo2 = g_state.temp = g_state.rr = 0;
          g_state.chSpo2 = g_state.chTemp = g_state.chEcg = Chan::ABSENT;
        }
        stateUnlock();
      }
      pageDirty_ = true;
      tone(PIN_BUZZER, 1200, 80);
    }
  }

  int bannerWas_ = -1;
};
