/*
 * max30100.h — minimal register-level MAX30100 driver.
 *
 * WHY THIS EXISTS: once the sensor box's power wiring was repaired, the
 * pulse-ox chip finally ACKed on the bus (0x57) — but the SparkFun
 * MAX3010x driver still rejected it. A chip that answers 0x57 yet fails
 * the MAX3010x part-ID check, on a board whose LED demonstrably lit
 * under the ORIGINAL sketch's MAX30100 library, is MAX30100 silicon —
 * the older 16-bit sibling with a different register map and FIFO
 * format. This driver speaks that dialect natively; Spo2Pipeline
 * auto-detects which chip is really fitted and uses the right one.
 *
 * Exposes the same call shape the pipeline already uses for the
 * SparkFun driver (check / available / getFIFOIR / getFIFORed /
 * nextSample). Samples are 16-bit and are lifted x4 toward the 18-bit
 * range the shared thresholds (finger-present 50k, AC floor 200) were
 * tuned for — the SpO2 R-ratio itself is scale-invariant.
 */
#pragma once
#include <Arduino.h>
#include <Wire.h>

class Max30100Raw {
public:
  static constexpr uint8_t ADDR = 0x57;

  bool begin(TwoWire &w) {
    wire_ = &w;
    if (read8(0xFF) != 0x11) return false;       // PART_ID: 0x11 = MAX30100
    write8(0x06, 0x40);                          // reset
    uint32_t t0 = millis();
    while ((read8(0x06) & 0x40) && millis() - t0 < 100) delay(2);
    write8(0x02, 0); write8(0x03, 0); write8(0x04, 0);   // clear FIFO pointers
    // SPO2_CONFIG: HI_RES | 100 samples/s | 1600 µs pulse (16-bit ADC)
    write8(0x07, 0x47);
    // LED currents ~27 mA each — visibly lit, comfortable for a fingertip
    write8(0x09, 0x88);
    write8(0x06, 0x03);                          // SpO2 mode → LEDs on
    return true;
  }

  // Drain the chip FIFO into the local queue. Call often.
  void check() {
    uint8_t wr = read8(0x02), rd = read8(0x04);
    int n = (wr - rd) & 0x0F;
    if (n <= 0) return;
    if (n > 16) n = 16;
    wire_->beginTransmission(ADDR);
    wire_->write(0x05);                          // FIFO_DATA
    if (wire_->endTransmission(false) != 0) return;
    int got = wire_->requestFrom((int)ADDR, n * 4);
    for (int i = 0; i + 3 < got; i += 4) {
      uint16_t ir  = (uint16_t)((wire_->read() << 8) | wire_->read());
      uint16_t red = (uint16_t)((wire_->read() << 8) | wire_->read());
      if (count_ < QUEUE) {
        int t = (tail_ + count_) % QUEUE;
        qIr_[t] = ir; qRed_[t] = red;
        count_++;
      }
    }
  }

  bool available() const { return count_ > 0; }
  long getFIFOIR()  const { return (long)qIr_[tail_] * 4; }   // x4 → 18-bit-ish range
  long getFIFORed() const { return (long)qRed_[tail_] * 4; }
  void nextSample() { if (count_) { tail_ = (tail_ + 1) % QUEUE; count_--; } }

private:
  uint8_t read8(uint8_t reg) {
    wire_->beginTransmission(ADDR);
    wire_->write(reg);
    if (wire_->endTransmission(false) != 0) return 0xFF;
    if (wire_->requestFrom((int)ADDR, 1) != 1) return 0xFF;
    return (uint8_t)wire_->read();
  }
  void write8(uint8_t reg, uint8_t v) {
    wire_->beginTransmission(ADDR);
    wire_->write(reg);
    wire_->write(v);
    wire_->endTransmission();
  }

  static constexpr int QUEUE = 32;
  uint16_t qIr_[QUEUE] = {0}, qRed_[QUEUE] = {0};
  int tail_ = 0, count_ = 0;
  TwoWire *wire_ = nullptr;
};
