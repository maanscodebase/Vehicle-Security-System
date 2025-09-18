#include <SIM800L.h>

SIM800L sim800l(8, 7); //Rx, Tx

#define RELAY_PIN 9

void handleSMS(String number, String message) {
  Serial.println("number: " + number + "\nMessage: " + message);
  if(message == "on") {
    digitalWrite(RELAY_PIN, LOW);
    sim800l.sendSMS(number, "Relay is ON");
  } 
  else if(message == "off") {
    digitalWrite(RELAY_PIN, HIGH);
    sim800l.sendSMS(number, "Relay is OFF");
  }
    
}

void handleCall(String number) {
  Serial.println("New call from " + number);
  boolean flag = digitalRead(RELAY_PIN);
  digitalWrite(RELAY_PIN, !flag);
}

void setup() {
  Serial.begin(9600);
  
  pinMode(RELAY_PIN, OUTPUT);
  
  sim800l.begin(9600);
  
  sim800l.setSMSCallback(handleSMS);
  sim800l.setCallCallback(handleCall);
}

void loop() {
  sim800l.listen();
}