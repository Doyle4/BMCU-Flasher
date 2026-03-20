# BMCU Flasher

Cross-platform flasher for BMCU (WCH ISP protocol).

![GUI](gui.jpg)

- OS: Linux / Windows / macOS / Android
- Modes:
  - USB (BMCU with CH340 on board, AutoDI)
  - TTL (pin header / external USB-Serial, manual BOOT + RESET)
- Android:
  - USB (CH340 + USB OTG)
  - TTL (external USB-Serial, manual BOOT + RESET)
  - Online flash only (no local .bin picker)
  - App language follows system language

## Download
Download prebuilt binaries from Releases.

Main builds:
- Windows x64: BMCU-Flasher-windows-x64.zip
- Windows x86: BMCU-Flasher-windows-x86.zip
- macOS Apple Silicon (arm64): BMCU-Flasher-macos-arm64.zip
- macOS Intel (x86_64): BMCU-Flasher-macos-x86_64.zip
- Linux x64: BMCU-Flasher-linux-x64.tar.gz
- Linux arm64: BMCU-Flasher-linux-arm64.tar.gz
- Android APK: BMCU-Flasher-android.apk

Legacy / experimental / community (older OS, best-effort):
- Windows legacy (older Windows): BMCU-Flasher-windows-legacy-x64.zip, BMCU-Flasher-windows-legacy-x86.zip
- macOS Catalina 10.15 (Intel): BMCU-Flasher-catalina-1.2.1.zip (community build by Doyle4, tested on Catalina)
- macOS legacy try (CI attempt): BMCU-Flasher-macos-x86_64-legacy-try.zip (may not run on Catalina)

## Android requirements
- Android 5.0+ (API 21+) (minSdk=21)
- USB OTG + USB host mode
- CH340 supported for USB mode
- External USB-Serial adapters supported for TTL mode
- Do NOT plug USB or flash while BMCU is connected to the printer

## Firmware
Latest BMCU firmware:
https://github.com/jarczakpawel/BMCU-C-PJARCZAK

GUI has "Online" firmware selection (no manual searching/downloading):
- choose mode (Standard / Soft load / High force)
- choose slot (SOLO / AMS_A / AMS_B / AMS_C / AMS_D)
- choose retract length, AUTOLOAD, RGB
- the app downloads the selected firmware and flashes it

## Drivers (CH340)
In USB mode (CH340), drivers may be required on Windows.
Linux and macOS usually work out-of-the-box.

## Usage
GUI (recommended):
- Run the app
- Click "Online" to pick the exact firmware variant (mode / slot / retract / autoload / RGB)
- Click "Help" inside the app (wiring + BOOT/RESET steps)

Android:
- Install APK
- Choose USB or TTL mode
- USB mode: plug BMCU via USB OTG
- TTL mode: connect external USB-Serial adapter and enter BOOTloader manually
- Select firmware options
- Click "Flash"

CLI (optional):
- USB (auto port by VID/PID):
```bash
python3 bmcu_flasher.py firmware.bin --mode usb
```
- TTL (manual BOOT+RESET, port required):
```bash
  python3 bmcu_flasher.py firmware.bin --mode ttl --port /dev/ttyUSB0
```

## License
MIT - see LICENSE.
