#include <SoftwareSerial.h>
#include <TinyGPS++.h>
#include <SIM800L.h>

// --- GPS Module ---
SoftwareSerial gpsSerial(4, 3);  // RX=4, TX=3
TinyGPSPlus gps;

// --- GSM Module ---
SIM800L sim800l(8, 7);   // RX, TX

// --- Relay Pin ---
#define RELAY_PIN 9

// ----------------- GSM CALLBACKS -----------------
void handleSMS(String number, String message) {
  if (message == "on") {
    digitalWrite(RELAY_PIN, LOW);
    sim800l.sendSMS(number, "Relay is ON");
  } 
  else if (message == "off") {
    digitalWrite(RELAY_PIN, HIGH);
    sim800l.sendSMS(number, "Relay is OFF");
  } 
  else if (message == "location") {
    sendLocationSMS(number);
  }
}

void handleCall(String number) {
  bool flag = digitalRead(RELAY_PIN);
  digitalWrite(RELAY_PIN, !flag); // toggle relay
}

// ----------------- CORE FUNCTIONS -----------------
void sendLocationSMS(String number) {
  if (gps.location.isValid()) {
    String smsText = "Car Location: http://maps.google.com/maps?q=loc:" +
                     String(gps.location.lat(), 6) + "," +
                     String(gps.location.lng(), 6);
    sim800l.sendSMS(number, smsText);
  } else {
    sim800l.sendSMS(number, "GPS not fixed yet.");
  }
}

void sendLocationBT() {
  if (gps.location.isValid()) {
    Serial.println("Car Location: http://maps.google.com/maps?q=loc:" +
                   String(gps.location.lat(), 6) + "," +
                   String(gps.location.lng(), 6));
  } else {
    Serial.println("GPS not fixed yet.");
  }
}

// ----------------- SETUP -----------------
void setup() {
  // ⚠️ Serial is your Bluetooth now (pins 0 and 1)!
  Serial.begin(9600);      
  gpsSerial.begin(9600);

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, HIGH); // Relay OFF initially

  sim800l.begin(9600);
  sim800l.setSMSCallback(handleSMS);
  sim800l.setCallCallback(handleCall);
}

// ----------------- LOOP -----------------
void loop() {
  sim800l.listen(); // check GSM

  // Handle Bluetooth commands
  if (Serial.available()) {
    char cmd = Serial.read();

    if (cmd == '1') {
      digitalWrite(RELAY_PIN, LOW);
      Serial.println("Relay ON");
    } 
    else if (cmd == '0') {
      digitalWrite(RELAY_PIN, HIGH);
      Serial.println("Relay OFF");
    } 
    else if (cmd == 'L' || cmd == 'l') {
      sendLocationBT();
    }
  }

  // Handle GPS data
  while (gpsSerial.available() > 0) {
    gps.encode(gpsSerial.read());
  }
}
