# History server

APRSDroid-TAK only knows what it has heard while it was running. A history
server is a small service you run yourself that stays connected to APRS-IS,
stores packets, and answers one question for the app: what has this station
done lately? With one set up, a station's **Track** and **Weather** tabs show
history from before the app was opened.

Set it under **Settings → Online lookups → History server**. The app uses the
server when it answers and falls back to its own data when it does not; the
station page says which one it used.

## The API

One request, read-only.

```
GET {base}/v1/stations/{CALLSIGN}/history?hours={N}
Authorization: Bearer {token}        (only if a token is set in the app)
```

- `{CALLSIGN}` is upper case and includes the SSID, URL-encoded (`AE5HO-9`).
- `hours` is how far back to look. The app asks for 168 (one week). A server
  may return less.
- Reply `200` with JSON. Any other status is treated as "not available".

```json
{
  "call": "AE5HO",
  "positions": [
    { "ts": 1795200000, "lat": 33.0112, "lon": -97.0527 }
  ],
  "weather": [
    { "ts": 1795200000, "raw": "114/001g002t071r000p000P000h68b10169L009" },
    { "ts": 1795200300, "temp_f": 71, "humidity": 68, "pressure_tenths_hpa": 10169,
      "wind_dir": 114, "wind_mph": 1, "gust_mph": 2,
      "rain_1h": 0, "rain_24h": 0, "rain_today": 0, "luminosity": 9 }
  ]
}
```

- `ts` is Unix time in seconds (UTC). Order does not matter; the app sorts.
- `positions` and `weather` are both optional. Entries missing `ts` (or
  `lat`/`lon` for positions) are skipped.
- A weather entry is either `raw`, the APRS weather string exactly as sent
  (the part after the `_` symbol), or decoded fields in APRS's own units:
  degrees, mph, °F, rain in hundredths of an inch, pressure in tenths of
  hPa, luminosity in W/m². Send whichever is easier; `raw` wins if both are
  present.
- Unknown fields are ignored, so the reply can grow.

## Building one

Connect to an APRS-IS server (for example `rotate.aprs2.net:14580`) with a
filter for the area you care about, such as `r/33.0/-97.0/300` for 300 km
around Dallas-Fort Worth. For each packet, store the time and callsign with
the position, and the weather string when the symbol is `_`. Keep as many
days as you want to be able to look back. Put it behind HTTPS or a private
network such as Tailscale; the data is public APRS traffic, but there is no
reason to serve strangers.

A receive-only APRS-IS connection needs a callsign and the passcode `-1`.
