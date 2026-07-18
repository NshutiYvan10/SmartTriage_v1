/*
 * cuffadc.h — HX710-family 24-bit cuff-pressure ADC, bit-banged.
 *
 * HARDWARE TRUTHS (learned the hard way on the real box):
 *   - This ADC is a TWO-WIRE device (DOUT + SCK). It has NO CHIP-SELECT,
 *     so it hears EVERY edge on its clock line. Our clock line is GPIO 12
 *     — the display/touch SPI clock (fixed, soldered wiring). Any screen
 *     redraw therefore scrambles the chip's internal bit counter.
 *   - It is 24-bit, two's complement, MSB first — NOT a 16-bit ADC. The
 *     v3.0-3.1 driver read 16 bits without a ready-wait, which returned
 *     all-ones (= "+1200 mmHg") while the chip was busy and garbage
 *     otherwise (observed live: zero offset 1200.1, cuff -989.7).
 *   - DOUT high = conversion in progress; DOUT low = data ready.
 *   - Holding SCK high > 60 µs power-downs the chip; releasing it resets
 *     the conversation — our reliable resync after any bus disturbance.
 *   - 25/26/27 clock pulses select the next conversion mode; we always
 *     clock 27 (24 data + 3) → 40 samples/s differential input.
 *
 * PROTOCOL FOR CALLERS (enforced in bp.h):
 *   own g_spiBusMutex for the WHOLE measurement, hold a CuffAdcPinGuard,
 *   cuffAdcResetSync() once (and after any touch poll), then
 *   wait-ready → clock-out per sample. Never interleave display SPI.
 */
#pragma once
#include <Arduino.h>
#include "soc/gpio_struct.h"
#include "config.h"

// Borrows GPIO 12 (the display SPI clock) as a plain GPIO output for the
// guard's lifetime, then restores its output-matrix routing byte-for-byte
// (digitalWrite is a no-op on a matrix-routed pin — see bp.h history).
// Also asserts the module's CS line if one is wired (harmless if not).
struct CuffAdcPinGuard {
  uint32_t savedRouting;
  CuffAdcPinGuard() {
    savedRouting = GPIO.func_out_sel_cfg[PIN_PRES_SCK].val;
    pinMode(PIN_PRES_SCK, OUTPUT);
    digitalWrite(PIN_PRES_SCK, LOW);
    digitalWrite(PIN_PRES_CS, LOW);
  }
  ~CuffAdcPinGuard() {
    digitalWrite(PIN_PRES_CS, HIGH);
    GPIO.func_out_sel_cfg[PIN_PRES_SCK].val = savedRouting;
  }
};

// Power-down reset: the one reliable way to resynchronise the bit counter
// after the shared clock carried foreign traffic. The next conversion
// restarts from scratch (first ready can take a few hundred ms).
inline void cuffAdcResetSync() {
  digitalWrite(PIN_PRES_SCK, HIGH);
  delayMicroseconds(80);              // > 60 µs → power-down + counter reset
  digitalWrite(PIN_PRES_SCK, LOW);
}

inline bool cuffAdcReady() { return digitalRead(PIN_PRES_MISO) == LOW; }

// Poll DOUT for "data ready". Uses vTaskDelay so long waits never starve
// other tasks (only the shared-bus mutex is held, deliberately).
inline bool cuffAdcWaitReady(uint32_t capMs) {
  uint32_t start = millis();
  while (!cuffAdcReady()) {
    if (millis() - start >= capMs) return false;
    vTaskDelay(pdMS_TO_TICKS(2));
  }
  return true;
}

// Clock out one 24-bit two's-complement sample (+3 mode pulses → 40 SPS
// next). Caller must have seen cuffAdcReady() true. ~100 µs, interrupts
// masked so a scheduler tick can't stretch a clock-high into a power-down.
inline int32_t cuffAdcClockOut24() {
  int32_t v = 0;
  noInterrupts();
  for (int i = 0; i < 24; i++) {
    digitalWrite(PIN_PRES_SCK, HIGH); delayMicroseconds(1);
    v = (v << 1) | (digitalRead(PIN_PRES_MISO) ? 1 : 0);
    digitalWrite(PIN_PRES_SCK, LOW);  delayMicroseconds(1);
  }
  for (int i = 0; i < 3; i++) {       // 27 pulses total → 40 SPS differential
    digitalWrite(PIN_PRES_SCK, HIGH); delayMicroseconds(1);
    digitalWrite(PIN_PRES_SCK, LOW);  delayMicroseconds(1);
  }
  interrupts();
  if (v & 0x800000) v -= 0x1000000;   // sign-extend 24-bit
  return v;
}
