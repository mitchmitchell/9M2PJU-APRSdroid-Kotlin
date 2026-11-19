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
- **Defaults match stock aprscot** (UID `APRS.<call>`, contact
  `<call> (APRS)`, type `a-f-G-I-U-T-r`, 60 min stale), so a station seen by
  both aprscot (APRS-IS) and APRSdroid (RF) is one marker in ATAK.
- Each event carries `<__aprs symbol=".." source="rf|aprs-is" origin=".."/>`
  in its detail for ATAK plugins. APRS-symbol icons (`usericon` from an
  iconset) are not wired up yet.
