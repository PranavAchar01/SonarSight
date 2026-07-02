/*
 * SixthSense — ESP32 BLE connectivity test (NO motors required).
 *
 * Flash this to verify the phone/app actually connects to the ESP32 over BLE,
 * BEFORE wiring up motors. It advertises with the SAME name + service/char UUIDs
 * as the real belt firmware (esp32_belt.ino), so the app's "CONNECT BELT" button
 * (and MCP belt_test / DEBUG_BELT) connect to it identically.
 *
 * What it does:
 *   - Advertises as "SixthSense-Belt".
 *   - Prints to Serial (115200 baud) when a central connects / disconnects.
 *   - Prints any 4-byte packet [L,C,R,pattern] written by the app.
 *   - Onboard LED: blinks while advertising (waiting), SOLID while connected.
 *
 * HOW TO TEST:
 *   1. Tools → Board → "ESP32 Dev Module"; pick the Port; Upload.
 *   2. Tools → Serial Monitor @ 115200. You should see "advertising…".
 *   3. In the SixthSense app tap CONNECT BELT (grant Bluetooth permission).
 *      -> Serial prints "*** central CONNECTED ***" and the LED goes solid.
 *   4. Tap TEST LEFT / CENTER / RIGHT (or run MCP belt_test) ->
 *      Serial prints the received packet bytes. Connection confirmed.
 *
 * Requires: ESP32 Arduino core + "NimBLE-Arduino" library (see ../esp32_belt/README.md).
 */

#include <NimBLEDevice.h>

static const char* SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* CHAR_UUID    = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";

// Onboard LED — GPIO 2 on most ESP32 dev boards. Change if your board differs;
// the test still works without an LED (just watch the Serial Monitor).
const int LED_PIN = 2;

volatile bool gConnected = false;

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server) {
    gConnected = true;
    Serial.println("*** central CONNECTED (phone is talking to the ESP32) ***");
  }
  void onDisconnect(NimBLEServer* server) {
    gConnected = false;
    Serial.println("central disconnected; re-advertising…");
    NimBLEDevice::startAdvertising();
  }
};

class WriteCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic) {
    NimBLEAttValue v = characteristic->getValue();
    Serial.printf("[conn-test] received %u byte(s): ", (unsigned)v.length());
    for (size_t i = 0; i < v.length(); i++) Serial.printf("%u ", (uint8_t)v[i]);
    if (v.length() >= 4) {
      Serial.printf(" -> L=%u C=%u R=%u pattern=%u",
                    (uint8_t)v[0], (uint8_t)v[1], (uint8_t)v[2], (uint8_t)v[3]);
    }
    Serial.println();
  }
};

void setup() {
  Serial.begin(115200);
  delay(200);
  pinMode(LED_PIN, OUTPUT);
  Serial.println("\n[conn-test] SixthSense ESP32 BLE connectivity test");

  NimBLEDevice::init("SixthSense-Belt");
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* service = server->createService(SERVICE_UUID);
  NimBLECharacteristic* ch = service->createCharacteristic(
      CHAR_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  ch->setCallbacks(new WriteCallbacks());
  service->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setName("SixthSense-Belt");
  NimBLEDevice::startAdvertising();

  Serial.println("[conn-test] advertising as 'SixthSense-Belt' — open the app and tap CONNECT BELT.");
}

void loop() {
  if (gConnected) {
    digitalWrite(LED_PIN, HIGH);              // solid = connected
  } else {
    digitalWrite(LED_PIN, millis() / 500 % 2); // ~1 Hz blink = advertising/waiting
  }
  delay(20);
}
