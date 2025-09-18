#include <SoftwareSerial.h>
#include <TinyGPS++.h>

// --- GPS Module Connections ---
// Connect GPS TX to Arduino pin 4
SoftwareSerial gpsSerial(4, 255); // Pin 255 is used to indicate an unused RX pin
TinyGPSPlus gps;

void setup() {
  Serial.begin(9600);
  gpsSerial.begin(9600);
  Serial.println("GPS Test Started. Waiting for fix...");
}

void loop() {
  // Read data from the GPS module
  while (gpsSerial.available() > 0) {
    // Feed the data to the TinyGPS++ library
    if (gps.encode(gpsSerial.read())) {
      // Once the library has decoded a full sentence, check for valid data
      if (gps.location.isValid()) {
        Serial.print("Latitude: ");
        Serial.println(gps.location.lat(), 6);
        Serial.print("Longitude: ");
        Serial.println(gps.location.lng(), 6);
        Serial.print("Satellites: ");
        Serial.println(gps.satellites.value());
        Serial.println("---");
      }
    }
  }

  // If you haven't received a fix, print a status message periodically
  if (millis() > 5000 && gps.charsProcessed() < 10) {
    Serial.println("No GPS data detected. Check wiring and power.");
  }
}
