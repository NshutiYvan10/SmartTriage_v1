#define USER_SETUP_LOADED

// Driver
#define ILI9488_DRIVER

// Display size
#define TFT_WIDTH  320
#define TFT_HEIGHT 480

// SPI Bus
#define USE_HSPI_PORT

// Pins
#define TFT_MISO 13
#define TFT_MOSI 11
#define TFT_SCLK 12
#define TFT_CS   10
#define TFT_DC    8
#define TFT_RST   9
#define TFT_BL   -1
#define TOUCH_CS  5

// SPI Speeds
#define SPI_FREQUENCY       27000000
#define SPI_READ_FREQUENCY  20000000
#define SPI_TOUCH_FREQUENCY  2500000

// Fonts
#define LOAD_GLCD
#define LOAD_FONT2
#define LOAD_FONT4
#define LOAD_FONT6
#define LOAD_FONT7
#define LOAD_FONT8
#define SMOOTH_FONT

#define SUPPORT_TRANSACTIONS