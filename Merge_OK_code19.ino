#include <SoftwareSerial.h>
#include <TinyGPS++.h>
#include <SIM800L.h>

SoftwareSerial gpsSerial(4, 3); 
TinyGPSPlus gps;

SIM800L sim800l(8, 7);  

#define RELAY_PIN 9

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
  digitalWrite(RELAY_PIN, !flag); 
}

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

void setup() {
  Serial.begin(9600);      
  gpsSerial.begin(9600);

  pinMode(RELAY_PIN, OUTPUT);
  digitalWrite(RELAY_PIN, HIGH);
  sim800l.begin(9600);
  sim800l.setSMSCallback(handleSMS);
  sim800l.setCallCallback(handleCall);
}

void loop() {
  sim800l.listen(); 

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

  while (gpsSerial.available() > 0) {
    gps.encode(gpsSerial.read());
  }
}
