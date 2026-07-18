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
 *   - The FIRST sample after a reset is unreliable (observed live: an
 *     exactly-doubled value = one-bit framing slip). Callers must settle:
 *     discard two samples after every resync (cuffAdcSyncSettle).
 *   - 25/26/27 clock pulses select the next conversion mode; we always
 *     clock 27 (24 data + 3) → 40 samples/s differential input.
 *
 * BUS PROTOCOL (v3.2.1): the BP cycle SHUTS DOWN the display SPI driver
 * (SPIClass::end) before touching these pins and re-begins it afterwards
 * — clean driver-level detach/reattach. The earlier register-level pin
 * juggling left the SPI peripheral wedged: the first display/touch
 * operation after the cycle spun forever (three UI freezes on real
 * hardware; the task watchdog's reboot was the only way out).
 */
#pragma once
#include <Arduino.h>
#include "config.h"

// Claims the shared-bus pins as plain GPIO for the guard's lifetime.
// PRECONDITION: the display SPI driver is NOT attached (either not yet
// begun — boot zero-cal — or explicitly ended by the BP cycle).
struct CuffAdcPinGuard {
  CuffAdcPinGuard() {
    pinMode(PIN_PRES_SCK, OUTPUT);      // shared clock (display SCLK)
    digitalWrite(PIN_PRES_SCK, LOW);
    pinMode(SHARED_PIN_MOSI, OUTPUT);   // for the in-cycle touch poll
    digitalWrite(SHARED_PIN_MOSI, LOW);
    pinMode(SHARED_PIN_MISO, INPUT);    // touch data out
    digitalWrite(PIN_PRES_CS, LOW);
  }
  ~CuffAdcPinGuard() {
    digitalWrite(PIN_PRES_CS, HIGH);
    // Pin routing is reclaimed by SPIClass::begin() after the cycle.
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

// Reset + discard the first two conversions (framing-slip protection —
// an exactly-doubled sample was observed live from the first-after-reset
// read). ~150-700 ms depending on the chip's post-reset conversion rate.
inline bool cuffAdcSyncSettle() {
  cuffAdcResetSync();
  for (int i = 0; i < 2; i++) {
    if (!cuffAdcWaitReady(i == 0 ? 700 : 150)) return false;
    (void)cuffAdcClockOut24();
  }
  return true;
}
