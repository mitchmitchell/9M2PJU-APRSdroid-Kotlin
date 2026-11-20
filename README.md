# APRSDroid-TAK
### *APRS client for Android with a TAK link*

> **APRSDroid-TAK** is an APRS client for Android that also sends the stations
> it hears to ATAK on the same device and to a TAK Server, with proper APRS
> icons. It adds Google Maps and a redesigned interface. It builds on
> [9M2PJU's Kotlin rewrite](https://github.com/9M2PJU/APRSdroid-9M2PJU-Mod),
> [NA7Q's enhanced APRSdroid](https://github.com/na7q/aprsdroid) and
> [Georg Lukas's original APRSdroid](https://aprsdroid.org/).

**[Maps and ATAK setup](doc/MAPS-AND-ATAK.md)** | **[Original APRSdroid](https://aprsdroid.org/)** | **[NA7Q Fork](https://github.com/na7q/aprsdroid)** | **[Source](https://github.com/mitchmitchell/APRSDroid-TAK)**

---

## About

**APRSDroid-TAK** is an Android client for the
[APRS (Automatic Packet Reporting System)](http://aprs.org/) network.
It is a fork of the APRSdroid 9M2PJU Mod, which is a fork of
[NA7Q's enhanced APRSdroid](https://github.com/na7q/aprsdroid),
which is itself a fork of [Georg Lukas's original APRSdroid](https://aprsdroid.org/).

### The APRSdroid family tree

| Project | Author | Description |
|---|---|---|
| [APRSdroid](https://aprsdroid.org/) | Georg Lukas (ge0rg) | The original APRS client for Android, since 2010. Scala + Java, GPLv2. |
| [NA7Q's fork](https://github.com/na7q/aprsdroid) | NA7Q | Enhanced fork with BLE TNC, digipeater, IGate, Mic-E, offline maps. [Homepage](https://na7q.com/aprsdroid-osm/) |
| [APRSdroid 9M2PJU Mod](https://github.com/9M2PJU/APRSdroid-9M2PJU-Mod) | 9M2PJU | Builds on NA7Q's fork with modern Android, Material UI, in-app updates, and a Kotlin and Jetpack Compose rewrite. |
| **APRSDroid-TAK** (this repo) | AE5HO | Adds ATAK and TAK Server export, Google Maps, and a redesigned interface. |

### What APRSDroid-TAK adds

- **Stations to ATAK**: every station heard is sent as Cursor on Target to
  ATAK on the same device (UDP) and, optionally, to a TAK Server over TLS
  with a client certificate
- **APRS icons in TAK**: a built-in iconset you can share to ATAK, so
  stations show with their APRS symbols in their own "APRS" group
- **Google Maps**: the Maps SDK with a build-time key, or Google map tiles
  with a key you enter in Settings. No key is stored in the source
- **Redesigned interface**: map-first layout, day and night themes, a
  transmit strip for sending position and tracking, clearer station list,
  station details and log
- **Passcode masked** in the log and in exported logs

Setup is described in [doc/MAPS-AND-ATAK.md](doc/MAPS-AND-ATAK.md).

### What 9M2PJU added on top of NA7Q's fork

- **Adaptive launcher icon** for Android 13+ — full logo with navy
  background, no default border (matches WhatsApp-style icons)
- **Modern Android support** — `targetSdk 35` (Android 15), foreground service
  types for Android 14+, Bluetooth permissions for Android 12+, storage
  permissions for Android 11+, `POST_NOTIFICATIONS` for Android 13+,
  edge-to-edge opt-out for Android 15+ with proper system bar inset handling
  (status bar, navigation bar, display cutout)
- **Material Design dark theme** — `Theme.MaterialComponents` with navy/amber
  palette, bottom navigation bar, modern component styles
- **Branded splash screen** — full-screen splash with centerCrop rendering
- **In-app update checker** — download and install updates directly from
  GitHub Releases, with Cloudflare CDN fallback when GitHub API is unreachable
- **GitHub Actions CI/CD** — signed release APK builds, automatic GitHub
  Releases on `v*` tags, auto-generated `version.json` for the update checker
- **New app icon and logo** across all density buckets
- **Map auto-zooms to GPS location** — on first open, the map centers on
  the user's last known GPS position instead of a hardcoded default
- **Map recenter button repositioned** — sits above the OSM zoom controls
  to avoid overlap
- **Restored OSM Maps preferences** — file picker first, then offline
  mapping toggle (with guidance to select a map file before enabling),
  MapsForge / MBTiles file picker, storage permissions button, hardware
  acceleration toggle
- **About dialog with logo and full credits** — app logo, project lineage,
  and credits to Bob Bruninga (WB4APR), Georg Lukas (DO1GL), NA7Q, and 9M2PJU
- **Bottom navigation with Menu tab** — quick access to Preferences, Hub,
  Log, Map, Messages, and more from any screen, with no sliding animation
  between tabs
- **Android 14+ crash fix** — `RECEIVER_NOT_EXPORTED` flag for
  `registerReceiver` (required by Android 14/Upside Down Cake)
- **App name and About dialog unified** across all 52 locale files
- **Version bumped** to `v2.0.0` (tocall `APDR20`)

### Features inherited from NA7Q's fork

- **Digipeater** — direct or full digipeating
- **2-Way IGating** — full Internet Gateway functionality
- **BLE TNC support** — Mobilinkd, DigiRig, and other Bluetooth TNCs
- **Offline maps** — MBTiles + MapsForge V3 with OpenStreetMap
- **Radio control** — Vero, BTech, Radioddity, and other radios
- **Mic-E compression** — efficient position encoding with emergency status
- **Symbol overlay support**
- **Unit options** — metric or imperial

### Features from the original APRSdroid

- **Real-time position reporting** via GPS
- **APRS messaging** — send and receive messages
- **TCP/IP connectivity** to APRS-IS
- **AFSK modem** for audio-based TNC
- **USB TNC support**

---

## Quick Start

### Installation

> **Important:** Uninstall the official APRSdroid or any other fork before
> installing. They share a package name but are signed with different keys,
> so Android will refuse an in-place upgrade.

1. Build the APK from source (see [doc/MAPS-AND-ATAK.md](doc/MAPS-AND-ATAK.md)
   for the signing and Maps key settings). There are no public releases yet.
2. Install the APK on your Android device (enable "Install from unknown
   sources" if prompted)
3. On Android 11+, grant *All files access* for offline MBTiles maps

### Maps

The default map is **OpenStreetMap (online)** and auto-zooms to your
GPS location on first open. To use offline maps:

1. Go to **Menu** (bottom nav) → **Preferences** → **OSM Maps**
2. Tap **"Select MapsforgeV3 or .mbtiles map"** and choose your `.map`
   or `.mbtiles` file
3. Enable **"Enable offline mapping"**
4. (Optional) Tap **"Grant Storage Permissions"** if the file can't be
   read
5. (Optional) Toggle **"Enable Hardware Acceleration"** for smoother
   map rendering (on by default)
6. Open **Map** — offline tiles will load without internet

#### Other map sources

NA7Q provides several tools for downloading offline maps for other regions:

| Tool | Platform | Description |
|---|---|---|
| [World Map](https://na7q.com/wp-content/uploads/2024/12/map.mbtiles) | Any | Ready-to-use starter world map (zoom 6) |
| [OSM Map Maker](https://downloads.aprs.wiki/APRSdroid/gui7-concurrency.exe) | Windows | GUI tool for downloading OSM maps |
| [Python Map Maker](https://na7q.com/wp-content/uploads/2025/01/gui7-concurrency.py) | Cross-platform | Python script version |
| [Multi-Map Maker](https://na7q.com/wp-content/uploads/2025/02/mapmaker-0.2.exe) | Windows | Google, OSM, USGS, Canada Topo, and more |
| [Map Viewer](https://na7q.com/wp-content/uploads/2025/02/mapviewer.exe) | Windows | Preview different map styles |
| [BBBike MapsForge](https://extract.bbbike.org/) | Web | Custom MapsForge OSM maps online |

### Connecting a LoRa APRS Tracker via Bluetooth (BLE KISS TNC)

APRSdroid can connect to LoRa APRS tracker boards (e.g. LilyGO T-Beam,
TTGO T-Beam, Heltec WiFi LoRa 32, RAK Wireless modules, and other
ESP32-based APRS trackers) using **Bluetooth Low Energy (BLE)** with
the **KISS TNC** protocol. This lets you send and receive APRS packets
over LoRa radio without a phone data connection.

#### Requirements

- A LoRa APRS tracker board with BLE KISS TNC firmware (e.g.
  [richonguzman's LoRa APRS Tracker](https://github.com/richonguzman/LoRa_APRS_Tracker))
- Android phone with Bluetooth
- APRSDroid-TAK installed

#### Setup steps

1. **Flash your tracker board** with BLE KISS TNC firmware (if not
   already done). The firmware must advertise a BLE KISS service.
2. **Power on the tracker** and put it in BLE pairing mode (usually
   automatic on boot).
3. **Pair the tracker with your phone:**
   - Go to Android **Settings** → **Bluetooth**
   - Find your tracker in the device list (e.g. "LoraTracker" or
     "LoRa_APRS")
   - Tap to pair
4. **Configure APRSdroid:**
   - Open APRSdroid → **Menu** → **Preferences** → **Connection Setup**
   - Select **Bluetooth TNC (KISS)** as the backend
   - Select your tracker from the Bluetooth device list
   - Set your callsign and passcode
5. **Start the service:**
   - Tap the **Start** button (or **Menu** → **Start**)
   - APRSdroid will connect to the tracker via BLE
   - Position reports are sent over LoRa radio
   - Received packets appear in the Log and on the Map

#### How it works

```
GPS → Phone → APRSdroid → BLE → LoRa Tracker → LoRa Radio → APRS Network
                                                    ↓
                                          Received packets → APRSdroid
```

- **Outgoing:** APRSdroid gets your GPS position, formats an APRS
  packet, and sends it to the tracker via BLE KISS TNC. The tracker
  transmits it over LoRa radio.
- **Incoming:** The tracker receives LoRa APRS packets and forwards
  them to APRSdroid via BLE. APRSdroid decodes and displays them.

---

## Documentation & Support

- [Maps and ATAK setup](doc/MAPS-AND-ATAK.md)
- [Original APRSdroid FAQ](https://github.com/ge0rg/aprsdroid/wiki/Frequently-Asked-Questions)
- [Original APRSdroid Configuration Guide](https://github.com/ge0rg/aprsdroid/wiki/Settings)
- [NA7Q's Homepage](https://na7q.com/aprsdroid-osm/) & [Changelog](https://na7q.com/aprsdroid-changelog/)

---

## License

This project is licensed under the **GNU General Public License v2.0** — see
the [LICENSE](LICENSE) file for details.

---

## Contributing

Contributions are welcome from the amateur radio community! Please open an
issue or pull request at the
[APRSDroid-TAK repo](https://github.com/mitchmitchell/APRSDroid-TAK).

---

## Credits

### Georg Lukas (ge0rg) — Original Author

[APRSdroid](https://aprsdroid.org/) was created by **Georg Lukas, ge0rg** and
has been the premier APRS client for Android since 2010. Written in Scala
with a Java core, it supports TCP/IP, AFSK, Bluetooth TNCs, and USB TNCs.
Georg continues to maintain the upstream project and licenses it under the
GPLv2 so that the community can build on his work.

### NA7Q — Enhanced Fork

[NA7Q's fork](https://github.com/na7q/aprsdroid) builds on Georg's original
work with BLE TNC support, digipeater and IGate functionality, symbol
overlays, Mic-E improvements, and offline mapping. NA7Q's fork is the direct
upstream of the 9M2PJU Mod. Homepage: <https://na7q.com/aprsdroid-osm/>

### 9M2PJU — APRSdroid 9M2PJU Mod

**9M2PJU** (Kuala Lumpur, Malaysia) built on NA7Q's fork with modern Android
compatibility, Material Design UI, bottom navigation, in-app updates and
CI/CD, and rewrote the app in Kotlin and Jetpack Compose. That rewrite is
the direct upstream of this project.

### Bob Bruninga, WB4APR — Creator of APRS

**Bob Bruninga, WB4APR** (1947–2022) created the Automatic Packet Reporting
System (APRS) in the 1980s. His vision of a real-time, position-aware digital
communications network for amateur radio lives on in every APRS client,
igate, and digipeater worldwide. Without Bob's pioneering work, none of this
would exist.

APRS® is a registered trademark of Bob Bruninga, WB4APR.
