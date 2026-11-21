# Google Maps, signing and ATAK/TAK export

## Map types

Pick the map type from the layers button on the map, or in Settings → Map.

| Map type | Screen | Needs |
|---|---|---|
| OpenStreetMap | Mapsforge map (`MapAct`) | nothing |
| Google tiles: Roadmap / Satellite / Satellite + labels / Terrain | Mapsforge map (`MapAct`) | a Map Tiles API key (entered in the app or at build time) |
| Google: Map / Satellite / Terrain | Google Maps SDK (`GoogleMapAct`) | `mapsApiKey` at build time and Google Play services |

Station icons, traces and the "pick position from map" screen work on all
Mapsforge types. Types that are missing what they need are greyed out.

## Keys (never committed)

`local.properties` is git-ignored. Put keys there:

```properties
# Google Maps SDK (Android). Restrict in Google Cloud to:
#   Android apps: org.aprsdroid.app + your signing certificate's SHA-1
#   APIs: Maps SDK for Android
mapsApiKey=AIza...

# Optional default for the Google tiles types. Users can also enter (or
# override) this in Settings -> Map. Restrict it to the "Map Tiles API"
# only and set a daily quota: Mapsforge can't send the Android app headers,
# so an "Android apps" restriction would reject the tile requests.
mapsTilesApiKey=AIza...
```

CI can use the `MAPS_API_KEY` / `MAPS_TILES_API_KEY` environment variables instead.
A key typed into Settings is stored in a separate preferences file that is
excluded from profile export and from Android backup.

## Signing with your own CA's certificate

Any PKCS#12 certificate works; Android ignores the CA chain and expiry and
treats the certificate itself as the app's identity. Use a dedicated
long-lived certificate and **never renew or re-key it**: a different
certificate means Android refuses to install updates over the existing app.

```properties
signing.storeFile=C:/Users/you/keys/aprsdroid-sign.p12
signing.storePassword=...
signing.keyAlias=aprsdroid
# signing.keyPassword=...   (defaults to storePassword)
```

When set, both debug and release builds are signed with it, so the Maps key
only needs one SHA-1. `gradlew signingReport` prints the fingerprints.

## ATAK / TAK export

Settings → ATAK / TAK export. Every received APRS position (stations,
objects, items) is sent as Cursor-on-Target. Changes apply immediately.

- **ATAK on this phone:** UDP to `127.0.0.1:4242`. ATAK listens there by
  default; check Settings → Network Preferences → Manage Inputs.
  `239.2.3.1:6969` sends to the Mesh SA multicast group instead (Wi-Fi only).
- **TAK Server:** TLS (normally port 8089) with a client certificate .p12
  imported in the settings screen. The CA certificates inside that .p12 are
  used to verify the server, so a private CA (e.g. OpenXPKI) works without
  being globally trusted. The server's host name is checked against its
  certificate unless switched off. Plain TCP (8087) is available for testing.
- **Defaults match the aprscot gateway on the TAK Server** (UID
  `APRS.<call>`, contact `<call> (APRS)`, one type `a-f-G-U-C`, 60 min
  stale), so a station seen by both aprscot (APRS-IS) and APRSdroid (RF) is
  one marker in ATAK. APRSdroid also sends real course and speed.
- **APRS icons and the "APRS" overlay:** each event carries
  `<usericon iconsetpath="<uid>/APRS/tableN_symbol_NN.png"/>`, the same
  scheme as the gateway (GrokAPRS). ATAK draws the APRS symbol from the
  "APRS" iconset and groups every station under one toggleable "APRS"
  overlay, named after the group segment of the path. The symbol is never
  encoded in the CoT type. If ATAK does not already have the iconset from
  the TAK Server, "Send the APRS iconset to ATAK" builds the same package
  (same UID, group and file names) from the app's own symbol sheet.
- Each event also carries `<__aprs symbol=".." source="rf|aprs-is"
  origin=".."/>` in its detail for ATAK plugins.

## Note on the bundled Mapsforge library

