// ============================================================
//  TFT_eSPI User_Setup.h — RECONSTRUCTION TEMPLATE
//  (the original working setup was lost with the old PC)
//
//  HOW TO USE
//  1. Fill in the GPIO numbers below by READING YOUR ACTUAL WIRING:
//     follow each wire from the display module to the ESP32-S3 pin
//     it lands on, and write that pin number here.
//  2. Copy this file OVER:
//       Documents\Arduino\libraries\TFT_eSPI\User_Setup.h
//     (replace the whole file), then recompile.
//
//  KNOWN FACTS from the working firmware:
//   - Resistive touch (XPT2046) via tft.getTouch  → TOUCH_CS is wired
//   - 480-wide landscape layout                    → 480x320 panel
//   - These S3 pins are TAKEN by sensors, so the display CANNOT be on
//     them: 1, 2, 4, 6, 7, 12, 14, 15, 16, 17, 18, 19, 21, 35, 37, 45, 47
//     Typical free S3 pins your display likely uses:
//     3, 5, 8, 9, 10, 11, 13, 20, 38, 39, 40, 41, 42, 48
// ============================================================

#define USER_SETUP_INFO "SmartTriage monitor 480x320 SPI + XPT2046"

// ---- 1. DRIVER ----------------------------------------------------
// Look at the back of the display PCB for the controller name.
// 480x320 SPI modules are almost always one of these two — enable ONE:
#define ILI9488_DRIVER      // most common 3.5" 480x320 SPI module
//#define ST7796_DRIVER     // common on 4.0" 480x320 modules

// If, after it compiles and runs, colours look wrong:
//  - white/black swapped  → uncomment: //#define TFT_INVERSION_ON
//  - red/blue swapped     → uncomment: //#define TFT_RGB_ORDER TFT_BGR

// ---- 2. DISPLAY PINS — REPLACE every -1 with your real GPIO -------
// Follow the wires from these display-module pins:
#define TFT_MISO  -1   // display "SDO/MISO"  (may be unwired — then leave -1)
#define TFT_MOSI  -1   // display "SDI/MOSI"
#define TFT_SCLK  -1   // display "SCK"
#define TFT_CS    -1   // display "CS"
#define TFT_DC    -1   // display "DC/RS"
#define TFT_RST   -1   // display "RESET"  (if wired to the S3's RST/EN pin instead, leave -1)
// display "LED/BL" backlight: if it goes to a GPIO (not 3V3), set it here:
//#define TFT_BL   -1
//#define TFT_BACKLIGHT_ON HIGH

// ---- 3. TOUCH PIN -------------------------------------------------
// The XPT2046 shares SCK/MOSI/MISO with the display. Its T_CLK, T_DIN,
// T_DO wires join the same three pins above. Only its chip-select is
// separate — follow the wire from the touch "T_CS" pin:
#define TOUCH_CS  -1   // touch "T_CS"   (REQUIRED — getTouch() needs it)
// (T_IRQ is not used by TFT_eSPI — it can stay unconnected.)

// ---- 4. FONTS (the firmware uses 1, 2 and 4) ----------------------
#define LOAD_GLCD
#define LOAD_FONT2
#define LOAD_FONT4
#define LOAD_FONT6
#define LOAD_FONT7
#define LOAD_FONT8
#define LOAD_GFXFF
#define SMOOTH_FONT

// ---- 5. SPI SPEEDS -------------------------------------------------
// ILI9488 is reliable at 27 MHz; drop to 20000000 if you see glitches.
#define SPI_FREQUENCY        27000000
#define SPI_READ_FREQUENCY   20000000
#define SPI_TOUCH_FREQUENCY   2500000
