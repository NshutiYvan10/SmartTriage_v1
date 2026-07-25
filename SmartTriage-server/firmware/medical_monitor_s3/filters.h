/*
 * filters.h — shared signal-conditioning primitives.
 * Small, allocation-free, and testable: EMA, median, outlier gates,
 * a mains-notch biquad, and a DC/baseline tracker.
 */
#pragma once
#include <math.h>

// ---------- EMA with explicit initialization ----------
struct Ema {
  float value = 0.0f;
  bool  primed = false;
  float update(float x, float alpha) {
    value = primed ? alpha * x + (1.0f - alpha) * value : x;
    primed = true;
    return value;
  }
  void reset() { value = 0.0f; primed = false; }
};

// ---------- Fixed-capacity median (insertion sort — tiny N) ----------
template <int N>
struct MedianRing {
  float buf[N];
  int   idx = 0, count = 0;
  void push(float x) {
    buf[idx] = x;
    idx = (idx + 1) % N;
    if (count < N) count++;
  }
  float median() const {
    if (count == 0) return 0.0f;
    float s[N];
    for (int i = 0; i < count; i++) s[i] = buf[i];
    for (int i = 1; i < count; i++) {
      float key = s[i]; int j = i - 1;
      while (j >= 0 && s[j] > key) { s[j + 1] = s[j]; j--; }
      s[j + 1] = key;
    }
    return s[count / 2];
  }
  void reset() { idx = 0; count = 0; }
};

// ---------- Outlier gates ----------
inline bool outlierPercent(float x, float baseline, float maxFrac) {
  if (baseline == 0.0f) return false;         // nothing to compare against yet
  return fabsf(x - baseline) / fabsf(baseline) > maxFrac;
}
inline bool outlierAbsolute(float x, float baseline, float maxAbs) {
  if (baseline == 0.0f) return false;
  return fabsf(x - baseline) > maxAbs;
}

// ---------- DC / baseline-wander tracker ----------
// Slow EMA of the raw signal; (raw - dc) is a cheap ~0.4 Hz high-pass that
// removes electrode baseline drift without smearing the QRS complex.
struct DcTracker {
  float dc = 0.0f;
  bool  primed = false;
  float highpass(float x, float alpha) {
    if (!primed) { dc = x; primed = true; }
    dc += alpha * (x - dc);
    return x - dc;
  }
  void reset() { dc = 0.0f; primed = false; }
};

// ---------- One-pole low-pass ----------
// (raw → smoothed): removes broadband noise above a soft cutoff set by
// alpha. Used to de-noise the DISPLAY traces and, on the pleth, to hand
// the SpO2 AC estimator a clean pulsatile signal. alpha ≈ dt/(RC+dt):
// higher alpha = higher cutoff = less smoothing.
struct LowPass {
  float value = 0.0f;
  bool  primed = false;
  float process(float x, float alpha) {
    value = primed ? value + alpha * (x - value) : x;
    primed = true;
    return value;
  }
  void reset() { value = 0.0f; primed = false; }
};

// ---------- Mains-notch biquad (50/60 Hz) ----------
// Direct-form-I notch, Q≈8. Coefficients computed once from the sample
// rate; float math is cheap on the S3's FPU.
struct NotchFilter {
  float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
  float x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  void configure(float mainsHz, float sampleHz, float q = 8.0f) {
    float w0 = 2.0f * PI * mainsHz / sampleHz;
    float alpha = sinf(w0) / (2.0f * q);
    float a0 = 1.0f + alpha;
    b0 = 1.0f / a0;
    b1 = -2.0f * cosf(w0) / a0;
    b2 = 1.0f / a0;
    a1 = -2.0f * cosf(w0) / a0;
    a2 = (1.0f - alpha) / a0;
  }
  float process(float x) {
    float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1; x1 = x;
    y2 = y1; y1 = y;
    return y;
  }
  void reset() { x1 = x2 = y1 = y2 = 0; }
};
