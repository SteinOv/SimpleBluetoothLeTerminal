[![Codacy Badge](https://api.codacy.com/project/badge/Grade/3f9ba45b5c5449179150010659311f57)](https://www.codacy.com/manual/kai-morich/SimpleBluetoothLeTerminal?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=kai-morich/SimpleBluetoothLeTerminal&amp;utm_campaign=Badge_Grade)

# SimpleBluetoothLeTerminal - fork for use with Tasker

This is a fork of [SimpleBluetoothLeTerminal](https://github.com/kai-morich/SimpleBluetoothLETerminal) with the main goal 
to be able to communicate with BLE devices using Tasker. You can connect, send and disconnect to BLE devices by sending intents. 
When a message by the BLE device is received, a broadcast intent is sent. When losing the connection or failing to connect, 
the service will try to reconnect for a configurable amount of time. <br>
Keep in mind that the external intents can be sent by any app, which may be a security risk. I made this mainly with a 
personal Arduino project in mind and this is my first time coding in Android, so there may be some bugs/imperfections. 
If you spot any, please let me know. 

## How To Use
First install the apk which can be found under app -> release
### Sending data
Create a new intent with component name package `de.kai_morich.simple_bluetooth_le_terminal` and class `de.kai_morich.simple_bluetooth_le_terminal.SerialService`.
Add parameters as extra's (key, value pairs), keys are case-insensitive. Send the intent using context.StartForegroundService(intent)<br>
You can use the following parameters:
- command (required)<br>
Main command, has the following possible values
  - connect - Connect to BLE device and start background service
  - disconnect - Disconnect from BLE device and stop background service
  - send - Send string to connected BLE device
<br><br>
- macAddress (required when using connect command)<br>
MAC address of device to connect to (for example `AA:11:BB:C3:D5:B6:66`). If you do not know this, you can find it by 
opening the app and scanning for devices. The MAC address will be below the device name.
- reconnectTimeout (optional, only relevant with connect command)<br>
Maximum amount of time the service should try to reconnect after losing connection (in milliseconds) default is 
30000 (5 minutes), set to 0 to infinitely retry (until either reconnected or service is stopped).
<br><br>
- text (required when using send) <br>
String to send to connected device.

### Receiving data
The service will send an intent with action `TASKER_BLE` and scheme `tasker` with data in the form: `tasker: <string sent by BLE device>`<br>
Additionally, for debugging purposes, some logging is sent via an intent with action `TASKER_BLE_INFO`, and scheme `tasker`.

### Tasker specific instructions
You can send intents in tasker by using the `Java Function` task. You can receive intents using the `Intent Received` event, then the data will be available in the local variable `%intent_data`.
You can import taskerBleExample.xml into Tasker for working examples.

### More information
[Tasker User Guide - Intents](https://tasker.joaoapps.com/userguide/en/intents.html) <br>
[Tasker User Guide - Java Support](https://tasker.joaoapps.com/userguide/en/java.html) <br>
[Android docs - Intents](https://developer.android.com/guide/components/intents-filters) <br>


---
# Original Readme

This Android app provides a line-oriented terminal / console for Bluetooth LE (4.x) devices implementing a custom serial profile

For an overview on Android BLE communication see 
[Android Bluetooth LE Overview](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview).

In contrast to classic Bluetooth, there is no predifined serial profile for Bluetooth LE, 
so each vendor uses GATT services with different service and characteristic UUIDs.

This app includes UUIDs for widely used serial profiles:
- Nordic Semiconductor nRF51822  
- Texas Instruments CC254x
- Microchip RN4870/1
- Telit Bluemod

## Motivation

I got various requests asking for help with Android development or source code for my
[Serial Bluetooth Terminal](https://play.google.com/store/apps/details?id=de.kai_morich.serial_bluetooth_terminal) app.
Here you find a simplified version of my app.