`libs/mapsforge-map-0.3.0-jar-with-dependencies.jar` is Mapsforge 0.3 (LGPL)
with one class removed:
`org.mapsforge.v3.android.maps.mapgenerator.tiledownloader.TileDownloader`.
Its replacement is in this repository at
`src/org/mapsforge/v3/android/maps/mapgenerator/tiledownloader/TileDownloader.kt`.
The original had no network timeouts, which could freeze the app when a tile
request stalled, and crashed on a response that was not a 256x256 image. To
go back to the stock class, restore the original jar and delete that file.

## Two-way link: messages and markers

Settings → ATAK / TAK export → **From TAK to APRS**.

**Messages** (on by default once the export is on)

- An APRS message addressed to you is also sent to TAK as a chat from that
  station's contact (`APRS.<call>`). It goes directly to the TAK user who
  last wrote to that station, or last wrote to any APRS station; until
  someone has, it goes to *All Chat Rooms*.
- Each exported station is a contact TAK can message. A chat typed in TAK to
  one of them is transmitted as an APRS message through the normal message
  queue (retries, acks, visible in the app's Messages). Text longer than 67
  characters is cut into up to four messages. Chats to anyone else, and to
  chat rooms, never go on the air.
- ATAK on the same phone reaches the app on `127.0.0.1:4349` (TCP and UDP,
  loopback only, port configurable). Through a TAK Server the chats come back
  on the same TLS connection the stations go out on.

**TAK markers as APRS objects** (off by default)

- A marker a person placed in TAK is transmitted as an APRS object under
  your callsign, named with the first nine characters of the marker's name.
  An unmoved marker is repeated at most every ten minutes.
- For ATAK on the same phone, add an output in ATAK (Settings → Network
  Preferences → Manage Outputs): UDP, `127.0.0.1`, port 4349. From a TAK
  Server, every marker the server forwards to you is eligible, so think
  before turning this on with a busy server.

You are the control operator for everything this sends over the radio,
including text typed by another TAK user through a server.

## Map engine (MapLibre) and offline maps

The OpenStreetMap map type and two vector map types are drawn by
[MapLibre Native](https://maplibre.org) in `MapLibreAct`:

| Map type | Source | Can be saved |
|---|---|---|
| OpenStreetMap | `tile.openstreetmap.org` picture tiles | No (their usage policy forbids bulk saving) |
| Street map | OpenFreeMap "liberty" vector style | Yes |
| Quiet map | OpenFreeMap "positron" vector style | Yes |

No key or account is needed. Vector maps stay sharp at any zoom, rotate
and tilt with two fingers, and need far less data than picture tiles.

**Offline maps.** On a vector map, the download button opens *Offline maps*.
*Save this area* stores the area on screen from the current zoom down to
street level; zoom in if the app says the area is too large (the limit is
6000 tiles). Saved areas are listed there and can be deleted. Areas you
have merely looked at are also kept in a cache and usually still draw with
no connection.

Stations, callsign labels and clusters are drawn by the app itself, so they
appear with or without a base map.

The Google Map Tiles types and the position chooser still use the old
Mapsforge screen, which also remains selectable as *OpenStreetMap (old map
screen)* in case the new one misbehaves on a device.

## Alerts

Settings → Alerts. They run while tracking is on (the service that receives
packets does the watching) and arrive as normal notifications on their own
channel, so sound and vibration are set in Android's notification settings.

- **Stations I watch**: a list of callsigns. You can be told when one comes
  on the air after 30 minutes of silence, and when one starts moving (300 m
  from where it sat; at most every 15 minutes while it keeps going).
- **Stations near me**: when a station comes within a distance of the phone,
  or of a fixed latitude/longitude. Watched stations by default; optionally
  any station on the move. A station must leave (20% past the edge) before
  it can alert again.
- **Weather warnings**: active alerts from the US National Weather Service
  (`api.weather.gov`) for the same position, checked every 10 minutes, each
  shown once. US only; needs an internet connection.

Tapping a station alert opens that station's page.
